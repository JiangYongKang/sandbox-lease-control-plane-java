package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseService;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseServiceException;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.QuotaView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.ReleaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.RenewRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.pool.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision.LocalSandboxProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.quota.TeamQuotaRegistry;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.store.LeaseStore;
import com.github.highcumontoa.sandboxleasecontrolplanejava.tests.LeaseServiceLifecycleTest.MutableClock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 请求标识（requestId）跨操作复用测试：首次结论为被拒（团队额度超限 / 整池已满）、
 * 失败（供应失败补偿）或成功的 requestId，再次被用于申请 / 续约 / 释放时，
 * 行为必须确定且可区分——该复现首次结论的复现首次结论，该报冲突的报冲突，
 * 任何路径都不得冒出 INTERNAL_ERROR，也不得被当成全新操作悄悄放行。
 * 每次复用尝试前后都断言团队额度与整池占用不变，并保持
 * “各团队占用之和 == 整池 leased” 的对账不变量。
 * 日志统一打印用例、requestId、teamId 与额度/池占用变化，便于复现排查。
 */
class LeaseServiceRequestIdReuseTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseServiceRequestIdReuseTest.class);

    private record Harness(LeaseService service, ResourcePool pool, MutableClock clock) {
    }

    private Harness harness(int capacity, Map<String, Integer> teamQuotas) {
        LeaseProperties props = new LeaseProperties();
        props.setPoolCapacity(capacity);
        props.setDefaultTtlSeconds(60);
        props.setMaxTtlSeconds(3600);
        props.setTeamQuotas(teamQuotas);
        MutableClock clock = new MutableClock();
        ResourcePool pool = new ResourcePool(props);
        LeaseService service = new LeaseService(new LeaseStore(), pool, new TeamQuotaRegistry(props),
                new LocalSandboxProvisioner(), clock, props);
        return new Harness(service, pool, clock);
    }

    /** 对账：各团队占用之和必须等于整池占用。 */
    private void assertBalanced(Harness h, String... teamIds) {
        int sum = 0;
        for (String teamId : teamIds) {
            sum += h.service.teamQuota(teamId).used();
        }
        QuotaView q = h.service.quota();
        log.info("[test] balance check: teamUsedSum={}, poolLeased={}/{}", sum, q.leased(), q.capacity());
        assertEquals(q.leased(), sum, "各团队占用之和必须等于整池占用");
    }

    private LeaseServiceException expectConflict(LeaseServiceException ex, String scene, String requestId) {
        log.warn("[test] {} requestId={} -> code={} (must be conflict, never INTERNAL_ERROR)",
                scene, requestId, ex.code());
        assertNotEquals(ErrorCode.INTERNAL_ERROR, ex.code(), "复用请求标识不得产生内部错误");
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ex.code(),
                "被拒的 requestId 复用于续约/释放必须返回冲突类错误");
        return ex;
    }

    @Test
    void teamQuotaRejected_reusedForRenewAndRelease_conflictNotInternalError() {
        Harness h = harness(2, Map.of("team-a", 1));

        // 首次：team-a 占满自己的额度
        LeaseView a1 = h.service.apply(new LeaseRequest("ru-a1", "team-a", 60));
        assertEquals("ACTIVE", a1.status());
        // 第二次：同一团队额度已满 -> TEAM_QUOTA_EXCEEDED，requestId=ru-rej 落下“被拒”结论
        LeaseServiceException rejected = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("ru-rej", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, rejected.code());
        log.warn("[test] case=team-quota-rejected requestId=ru-rej team=team-a firstCode={}", rejected.code());

        int teamUsedBefore = h.service.teamQuota("team-a").used();
        int poolLeasedBefore = h.pool.leased();
        long expiresBefore = h.service.get(a1.leaseId()).expiresAtMillis();
        log.info("[test] before reuse: team=team-a used={}, poolLeased={}/{}",
                teamUsedBefore, poolLeasedBefore, h.pool.capacity());

        // 复用 1：拿被拒的 requestId 去续约另一个正在使用的环境 -> 冲突，不得 500
        expectConflict(assertThrows(LeaseServiceException.class,
                () -> h.service.renew(a1.leaseId(), new RenewRequest("ru-rej", 120))),
                "renew-after-team-quota-reject", "ru-rej");

        // 复用 2：同一 requestId 再用于释放 -> 同样冲突，不得被当成全新释放放行
        expectConflict(assertThrows(LeaseServiceException.class,
                () -> h.service.release(a1.leaseId(), new ReleaseRequest("ru-rej"))),
                "release-after-team-quota-reject", "ru-rej");

        // 边界：复用不得多占额度、不得改动整池占用、不得改动目标租约
        assertEquals(teamUsedBefore, h.service.teamQuota("team-a").used(), "复用不得多占团队额度");
        assertEquals(poolLeasedBefore, h.pool.leased(), "复用不得改动整池占用");
        LeaseView after = h.service.get(a1.leaseId());
        assertEquals("ACTIVE", after.status(), "冲突的续约/释放不得改动目标租约状态");
        assertEquals(expiresBefore, after.expiresAtMillis(), "冲突的续约不得改动到期时间");
        log.info("[test] after reuse: team=team-a used={}, poolLeased={}/{}, lease={} status={}",
                h.service.teamQuota("team-a").used(), h.pool.leased(), h.pool.capacity(),
                a1.leaseId(), after.status());
        assertBalanced(h, "team-a");
    }

    @Test
    void poolExhausted_reusedForRenewAndRelease_conflictNotInternalError() {
        Harness h = harness(1, Map.of());

        // 首次：占满整池
        LeaseView b1 = h.service.apply(new LeaseRequest("ru-b1", "team-b", 60));
        assertEquals("ACTIVE", b1.status());
        // 第二次：整池已满 -> QUOTA_EXHAUSTED，requestId=ru-full 落下“被拒”结论
        LeaseServiceException rejected = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("ru-full", "team-b", 60)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, rejected.code());
        log.warn("[test] case=pool-exhausted requestId=ru-full team=team-b firstCode={}", rejected.code());

        int teamUsedBefore = h.service.teamQuota("team-b").used();
        int poolLeasedBefore = h.pool.leased();
        log.info("[test] before reuse: team=team-b used={}, poolLeased={}/{}",
                teamUsedBefore, poolLeasedBefore, h.pool.capacity());

        // 复用 1：被拒的 requestId 用于续约 -> 冲突，不得 500
        expectConflict(assertThrows(LeaseServiceException.class,
                () -> h.service.renew(b1.leaseId(), new RenewRequest("ru-full", 120))),
                "renew-after-pool-exhausted", "ru-full");

        // 复用 2：同一 requestId 用于释放 -> 冲突，不得悄悄放行
        expectConflict(assertThrows(LeaseServiceException.class,
                () -> h.service.release(b1.leaseId(), new ReleaseRequest("ru-full"))),
                "release-after-pool-exhausted", "ru-full");

        assertEquals(teamUsedBefore, h.service.teamQuota("team-b").used(), "复用不得多占团队额度");
        assertEquals(poolLeasedBefore, h.pool.leased(), "复用不得改动整池占用");
        assertEquals("ACTIVE", h.service.get(b1.leaseId()).status(), "冲突的续约/释放不得改动目标租约");
        log.info("[test] after reuse: team=team-b used={}, poolLeased={}/{}",
                h.service.teamQuota("team-b").used(), h.pool.leased(), h.pool.capacity());
        assertBalanced(h, "team-b");
    }

    @Test
    void rejectedRequestId_reusedForApply_reproducesSameRejection() {
        Harness h = harness(2, Map.of("team-a", 1));

        h.service.apply(new LeaseRequest("rp-a1", "team-a", 60));
        LeaseServiceException teamFirst = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("rp-rej", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, teamFirst.code());

        // 复用于申请：必须复现首次被拒结论（团队额度），而不是冲突或成功
        LeaseServiceException teamDup = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("rp-rej", "team-a", 60)));
        log.warn("[test] case=apply-reuse requestId=rp-rej team=team-a first={} reused={}",
                teamFirst.code(), teamDup.code());
        assertNotEquals(ErrorCode.INTERNAL_ERROR, teamDup.code());
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, teamDup.code(), "申请复用必须复现首次被拒结论");
        assertEquals(1, h.service.teamQuota("team-a").used(), "复用不得多占团队额度");

        // 整池已满的被拒结论同样复现
        h.service.apply(new LeaseRequest("rp-b1", "team-b", 60)); // 池满 2/2
        LeaseServiceException poolFirst = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("rp-full", "team-b", 60)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, poolFirst.code());
        LeaseServiceException poolDup = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("rp-full", "team-b", 60)));
        log.warn("[test] case=apply-reuse requestId=rp-full team=team-b first={} reused={}",
                poolFirst.code(), poolDup.code());
        assertNotEquals(ErrorCode.INTERNAL_ERROR, poolDup.code());
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, poolDup.code(), "申请复用必须复现整池已满结论");
        assertEquals(2, h.pool.leased(), "复用不得改动整池占用");
        assertBalanced(h, "team-a", "team-b");
    }

    @Test
    void failedAndSucceededRequestId_reuseIsDeterministicAndDistinguishable() {
        Harness h = harness(2, Map.of("fail:team-x", 1));

        // 首次结论为失败：供应异常 -> 补偿后落 FAILED
        LeaseServiceException failed = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("ru-fail", "fail:team-x", 60)));
        assertEquals(ErrorCode.PROVISION_FAILED, failed.code());
        log.warn("[test] case=provision-failed requestId=ru-fail team=fail:team-x firstCode={}",
                failed.code());

        // 首次结论为成功：team-y 正常开出两个环境
        LeaseView y1 = h.service.apply(new LeaseRequest("ru-ok", "team-y", 60));
        LeaseView y2 = h.service.apply(new LeaseRequest("ru-ok2", "team-y", 60));
        assertEquals("ACTIVE", y1.status());
        assertEquals("ACTIVE", y2.status());

        int poolLeasedBefore = h.pool.leased();

        // 失败的 requestId 复用于续约他人租约 -> 冲突（属于其它租约的结论），不得 500
        expectConflict(assertThrows(LeaseServiceException.class,
                () -> h.service.renew(y2.leaseId(), new RenewRequest("ru-fail", 120))),
                "renew-with-failed-requestId", "ru-fail");

        // 成功的 requestId 复用于续约他人租约 -> 冲突，不得 500
        expectConflict(assertThrows(LeaseServiceException.class,
                () -> h.service.renew(y2.leaseId(), new RenewRequest("ru-ok", 120))),
                "renew-with-others-requestId", "ru-ok");

        // 成功的 requestId 复用于释放他人租约 -> 冲突，不得悄悄放行
        expectConflict(assertThrows(LeaseServiceException.class,
                () -> h.service.release(y2.leaseId(), new ReleaseRequest("ru-ok"))),
                "release-with-others-requestId", "ru-ok");

        // 三种复用都不得改动额度与池占用
        assertEquals(poolLeasedBefore, h.pool.leased(), "复用不得改动整池占用");
        assertEquals(2, h.service.teamQuota("team-y").used());
        assertEquals(0, h.service.teamQuota("fail:team-x").used(), "失败补偿后额度必须已归还");
        assertEquals("ACTIVE", h.service.get(y1.leaseId()).status());
        assertEquals("ACTIVE", h.service.get(y2.leaseId()).status());
        log.info("[test] after reuse: team-y used={}, fail:team-x used={}, poolLeased={}/{}",
                h.service.teamQuota("team-y").used(), h.service.teamQuota("fail:team-x").used(),
                h.pool.leased(), h.pool.capacity());
        assertBalanced(h, "team-y", "fail:team-x");
    }
}
