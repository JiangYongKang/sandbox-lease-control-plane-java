package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseService;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseServiceException;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.QuotaView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.ReleaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamQuotaUpdateRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamQuotaView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.pool.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision.LocalSandboxProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.quota.TeamQuotaRegistry;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.store.LeaseStore;
import com.github.highcumontoa.sandboxleasecontrolplanejava.tests.LeaseServiceLifecycleTest.MutableClock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 团队额度测试：准入与拒绝原因区分、跨团队并发抢占、被拒请求重复提交一致性、
 * 额度归还（释放/到期回收/失败回滚）、在线调整额度、团队视图与整池配额对账。
 * 日志统一打印 requestId、teamId、团队额度与整池占用变化，便于复现排查。
 */
class LeaseServiceTeamQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseServiceTeamQuotaTest.class);

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

    @Test
    void teamQuotaAdmission_rejectionReasonIsDistinguishable() {
        Harness h = harness(3, Map.of("team-a", 1));

        LeaseView a1 = h.service.apply(new LeaseRequest("tq-a1", "team-a", 60));
        log.info("[test] requestId=tq-a1 team=team-a acquired leaseId={}, teamQuota={}, pool={}",
                a1.leaseId(), h.service.teamQuota("team-a"), h.service.quota().leased());
        assertEquals("ACTIVE", a1.status());

        // 团队额度满 -> TEAM_QUOTA_EXCEEDED，与整池已满/参数非法可区分
        LeaseServiceException teamFull = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("tq-a2", "team-a", 60)));
        log.warn("[test] requestId=tq-a2 team=team-a rejected code={}", teamFull.code());
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, teamFull.code());

        // 未配置额度的 team-b 不受团队上限约束，直到整池打满
        h.service.apply(new LeaseRequest("tq-b1", "team-b", 60));
        h.service.apply(new LeaseRequest("tq-b2", "team-b", 60));
        LeaseServiceException poolFull = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("tq-b3", "team-b", 60)));
        log.warn("[test] requestId=tq-b3 team=team-b rejected code={}", poolFull.code());
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, poolFull.code());

        // 参数非法仍是独立的错误码
        LeaseServiceException invalid = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("", "team-b", 60)));
        assertEquals(ErrorCode.INVALID_REQUEST, invalid.code());

        TeamQuotaView teamA = h.service.teamQuota("team-a");
        assertEquals(1, teamA.limit());
        assertEquals(1, teamA.used());
        assertEquals(0, teamA.remaining());
        assertBalanced(h, "team-a", "team-b");
    }

    @Test
    void unconfiguredTeam_keepsSharedPoolBehavior() {
        Harness h = harness(2, Map.of());

        h.service.apply(new LeaseRequest("uq-1", "team-x", 60));
        h.service.apply(new LeaseRequest("uq-2", "team-x", 60));
        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("uq-3", "team-y", 60)));
        log.warn("[test] requestId=uq-3 team=team-y rejected code={} (unconfigured teams share pool)",
                ex.code());
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, ex.code(), "未配置团队只受整池约束");

        TeamQuotaView view = h.service.teamQuota("team-x");
        log.info("[test] team=team-x quota view limit={} used={} remaining={}",
                view.limit(), view.used(), view.remaining());
        assertNull(view.limit(), "未配置团队上限为 null");
        assertNull(view.remaining(), "未配置团队剩余为 null");
        assertEquals(2, view.used());
        assertBalanced(h, "team-x", "team-y");
    }

    @Test
    void crossTeamConcurrentContention_neverExceedsTeamOrPoolLimits() throws Exception {
        int capacity = 6;
        Harness h = harness(capacity, Map.of("team-a", 2, "team-b", 2));
        String[] teams = {"team-a", "team-b", "team-c"}; // team-c 未配置上限
        int perTeam = 10;
        int threads = perTeam * teams.length;

        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Map<String, AtomicInteger> successByTeam = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> rejectedByTeam = new ConcurrentHashMap<>();
        Map<ErrorCode, AtomicInteger> rejections = new ConcurrentHashMap<>();
        Set<Integer> observedPoolLeased = ConcurrentHashMap.newKeySet();

        for (String team : teams) {
            for (int i = 0; i < perTeam; i++) {
                final String requestId = "cc-" + team + "-" + i;
                final String teamId = team;
                executor.submit(() -> {
                    try {
                        start.await();
                        h.service.apply(new LeaseRequest(requestId, teamId, 60));
                        successByTeam.computeIfAbsent(teamId, k -> new AtomicInteger()).incrementAndGet();
                    } catch (LeaseServiceException ex) {
                        rejectedByTeam.computeIfAbsent(teamId, k -> new AtomicInteger()).incrementAndGet();
                        rejections.computeIfAbsent(ex.code(), k -> new AtomicInteger()).incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        observedPoolLeased.add(h.pool.leased());
                        done.countDown();
                    }
                });
            }
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        int succA = successByTeam.getOrDefault("team-a", new AtomicInteger()).get();
        int succB = successByTeam.getOrDefault("team-b", new AtomicInteger()).get();
        int succC = successByTeam.getOrDefault("team-c", new AtomicInteger()).get();
        log.info("[test] cross-team contention success a={} b={} c={} rejections={} poolLeased={}/{}",
                succA, succB, succC, rejections, h.pool.leased(), capacity);

        assertEquals(2, succA, "team-a 不得突破自己的上限");
        assertEquals(2, succB, "team-b 不得突破自己的上限");
        assertEquals(2, succC, "team-c 只能拿到整池剩余部分");
        assertEquals(capacity, h.pool.leased(), "整池不得超卖");
        for (int leased : observedPoolLeased) {
            assertTrue(leased <= capacity, "任何时刻整池占用都不得超容量: " + leased);
        }
        assertEquals(perTeam - 2, rejectedByTeam.get("team-a").get());
        assertEquals(perTeam - 2, rejectedByTeam.get("team-b").get());
        assertEquals(perTeam - 2, rejectedByTeam.get("team-c").get());
        assertBalanced(h, teams);
    }

    @Test
    void rejectedRequest_duplicateSubmitReturnsSameConclusion() {
        Harness h = harness(2, Map.of("team-a", 1));

        LeaseView a1 = h.service.apply(new LeaseRequest("rj-1", "team-a", 60));
        LeaseServiceException first = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("rj-2", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, first.code());

        // 释放后团队额度空出来了，但同一 requestId 重复提交必须复现首次拒绝结论
        h.service.release(a1.leaseId(), new ReleaseRequest("rj-rel-1"));
        assertEquals(0, h.service.teamQuota("team-a").used());
        LeaseServiceException dup = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("rj-2", "team-a", 60)));
        log.warn("[test] requestId=rj-2 team=team-a duplicate after quota freed, code={} (must stay rejected)",
                dup.code());
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, dup.code());
        assertEquals(0, h.service.teamQuota("team-a").used(), "重复提交不得占用额度");

        // 新的 requestId 则可以正常申请
        LeaseView a3 = h.service.apply(new LeaseRequest("rj-3", "team-a", 60));
        assertEquals("ACTIVE", a3.status());

        // 整池已满的拒绝同样幂等：填满池子 -> 拒绝 -> 释放 -> 重复提交仍拒绝
        h.service.apply(new LeaseRequest("rj-b1", "team-b", 60)); // 池满 2/2
        LeaseServiceException poolFirst = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("rj-b2", "team-b", 60)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, poolFirst.code());
        h.service.release(a3.leaseId(), new ReleaseRequest("rj-rel-3"));
        LeaseServiceException poolDup = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("rj-b2", "team-b", 60)));
        log.warn("[test] requestId=rj-b2 team=team-b duplicate after pool freed, code={}", poolDup.code());
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, poolDup.code());
        assertBalanced(h, "team-a", "team-b");
    }

    @Test
    void teamQuotaReturned_onReleaseExpiryAndProvisionFailure() {
        Harness h = harness(3, Map.of("team-a", 2, "fail:team-b", 1));

        LeaseView a1 = h.service.apply(new LeaseRequest("rt-a1", "team-a", 10));
        LeaseView a2 = h.service.apply(new LeaseRequest("rt-a2", "team-a", 10));
        assertEquals(2, h.service.teamQuota("team-a").used());

        // 1) 主动释放归还团队额度
        h.service.release(a1.leaseId(), new ReleaseRequest("rt-rel-1"));
        log.info("[test] requestId=rt-rel-1 released leaseId={}, team-a used={}, poolLeased={}",
                a1.leaseId(), h.service.teamQuota("team-a").used(), h.pool.leased());
        assertEquals(1, h.service.teamQuota("team-a").used());

        // 2) 到期自动回收归还团队额度
        h.clock.advanceMillis(10_001);
        int reaped = h.service.reapExpired();
        log.info("[test] auto-reap leaseId={} reaped={}, team-a used={}, poolLeased={}",
                a2.leaseId(), reaped, h.service.teamQuota("team-a").used(), h.pool.leased());
        assertEquals(1, reaped);
        assertEquals(0, h.service.teamQuota("team-a").used());
        assertEquals(0, h.pool.leased());

        // 3) 申请失败回滚归还团队额度（不重复计、不泄漏）
        LeaseServiceException fail = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("rt-f1", "fail:team-b", 60)));
        assertEquals(ErrorCode.PROVISION_FAILED, fail.code());
        log.warn("[test] requestId=rt-f1 team=fail:team-b provision failed, team used={}, poolLeased={}",
                h.service.teamQuota("fail:team-b").used(), h.pool.leased());
        assertEquals(0, h.service.teamQuota("fail:team-b").used(), "失败回滚后团队额度必须归还");
        assertEquals(0, h.pool.leased());

        // 回滚后额度确实可用：换一个可成功的团队占用同一池位
        h.service.apply(new LeaseRequest("rt-a3", "team-a", 60));
        assertEquals(1, h.service.teamQuota("team-a").used());
        assertBalanced(h, "team-a", "fail:team-b");
    }

    @Test
    void onlineAdjustment_effectiveImmediatelyAndNeverEvicts() {
        Harness h = harness(4, Map.of("team-a", 2));

        h.service.apply(new LeaseRequest("oa-1", "team-a", 60));
        LeaseView a2 = h.service.apply(new LeaseRequest("oa-2", "team-a", 60));
        LeaseServiceException rejected = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("oa-3", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, rejected.code());

        // 调高上限 -> 后续申请立即放行（oa-3 已被拒并落幂等结论，换新 requestId）
        TeamQuotaView raised = h.service.updateTeamQuota("team-a", new TeamQuotaUpdateRequest(3));
        log.info("[test] team=team-a limit raised 2 -> 3, view={}", raised);
        assertEquals(3, raised.limit());
        LeaseView a3 = h.service.apply(new LeaseRequest("oa-4", "team-a", 60));
        assertEquals("ACTIVE", a3.status());

        // 调低到当前占用之下 -> 存量租约不被强拆，后续申请继续被拒
        TeamQuotaView lowered = h.service.updateTeamQuota("team-a", new TeamQuotaUpdateRequest(1));
        log.info("[test] team=team-a limit lowered 3 -> 1 (used=3), view={}", lowered);
        assertEquals(0, lowered.remaining(), "占用超过上限时剩余为 0 而非负数");
        assertEquals("ACTIVE", h.service.get(a2.leaseId()).status(), "存量租约不得被强拆");
        assertEquals("ACTIVE", h.service.get(a3.leaseId()).status(), "存量租约不得被强拆");
        LeaseServiceException stillRejected = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("oa-5", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, stillRejected.code());

        // 清除上限 -> 恢复整池共享约束
        TeamQuotaView cleared = h.service.updateTeamQuota("team-a", new TeamQuotaUpdateRequest(null));
        log.info("[test] team=team-a limit cleared, view={}", cleared);
        assertNull(cleared.limit());
        LeaseView a6 = h.service.apply(new LeaseRequest("oa-6", "team-a", 60));
        assertEquals("ACTIVE", a6.status());

        // 非法调整 -> INVALID_REQUEST，且不影响现有限额
        LeaseServiceException invalid = assertThrows(LeaseServiceException.class,
                () -> h.service.updateTeamQuota("team-a", new TeamQuotaUpdateRequest(-1)));
        assertEquals(ErrorCode.INVALID_REQUEST, invalid.code());
        assertBalanced(h, "team-a");
    }

    @Test
    void teamQuotaViewAndLeases_consistentWithPoolQuota() {
        Harness h = harness(4, Map.of("team-a", 2));

        LeaseView a1 = h.service.apply(new LeaseRequest("vw-a1", "team-a", 60));
        h.service.apply(new LeaseRequest("vw-b1", "team-b", 60));
        LeaseView b2 = h.service.apply(new LeaseRequest("vw-b2", "team-b", 60));

        TeamQuotaView teamA = h.service.teamQuota("team-a");
        TeamQuotaView teamB = h.service.teamQuota("team-b");
        QuotaView pool = h.service.quota();
        log.info("[test] views: team-a={} team-b={} pool={}/{}/{}",
                teamA, teamB, pool.capacity(), pool.leased(), pool.available());

        assertEquals(2, teamA.limit());
        assertEquals(1, teamA.used());
        assertEquals(1, teamA.remaining());
        assertNull(teamB.limit());
        assertEquals(2, teamB.used());
        assertNull(teamB.remaining());
        assertEquals(pool.leased(), teamA.used() + teamB.used(), "团队视图必须与整池配额对得上");

        List<LeaseView> leasesA = h.service.teamLeases("team-a");
        List<LeaseView> leasesB = h.service.teamLeases("team-b");
        log.info("[test] team leases: team-a={} team-b={}",
                leasesA.stream().map(LeaseView::leaseId).toList(),
                leasesB.stream().map(LeaseView::leaseId).toList());
        assertEquals(List.of(a1.leaseId()), leasesA.stream().map(LeaseView::leaseId).toList());
        assertEquals(2, leasesB.size());

        // 释放一个团队租约后，团队视图与整池视图同步变化
        h.service.release(b2.leaseId(), new ReleaseRequest("vw-rel-b2"));
        assertEquals(1, h.service.teamQuota("team-b").used());
        assertEquals(2, h.service.quota().leased());
        assertEquals(1, h.service.teamLeases("team-b").size(), "终态租约不再挂在团队名下");
        assertBalanced(h, "team-a", "team-b");
    }
}
