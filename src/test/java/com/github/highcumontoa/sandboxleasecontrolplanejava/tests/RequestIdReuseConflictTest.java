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
 * 请求标识跨操作复用测试：一个 requestId 第一次的结论（被拒/成功/失败）必须在之后的
 * 申请、续约、释放复用中得到确定且可区分的结论——该冲突报冲突（409），该复现首次
 * 结论就复现首次结论，任何路径都不得退化成 500 内部错误，也不得多占额度、破坏
 * “各团队占用之和 == 整池 leased”的对账。
 *
 * <p>日志统一打印覆盖用例、requestId、teamId、团队额度与整池占用变化。
 */
class RequestIdReuseConflictTest {

    private static final Logger log = LoggerFactory.getLogger(RequestIdReuseConflictTest.class);

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

    /** 断言为可区分的业务冲突（409），绝不允许是 500 内部错误。 */
    private static void assertConflictNotInternal(LeaseServiceException ex) {
        assertNotEquals(ErrorCode.INTERNAL_ERROR, ex.code(), "标识复用不得退化成内部错误");
        assertEquals(409, ErrorCode.IDEMPOTENCY_KEY_CONFLICT.httpStatus());
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ex.code(), "复用被拒标识必须报标识冲突");
    }

    @Test
    void teamQuotaRejectedRequestId_reusedForRenewAnotherLease_conflictsNot500() {
        Harness h = harness(3, Map.of("team-a", 1));

        // team-a 先占满自己的额度（一个正在使用的环境）
        LeaseView own = h.service.apply(new LeaseRequest("ru-own", "team-a", 60));
        assertEquals(1, h.service.teamQuota("team-a").used());

        // 首次结论：团队额度被拒（不占任何额度）
        LeaseServiceException rejected = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("ru-rej", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, rejected.code());
        log.warn("[test] case=team-rejected-reuse-renew requestId=ru-rej team=team-a firstConclusion={}",
                rejected.code());
        assertEquals(1, h.service.teamQuota("team-a").used(), "被拒不占团队额度");

        // 另一个团队正在使用的环境（复用 bug 的受害租约）
        LeaseView other = h.service.apply(new LeaseRequest("ru-other", "team-b", 60));
        assertEquals("ACTIVE", other.status());
        assertEquals(2, h.pool.leased());

        // bug 现场：拿“首次被拒”的 requestId 去续约另一个在用环境，
        // 必须稳定得到 409 冲突，而不是 NPE -> 500。
        LeaseServiceException renewEx = assertThrows(LeaseServiceException.class,
                () -> h.service.renew(other.leaseId(), new RenewRequest("ru-rej", 30)));
        log.warn("[test] reuse requestId=ru-rej for renew leaseId={} code={} (must be conflict, never 500)",
                other.leaseId(), renewEx.code());
        assertConflictNotInternal(renewEx);

        // 再来一次，结论必须确定可复现
        LeaseServiceException renewEx2 = assertThrows(LeaseServiceException.class,
                () -> h.service.renew(other.leaseId(), new RenewRequest("ru-rej", 30)));
        assertEquals(renewEx.code(), renewEx2.code(), "同一标识重复复用结论必须一致");

        // 被拒标识复用不得多占额度、不得改动租约状态与整池占用
        assertEquals("ACTIVE", h.service.get(other.leaseId()).status(), "被冲突的续约不得改动租约");
        assertEquals(2, h.pool.leased(), "冲突路径不得多占整池额度");
        assertEquals(1, h.service.teamQuota("team-a").used());
        assertEquals(1, h.service.teamQuota("team-b").used());
        assertEquals("ACTIVE", h.service.get(own.leaseId()).status());
        assertBalanced(h, "team-a", "team-b");
    }

    @Test
    void poolExhaustedRejectedRequestId_reusedForRenewReleaseAndApply_isDeterministic() {
        Harness h = harness(2, Map.of());

        // 打满整池 2/2
        LeaseView a = h.service.apply(new LeaseRequest("px-a", "team-p", 60));
        LeaseView b = h.service.apply(new LeaseRequest("px-b", "team-q", 60));
        assertEquals(2, h.pool.leased());

        // 首次结论：整池已满被拒（不占额度）
        LeaseServiceException rejected = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("px-over", "team-p", 60)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, rejected.code());
        log.warn("[test] case=pool-rejected-reuse requestId=px-over team=team-p firstConclusion={}",
                rejected.code());
        assertEquals(2, h.pool.leased(), "被拒不占整池额度");

        // 复用于续约在用环境 -> 409 冲突（不是 500）
        LeaseServiceException renewEx = assertThrows(LeaseServiceException.class,
                () -> h.service.renew(b.leaseId(), new RenewRequest("px-over", 30)));
        log.warn("[test] reuse requestId=px-over for renew leaseId={} code={}",
                b.leaseId(), renewEx.code());
        assertConflictNotInternal(renewEx);

        // 复用于释放在用环境 -> 409 冲突（此前会被当全新释放悄悄放行并归还额度）
        LeaseServiceException releaseEx = assertThrows(LeaseServiceException.class,
                () -> h.service.release(b.leaseId(), new ReleaseRequest("px-over")));
        log.warn("[test] reuse requestId=px-over for release leaseId={} code={}",
                b.leaseId(), releaseEx.code());
        assertConflictNotInternal(releaseEx);

        // 冲突路径既没有释放、也没有多占：b 仍 ACTIVE，整池仍 2/2
        assertEquals("ACTIVE", h.service.get(b.leaseId()).status(), "被冲突的释放不得终结租约");
        assertEquals(2, h.pool.leased(), "冲突路径不得改动整池占用");

        // 即使腾出池位，同一 requestId 再申请仍复现首次“整池已满”的结论
        h.service.release(a.leaseId(), new ReleaseRequest("px-rel-a"));
        assertEquals(1, h.pool.leased());
        LeaseServiceException dupApply = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("px-over", "team-p", 60)));
        log.warn("[test] reuse requestId=px-over for apply after slot freed code={} (first conclusion reproduced)",
                dupApply.code());
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, dupApply.code(), "再申请必须复现首次被拒结论");
        assertEquals(1, h.pool.leased(), "复现拒绝不得多占额度");
        assertBalanced(h, "team-p", "team-q");
    }

    @Test
    void rejectedSuccessfulAndFailedFirstConclusions_reusedAreDistinguishable() {
        Harness h = harness(4, Map.of("team-a", 1));

        // ---- 首次结论 A：团队额度被拒 ----
        h.service.apply(new LeaseRequest("dc-own", "team-a", 60));
        LeaseServiceException rejectedA = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("dc-rej", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, rejectedA.code());

        // ---- 首次结论 B：成功 ----
        LeaseView succ = h.service.apply(new LeaseRequest("dc-ok", "team-b", 60));
        // 另一个在用租约，作为跨租约复用目标
        LeaseView victim = h.service.apply(new LeaseRequest("dc-victim", "team-c", 60));

        // ---- 首次结论 C：供应失败（已补偿） ----
        LeaseServiceException failedC = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("dc-fail", "fail:team-d", 60)));
        assertEquals(ErrorCode.PROVISION_FAILED, failedC.code());
        assertEquals(0, h.service.teamQuota("fail:team-d").used(), "失败回滚不占额度");
        int leasedAfterThree = h.pool.leased();
        assertEquals(3, leasedAfterThree);

        // 1) 被拒标识：再申请复现 429；续约/释放他租约 -> 409
        LeaseServiceException rejAgain = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("dc-rej", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, rejAgain.code(),
                "被拒标识再申请必须复现首次拒绝码 429，与 503/409 可区分");
        assertConflictNotInternal(assertThrows(LeaseServiceException.class,
                () -> h.service.renew(victim.leaseId(), new RenewRequest("dc-rej", 30))));
        assertConflictNotInternal(assertThrows(LeaseServiceException.class,
                () -> h.service.release(victim.leaseId(), new ReleaseRequest("dc-rej"))));
        log.warn("[test] case=three-conclusions rejected id=dc-rej: apply->{} renew/release other->CONFLICT",
                rejAgain.code());

        // 2) 成功标识：续约/释放在“同一租约”上沿用申请标识是允许的既有约定；
        //    拿去操作另一个租约必须 409。释放用独立的操作标识（与续约各自幂等）。
        LeaseView renewed = h.service.renew(succ.leaseId(), new RenewRequest("dc-ok", 30));
        assertEquals("ACTIVE", renewed.status());
        assertConflictNotInternal(assertThrows(LeaseServiceException.class,
                () -> h.service.renew(victim.leaseId(), new RenewRequest("dc-ok", 30))));
        assertConflictNotInternal(assertThrows(LeaseServiceException.class,
                () -> h.service.release(victim.leaseId(), new ReleaseRequest("dc-ok"))));
        LeaseView released = h.service.release(succ.leaseId(), new ReleaseRequest("dc-ok-rel"));
        assertEquals("RELEASED", released.status());
        log.info("[test] case=three-conclusions success id=dc-ok: same-lease renew allowed, cross-lease reuse CONFLICT");

        // 3) 失败标识：再申请复现 502；续约/释放他租约 -> 409
        LeaseServiceException failAgain = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("dc-fail", "fail:team-d", 60)));
        assertEquals(ErrorCode.PROVISION_FAILED, failAgain.code(),
                "失败标识再申请必须复现 502，与 429/409 可区分");
        assertConflictNotInternal(assertThrows(LeaseServiceException.class,
                () -> h.service.renew(victim.leaseId(), new RenewRequest("dc-fail", 30))));
        assertConflictNotInternal(assertThrows(LeaseServiceException.class,
                () -> h.service.release(victim.leaseId(), new ReleaseRequest("dc-fail"))));
        log.warn("[test] case=three-conclusions failed id=dc-fail: apply->{} renew/release other->CONFLICT",
                failAgain.code());

        // 全部复用折腾完：只有真正成功又被同源释放的那份额度发生变化，其余不多占、不少还
        assertEquals(leasedAfterThree - 1, h.pool.leased());
        assertEquals("ACTIVE", h.service.get(victim.leaseId()).status(), "受害租约始终未受影响");
        assertBalanced(h, "team-a", "team-b", "team-c", "fail:team-d");
    }

    @Test
    void operationRequestId_reusedForApply_conflictsAndRejectedIdStaysStableOnTerminalLease() {
        Harness h = harness(3, Map.of("team-a", 1));

        LeaseView a = h.service.apply(new LeaseRequest("op-apply", "team-a", 60));
        LeaseView b = h.service.apply(new LeaseRequest("op-apply-b", "team-b", 60));

        // 续约/释放使用的 requestId 再拿去申请，必须冲突，不能当新申请放行
        h.service.renew(a.leaseId(), new RenewRequest("op-renew", 30));
        h.service.release(b.leaseId(), new ReleaseRequest("op-release"));
        LeaseServiceException renewIdApply = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("op-renew", "team-b", 60)));
        assertConflictNotInternal(renewIdApply);
        LeaseServiceException releaseIdApply = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("op-release", "team-b", 60)));
        assertConflictNotInternal(releaseIdApply);
        log.warn("[test] case=op-id-back-to-apply requestIds=op-renew,op-release code={} (apply blocked)",
                renewIdApply.code());

        // 团队额度被拒的 requestId，反复用于续约/释放任意（含已终结）租约，结论始终是 409
        LeaseServiceException rejected = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("op-rej", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, rejected.code());
        h.service.release(a.leaseId(), new ReleaseRequest("op-release-a"));
        for (int i = 0; i < 2; i++) {
            LeaseServiceException ex1 = assertThrows(LeaseServiceException.class,
                    () -> h.service.renew(b.leaseId(), new RenewRequest("op-rej", 30)));
            LeaseServiceException ex2 = assertThrows(LeaseServiceException.class,
                    () -> h.service.release(b.leaseId(), new ReleaseRequest("op-rej")));
            LeaseServiceException ex3 = assertThrows(LeaseServiceException.class,
                    () -> h.service.release(a.leaseId(), new ReleaseRequest("op-rej")));
            assertConflictNotInternal(ex1);
            assertConflictNotInternal(ex2);
            assertConflictNotInternal(ex3);
        }
        log.warn("[test] case=rejected-id-stable requestId=op-rej team=team-a repeated renew/release (incl. terminal) -> CONFLICT");

        // 额度与对账：a、b 均被合法释放，冲突路径不多占不少还
        assertEquals(0, h.pool.leased());
        assertEquals(0, h.service.teamQuota("team-a").used());
        assertEquals(0, h.service.teamQuota("team-b").used());
        assertBalanced(h, "team-a", "team-b");
    }
}
