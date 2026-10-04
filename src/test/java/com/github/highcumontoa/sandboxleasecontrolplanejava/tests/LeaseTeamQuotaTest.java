package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseService;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseServiceException;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.ReleaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamLeasesView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamQuotaView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.pool.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision.LocalSandboxProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.quota.TeamQuotaManager;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.store.LeaseStore;
import com.github.highcumontoa.sandboxleasecontrolplanejava.tests.LeaseServiceLifecycleTest.MutableClock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 团队额度专项测试：
 * 准入与拒绝原因区分、跨团队并发抢占、被拒请求重复提交一致性、
 * 额度归还（主动释放/到期回收/惰性到期/失败补偿）、在线调整、团队视图与整池一致性。
 *
 * <p>日志统一打印用例名、requestId、tenantId、团队额度与整池占用变化及状态迁移。
 */
class LeaseTeamQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseTeamQuotaTest.class);

    private record Harness(LeaseService service, ResourcePool pool, TeamQuotaManager teams,
                           MutableClock clock) {
    }

    private Harness harness(int capacity) {
        LeaseProperties props = new LeaseProperties();
        props.setPoolCapacity(capacity);
        props.setDefaultTtlSeconds(60);
        props.setMaxTtlSeconds(3600);
        MutableClock clock = new MutableClock();
        ResourcePool pool = new ResourcePool(props);
        TeamQuotaManager teams = new TeamQuotaManager(props);
        LeaseService service = new LeaseService(new LeaseStore(), pool, teams,
                new LocalSandboxProvisioner(), clock, props);
        return new Harness(service, pool, teams, clock);
    }

    /** 核心不变量：所有团队占用之和恒等于整池 leased。 */
    private static void assertTeamPoolBalanced(Harness h) {
        assertEquals(h.pool.leased(), h.teams.totalUsed(),
                "任意时刻所有团队占用之和必须等于整池占用");
    }

    @BeforeEach
    void banner(TestInfo info) {
        String method = info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("unknown");
        log.info("========== [test-case] {} | {} ==========", new Object[]{method,
                info.getDisplayName()});
    }

    @Test
    @DisplayName("团队额度准入：到上限被 TEAM_QUOTA_EXHAUSTED 拒绝，释放后恢复")
    void teamQuotaAdmission_rejectsAtLimitAndRecoversAfterRelease() {
        Harness h = harness(4);
        h.teams.configure("team-a", 2);

        h.service.apply(new LeaseRequest("ta-1", "team-a", 60));
        h.service.apply(new LeaseRequest("ta-2", "team-a", 60));
        log.info("[test] tenantId=team-a filled to limit, teamLeased=2/2, poolLeased={}/4",
                h.pool.leased());

        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("ta-3", "team-a", 60)));
        log.warn("[test] requestId=ta-3 tenantId=team-a rejected code={}, message={}",
                ex.code(), ex.getMessage());
        assertEquals(ErrorCode.TEAM_QUOTA_EXHAUSTED, ex.code());
        assertEquals(2, h.teams.used("team-a"));
        assertEquals(2, h.pool.leased(), "池仍有余量，证明拒绝来自团队额度而非整池");
        assertTeamPoolBalanced(h);

        TeamQuotaView q = h.service.teamQuota("team-a");
        log.info("[test] team quota view tenantId=team-a limit={}, leased={}, remaining={}, pool={}/{}/{}",
                q.limit(), q.leased(), q.remaining(), q.poolLeased(), q.poolCapacity(), q.poolAvailable());
        assertEquals(2, q.limit());
        assertEquals(2, q.leased());
        assertEquals(0, q.remaining());
        assertEquals(h.pool.capacity(), q.poolCapacity());
        assertEquals(h.pool.leased(), q.poolLeased());

        // 未配置团队此刻仍可申请（池有 2 个余量）
        LeaseView other = h.service.apply(new LeaseRequest("tb-1", "team-b", 60));
        assertEquals("ACTIVE", other.status());
        log.info("[test] requestId=tb-1 tenantId=team-b (unconfigured) acquired, poolLeased={}/4",
                h.pool.leased());

        // 释放一个团队 A 的占用后，新 requestId 可以成功
        h.service.release(h.service.teamLeases("team-a").leases().get(0).leaseId(),
                new ReleaseRequest("ta-rel-1"));
        assertEquals(1, h.teams.used("team-a"));
        LeaseView retry = h.service.apply(new LeaseRequest("ta-retry", "team-a", 60));
        assertEquals("ACTIVE", retry.status());
        assertEquals(2, h.teams.used("team-a"));
        assertTeamPoolBalanced(h);
    }

    @Test
    @DisplayName("拒绝原因区分：团队满 TEAM_QUOTA_EXHAUSTED / 整池满 QUOTA_EXHAUSTED / 参数 INVALID_REQUEST")
    void rejectionReasons_areDistinguishable() {
        Harness h = harness(2);
        h.teams.configure("team-a", 1);
        h.teams.configure("team-big", 5); // 上限高于整池，实际吞吐仍被整池卡住

        h.service.apply(new LeaseRequest("a-1", "team-a", 60));
        LeaseServiceException teamFull = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("a-2", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXHAUSTED, teamFull.code());
        log.warn("[test] requestId=a-2 tenantId=team-a -> {} (pool still has room)", teamFull.code());

        // 用未受限的团队把剩余池容量打满
        h.service.apply(new LeaseRequest("c-1", "team-c", 60));
        assertEquals(2, h.pool.leased());
        // team-big 自身远未到团队上限，但整池已满 -> QUOTA_EXHAUSTED
        LeaseServiceException poolFull = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("big-1", "team-big", 60)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, poolFull.code());
        log.warn("[test] requestId=big-1 tenantId=team-big -> {} (pool full, team limit=5)", poolFull.code());

        LeaseServiceException invalid = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("bad-1", "team-a", -1)));
        assertEquals(ErrorCode.INVALID_REQUEST, invalid.code());
        log.warn("[test] requestId=bad-1 -> {} (bad ttl)", invalid.code());

        // 三类错误码两两可区分，且对应不同 HTTP 状态
        assertFalse(teamFull.code() == poolFull.code());
        assertEquals(503, ErrorCode.TEAM_QUOTA_EXHAUSTED.httpStatus());
        assertEquals(503, ErrorCode.QUOTA_EXHAUSTED.httpStatus());
        assertEquals(400, ErrorCode.INVALID_REQUEST.httpStatus());
        assertTeamPoolBalanced(h);
    }

    @Test
    @DisplayName("未配置团队：仅受整池约束，limit/remaining 为 null")
    void unconfiguredTeam_isOnlyBoundedByPool() {
        Harness h = harness(2);
        h.service.apply(new LeaseRequest("x-1", "team-x", 60));
        h.service.apply(new LeaseRequest("x-2", "team-x", 60));

        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("x-3", "team-x", 60)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, ex.code(), "未配置团队只能因整池满被拒");
        log.warn("[test] requestId=x-3 tenantId=team-x (unconfigured) rejected code={}", ex.code());

        TeamQuotaView q = h.service.teamQuota("team-x");
        assertNull(q.limit());
        assertNull(q.remaining());
        assertEquals(2, q.leased());
        log.info("[test] unconfigured view tenantId=team-x limit={}, leased={}, remaining={}",
                q.limit(), q.leased(), q.remaining());
        assertTeamPoolBalanced(h);
    }

    @Test
    @DisplayName("单团队并发抢占：成功数严格等于团队上限与整池容量的较小者")
    void concurrentContentionWithinTeam_neverBreaksLimits() throws Exception {
        Harness h = harness(5);
        h.teams.configure("team-a", 3);
        int threads = 20;
        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> rejects = new ConcurrentHashMap<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    start.await();
                    h.service.apply(new LeaseRequest("ca-" + idx, "team-a", 60));
                    success.incrementAndGet();
                } catch (LeaseServiceException e) {
                    rejects.computeIfAbsent(e.code(), k -> new AtomicInteger()).incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        exec.shutdown();

        log.info("[test] tenantId=team-a concurrent threads={} success={} rejects={} teamLeased=3/3 poolLeased={}/5",
                threads, success.get(), rejects, h.pool.leased());
        assertEquals(3, success.get());
        assertEquals(threads - 3, rejects.getOrDefault(ErrorCode.TEAM_QUOTA_EXHAUSTED, new AtomicInteger()).get());
        assertFalse(rejects.containsKey(ErrorCode.QUOTA_EXHAUSTED), "池从未打满，不应出现整池拒绝");
        assertEquals(3, h.teams.used("team-a"));
        assertEquals(3, h.pool.leased());
        assertTeamPoolBalanced(h);
    }

    @Test
    @DisplayName("跨团队并发抢占：既不破团队上限也不破整池上限")
    void crossTeamConcurrentContention_respectsBothLimits() throws Exception {
        Harness h = harness(6);
        h.teams.configure("team-a", 2);
        h.teams.configure("team-b", 2);
        // team-c 不配置额度

        // 预置：team-a/team-b 各占满 2，整池 6 只剩 2
        h.service.apply(new LeaseRequest("seed-a1", "team-a", 60));
        h.service.apply(new LeaseRequest("seed-a2", "team-a", 60));
        h.service.apply(new LeaseRequest("seed-b1", "team-b", 60));
        h.service.apply(new LeaseRequest("seed-b2", "team-b", 60));

        int perTeam = 10;
        ExecutorService exec = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(perTeam * 3);
        Map<String, AtomicInteger> success = new ConcurrentHashMap<>();
        Map<String, Map<ErrorCode, AtomicInteger>> rejects = new ConcurrentHashMap<>();

        for (int i = 0; i < perTeam; i++) {
            submitContender(exec, start, done, h, "team-a", "race-a-" + i, success, rejects);
            submitContender(exec, start, done, h, "team-b", "race-b-" + i, success, rejects);
            submitContender(exec, start, done, h, "team-c", "race-c-" + i, success, rejects);
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        exec.shutdown();

        log.info("[test] cross-team result success={} rejects={} poolLeased={}/6 teamA=2 teamB=2 teamC=2",
                success, rejects, h.pool.leased());
        assertEquals(0, success.getOrDefault("team-a", new AtomicInteger()).get());
        assertEquals(0, success.getOrDefault("team-b", new AtomicInteger()).get());
        assertEquals(2, success.getOrDefault("team-c", new AtomicInteger()).get(),
                "整池仅剩 2 槽，全部被未受限的 team-c 抢到");
        assertEquals(perTeam, rejects.get("team-a").get(ErrorCode.TEAM_QUOTA_EXHAUSTED).get());
        assertEquals(perTeam, rejects.get("team-b").get(ErrorCode.TEAM_QUOTA_EXHAUSTED).get());
        assertEquals(perTeam - 2, rejects.get("team-c").get(ErrorCode.QUOTA_EXHAUSTED).get());
        assertEquals(6, h.pool.leased());
        assertEquals(2, h.teams.used("team-a"));
        assertEquals(2, h.teams.used("team-b"));
        assertEquals(2, h.teams.used("team-c"));
        assertTeamPoolBalanced(h);
    }

    private static void submitContender(ExecutorService exec, CountDownLatch start, CountDownLatch done,
                                        Harness h, String tenant, String requestId,
                                        Map<String, AtomicInteger> success,
                                        Map<String, Map<ErrorCode, AtomicInteger>> rejects) {
        exec.submit(() -> {
            try {
                start.await();
                h.service.apply(new LeaseRequest(requestId, tenant, 60));
                success.computeIfAbsent(tenant, k -> new AtomicInteger()).incrementAndGet();
            } catch (LeaseServiceException e) {
                rejects.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(e.code(), k -> new AtomicInteger()).incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
    }

    @Test
    @DisplayName("被拒请求重复提交：额度空出后同一 requestId 仍复现首次拒绝结论")
    void rejectedResubmission_staysRejectedEvenWhenQuotaFrees() {
        Harness h = harness(2);
        h.teams.configure("team-a", 1);
        h.service.apply(new LeaseRequest("hold-a", "team-a", 60));

        // 首次：团队额度满被拒
        LeaseServiceException first = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("deny-team", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXHAUSTED, first.code());

        // 释放占用，团队额度重新可用；同一 requestId 必须仍被拒，不能悄悄变成功
        String holderId = h.service.teamLeases("team-a").leases().get(0).leaseId();
        h.service.release(holderId, new ReleaseRequest("hold-rel"));
        assertEquals(0, h.teams.used("team-a"));
        LeaseServiceException again = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("deny-team", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXHAUSTED, again.code());
        assertEquals(first.getMessage(), again.getMessage());
        assertEquals(0, h.teams.used("team-a"), "被拒复现不得产生占用");
        log.warn("[test] requestId=deny-team tenantId=team-a reproducible rejection code={}, teamLeased=0",
                again.code());

        // 新 requestId 在同一份空出的额度上可以成功
        assertEquals("ACTIVE",
                h.service.apply(new LeaseRequest("new-after-free", "team-a", 60)).status());

        // 整池满拒绝同理：打满后拒绝、释放后重复提交仍拒绝
        h.teams.configure("team-z", null);
        h.service.apply(new LeaseRequest("fill-1", "team-z", 60));
        // pool capacity=2，team-a 已占 1，team-z 再占 1 即满
        LeaseServiceException poolFirst = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("deny-pool", "team-z", 60)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, poolFirst.code());
        h.service.release(h.service.teamLeases("team-z").leases().get(0).leaseId(),
                new ReleaseRequest("z-rel"));
        LeaseServiceException poolAgain = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("deny-pool", "team-z", 60)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, poolAgain.code());
        log.warn("[test] requestId=deny-pool tenantId=team-z reproducible rejection code={}",
                poolAgain.code());
        assertTeamPoolBalanced(h);
    }

    @Test
    @DisplayName("额度归还：主动释放、定时回收、惰性到期查询、失败补偿各只还一次")
    void quotaIsReturnedOnce_releaseReapLazyExpiryCompensation() {
        Harness h = harness(4);
        h.teams.configure("team-a", 2);
        h.teams.configure("team-exp", 2);
        h.teams.configure("fail:broken", 2);

        // (1) 主动释放
        LeaseView a = h.service.apply(new LeaseRequest("ret-a", "team-a", 60));
        h.service.release(a.leaseId(), new ReleaseRequest("ret-a-rel"));
        assertEquals(0, h.teams.used("team-a"));
        assertEquals(0, h.pool.leased());
        assertTeamPoolBalanced(h);
        log.info("[test] release return: tenantId=team-a teamLeased=0, poolLeased=0");

        // (2) 定时回收
        LeaseView e = h.service.apply(new LeaseRequest("ret-e", "team-exp", 5));
        h.clock.advanceMillis(5_001);
        int reaped = h.service.reapExpired();
        assertEquals(1, reaped);
        assertEquals(0, h.teams.used("team-exp"));
        assertEquals(0, h.pool.leased());
        assertTeamPoolBalanced(h);
        log.info("[test] auto-reap return: tenantId=team-exp reaped=1, teamLeased=0, poolLeased=0");

        // (3) 惰性到期：通过团队额度查询触发，占用同样被归还
        LeaseView l = h.service.apply(new LeaseRequest("ret-l", "team-exp", 5));
        h.clock.advanceMillis(5_001);
        TeamQuotaView q = h.service.teamQuota("team-exp");
        assertEquals(0, q.leased(), "查询触发惰性到期后团队占用应为 0");
        assertEquals(0, h.pool.leased());
        TeamLeasesView list = h.service.teamLeases("team-exp");
        assertEquals(0, list.leases().size());
        assertEquals("EXPIRED", h.service.get(l.leaseId()).status());
        assertTeamPoolBalanced(h);
        log.info("[test] lazy-expiry return: tenantId=team-exp via teamQuota, teamLeased=0, poolLeased=0");

        // (4) provision 失败补偿：团队与整池额度都一次性归还；重复提交结论一致
        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("ret-fail", "fail:broken", 60)));
        assertEquals(ErrorCode.PROVISION_FAILED, ex.code());
        assertEquals(0, h.teams.used("fail:broken"));
        assertEquals(0, h.pool.leased());
        LeaseServiceException again = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("ret-fail", "fail:broken", 60)));
        assertEquals(ErrorCode.PROVISION_FAILED, again.code());
        assertEquals(0, h.teams.used("fail:broken"), "失败请求重复提交不得重复占用/归还");
        assertTeamPoolBalanced(h);
        log.info("[test] compensation return: tenantId=fail:broken code=PROVISION_FAILED, "
                + "teamLeased=0, poolLeased=0, resubmit reproducible");
    }

    @Test
    @DisplayName("在线调整：立即影响后续申请，低于占用时不强拆，移除后回归整池共享")
    void onlineAdjustment_effectsLaterAppliesAndNeverEvicts() {
        Harness h = harness(8);
        h.teams.configure("team-a", 3);
        for (int i = 0; i < 3; i++) {
            h.service.apply(new LeaseRequest("adj-" + i, "team-a", 60));
        }

        // 下调到低于当前占用：3 个租约全部存活，后续申请继续被拒
        TeamQuotaView lowered = h.service.configureTeamQuota("team-a", 1);
        assertEquals(1, lowered.limit());
        assertEquals(3, lowered.leased());
        assertEquals(0, lowered.remaining());
        TeamLeasesView alive = h.service.teamLeases("team-a");
        assertEquals(3, alive.leases().size(), "已开出租约不得被强拆");
        alive.leases().forEach(v -> assertEquals("ACTIVE", v.status()));
        LeaseServiceException denied = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("adj-low-deny", "team-a", 60)));
        assertEquals(ErrorCode.TEAM_QUOTA_EXHAUSTED, denied.code());
        log.warn("[test] lower limit tenantId=team-a limit=1 leased=3 -> new apply code={}", denied.code());

        // 调到 0：继续拒绝，存量不动
        h.service.configureTeamQuota("team-a", 0);
        assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("adj-zero-deny", "team-a", 60)));
        assertEquals(3, h.service.teamLeases("team-a").leases().size());

        // 上调到 4：第 4 个立即可以申请
        h.service.configureTeamQuota("team-a", 4);
        assertEquals("ACTIVE",
                h.service.apply(new LeaseRequest("adj-4th", "team-a", 60)).status());
        assertEquals(4, h.teams.used("team-a"));
        log.info("[test] raise limit tenantId=team-a limit=4 -> 4th apply ACTIVE, teamLeased=4");

        // 非法调整
        LeaseServiceException bad = assertThrows(LeaseServiceException.class,
                () -> h.service.configureTeamQuota("team-a", -1));
        assertEquals(ErrorCode.INVALID_REQUEST, bad.code());

        // 移除配置：只受整池约束
        TeamQuotaView removed = h.service.configureTeamQuota("team-a", null);
        assertNull(removed.limit());
        assertNull(removed.remaining());
        assertEquals(4, removed.leased(), "存量占用保持不变");
        assertEquals("ACTIVE",
                h.service.apply(new LeaseRequest("adj-free-1", "team-a", 60)).status());
        log.info("[test] remove limit tenantId=team-a -> pool-only behavior, teamLeased=5, poolLeased=5/8");
        assertEquals(5, h.teams.used("team-a"));
        assertTeamPoolBalanced(h);
    }

    @Test
    @DisplayName("团队视图与整池配额口径一致：leased 之和、清单数、pool 快照全部对齐")
    void teamViews_stayConsistentWithPoolSnapshot() {
        Harness h = harness(6);
        h.teams.configure("team-a", 4);
        h.teams.configure("team-b", 2);
        for (int i = 0; i < 3; i++) {
            h.service.apply(new LeaseRequest("mix-a-" + i, "team-a", 60));
        }
        h.service.apply(new LeaseRequest("mix-b-0", "team-b", 60));
        h.service.apply(new LeaseRequest("mix-c-0", "team-c", 60)); // 未配置

        int sum = h.teams.used("team-a") + h.teams.used("team-b") + h.teams.used("team-c");
        assertEquals(h.pool.leased(), sum);
        assertEquals(5, sum);
        assertTeamPoolBalanced(h);

        for (String t : new String[]{"team-a", "team-b", "team-c"}) {
            TeamQuotaView q = h.service.teamQuota(t);
            TeamLeasesView l = h.service.teamLeases(t);
            assertEquals(q.leased(), l.leases().size(), "团队清单数必须等于团队占用");
            assertEquals(l.leased(), l.leases().size());
            assertEquals(h.pool.capacity(), q.poolCapacity());
            assertEquals(h.pool.leased(), q.poolLeased(), "团队视图中的整池快照必须与 quota 接口一致");
            assertEquals(h.pool.available(), q.poolAvailable());
            log.info("[test] consistency tenantId={} limit={} leased={} listed={} pool={}/{} available={}",
                    t, q.limit(), q.leased(), l.leases().size(), q.poolLeased(),
                    q.poolCapacity(), q.poolAvailable());
        }
        assertTeamPoolBalanced(h);
    }
}
