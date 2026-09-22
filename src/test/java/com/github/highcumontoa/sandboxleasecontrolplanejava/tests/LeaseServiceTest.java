package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import com.github.highcumontoa.sandboxleasecontrolplanejava.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.Lease;
import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.LeaseStatus;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.AcquireRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.LeaseResponse;
import com.github.highcumontoa.sandboxleasecontrolplanejava.error.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.error.LeaseException;
import com.github.highcumontoa.sandboxleasecontrolplanejava.provision.InMemoryProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.quota.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.repository.LeaseStore;
import com.github.highcumontoa.sandboxleasecontrolplanejava.service.LeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租约服务：生命周期、幂等、配额耗尽、并发抢占与申请失败补偿。
 * 纯手工装配（不启动 Spring 调度），时钟可控。
 */
class LeaseServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseServiceTest.class);

    private LeaseProperties properties;
    private LeaseStore store;
    private ResourcePool pool;
    private InMemoryProvisioner provisioner;
    private TestClock clock;
    private LeaseService service;

    @BeforeEach
    void setUp() {
        properties = new LeaseProperties();
        properties.setPoolCapacity(4);
        properties.setDefaultTtlSeconds(60);
        properties.setMaxTtlSeconds(600);
        store = new LeaseStore();
        pool = new ResourcePool(properties);
        provisioner = new InMemoryProvisioner();
        clock = new TestClock();
        service = new LeaseService(store, pool, provisioner, properties, clock);
    }

    private AcquireRequest acquireRequest(String requestId, boolean failInjected) {
        AcquireRequest req = new AcquireRequest();
        req.setRequestId(requestId);
        req.setEnvType("devbox");
        req.setOwner("alice");
        req.setTtlSeconds(60L);
        req.setSimulateProvisioningFailure(failInjected);
        return req;
    }

    @Test
    void acquireRenewReleaseLifecycleAndQuotaAccounting() {
        LeaseResponse acquired = service.acquire(acquireRequest("req-1", false));
        log.info("[lifecycle-test] requestId=req-1 lease={} status={} quota={}/{}",
                acquired.getLeaseId(), acquired.getStatus(),
                acquired.getQuotaOccupied(), acquired.getQuotaCapacity());
        assertEquals(LeaseStatus.ACTIVE, acquired.getStatus());
        assertEquals(1, pool.getOccupied());

        LeaseResponse renewed = service.renew(acquired.getLeaseId(), "renew-1", 120L);
        log.info("[lifecycle-test] requestId=renew-1 lease={} expiry={} v={}",
                renewed.getLeaseId(), renewed.getExpiresAt(), renewed.getVersion());
        assertTrue(renewed.getExpiresAt().isAfter(acquired.getExpiresAt()));
        assertEquals(1, pool.getOccupied(), "renew must not consume quota");

        LeaseResponse released = service.release(acquired.getLeaseId(), "release-1");
        log.info("[lifecycle-test] requestId=release-1 lease={} status={} releasedAt={} quota={}/{}",
                released.getLeaseId(), released.getStatus(), released.getReleasedAt(),
                released.getQuotaOccupied(), released.getQuotaCapacity());
        assertEquals(LeaseStatus.RELEASED, released.getStatus());
        assertEquals(0, pool.getOccupied());
        assertEquals(0, provisioner.activeHandleCount(), "underlying handle must be de-registered");
    }

    @Test
    void duplicateAcquireSameRequestIdReturnsSameResultWithoutDoubleOccupation() {
        LeaseResponse first = service.acquire(acquireRequest("dup-req", false));
        LeaseResponse second = service.acquire(acquireRequest("dup-req", false));
        log.info("[idempotency-test] requestId=dup-req first={} second={} replayFlag={}",
                first.getLeaseId(), second.getLeaseId(), second.isIdempotentReplay());
        assertEquals(first.getLeaseId(), second.getLeaseId());
        assertTrue(second.isIdempotentReplay());
        assertEquals(1, pool.getOccupied(), "duplicate submission must not occupy quota twice");
        assertEquals(1, provisioner.activeHandleCount());
    }

    @Test
    void sameRequestIdWithDifferentPayloadIsRejected() {
        service.acquire(acquireRequest("key-conflict", false));
        AcquireRequest different = acquireRequest("key-conflict", false);
        different.setOwner("bob");
        LeaseException ex = assertThrows(LeaseException.class,
                () -> service.acquire(different));
        log.info("[idempotency-test] requestId=key-conflict rejected reason={}", ex.getErrorCode());
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ex.getErrorCode());
        assertEquals(1, pool.getOccupied(), "conflicting request must not touch quota");
    }

    @Test
    void quotaExhaustionIsRejectedWithDistinctReasonAndReplayable() {
        for (int i = 1; i <= 4; i++) {
            service.acquire(acquireRequest("fill-" + i, false));
        }
        LeaseException ex = assertThrows(LeaseException.class,
                () -> service.acquire(acquireRequest("overflow", false)));
        log.info("[quota-test] requestId=overflow rejected reason={} occupied={}/{}",
                ex.getErrorCode(), pool.getOccupied(), pool.getCapacity());
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, ex.getErrorCode());
        assertEquals(4, pool.getOccupied());

        // 重复提交同一个失败请求：结论可复现，且依然不占配额
        LeaseException replay = assertThrows(LeaseException.class,
                () -> service.acquire(acquireRequest("overflow", false)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, replay.getErrorCode());
        assertEquals(4, pool.getOccupied());

        // 释放一个名额后，被拒请求再次以同一 requestId 提交仍应回放旧结论（不偷偷成功）
        List<Lease> leases = store.all().stream().filter(l -> l.getStatus() == LeaseStatus.ACTIVE).toList();
        service.release(leases.get(0).getLeaseId(), "free-one");
        LeaseException stillRejected = assertThrows(LeaseException.class,
                () -> service.acquire(acquireRequest("overflow", false)));
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, stillRejected.getErrorCode());
    }
    @Test
    void provisioningFailureRollsBackQuotaAndLeavesNoDanglingLease() {
        int before = pool.getOccupied();
        LeaseException ex = assertThrows(LeaseException.class,
                () -> service.acquire(acquireRequest("fail-req", true)));
        log.info("[compensation-test] requestId=fail-req rejected reason={} message={}",
                ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.PROVISIONING_FAILED, ex.getErrorCode());
        assertEquals(before, pool.getOccupied(), "quota must be refunded after provision failure");
        assertEquals(0, provisioner.activeHandleCount(), "no underlying handle may leak");

        // 仓储中保留的是带补偿标记的终态记录，而不是悬挂的 ACTIVE 租约
        Lease leftover = store.all().iterator().next();
        assertEquals(LeaseStatus.EXPIRED, leftover.getStatus());
        assertTrue(leftover.isCompensated());
        log.info("[compensation-test] compensated lease={} status={} compensated={}",
                leftover.getLeaseId(), leftover.getStatus(), leftover.isCompensated());

        // 同一失败请求重复提交：相同错误码，且补偿不会重复执行（余额不变）
        LeaseException replay = assertThrows(LeaseException.class,
                () -> service.acquire(acquireRequest("fail-req", true)));
        assertEquals(ErrorCode.PROVISIONING_FAILED, replay.getErrorCode());
        assertEquals(before, pool.getOccupied());

        // 补偿后配额可被后续正常申请复用
        LeaseResponse later = service.acquire(acquireRequest("after-fail", false));
        assertEquals(LeaseStatus.ACTIVE, later.getStatus());
        assertEquals(1, pool.getOccupied());
    }

    @Test
    void concurrentAcquireNeverOvercommits() throws Exception {
        int threads = 32;
        ExecutorService exec = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            String requestId = "concurrent-" + i;
            futures.add(exec.submit(() -> {
                try {
                    start.await();
                    try {
                        LeaseResponse r = service.acquire(acquireRequest(requestId, false));
                        success.incrementAndGet();
                        log.info("[concurrent-test] requestId={} granted lease={} quota={}/{}",
                                requestId, r.getLeaseId(), r.getQuotaOccupied(), r.getQuotaCapacity());
                    } catch (LeaseException ex) {
                        rejected.incrementAndGet();
                        assertEquals(ErrorCode.QUOTA_EXHAUSTED, ex.getErrorCode());
                        log.info("[concurrent-test] requestId={} rejected reason={}",
                                requestId, ex.getErrorCode());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        exec.shutdown();

        log.info("[concurrent-test] settled success={} rejected={} occupied={} handles={}",
                success.get(), rejected.get(), pool.getOccupied(), provisioner.activeHandleCount());
        assertEquals(4, success.get());
        assertEquals(threads - 4, rejected.get());
        assertEquals(4, pool.getOccupied());
        assertEquals(4, provisioner.activeHandleCount());
        assertEquals(4, store.all().stream().filter(l -> l.getStatus() == LeaseStatus.ACTIVE).count());
    }

    @Test
    void duplicateSubmitUnderContentionResolvesToSingleOutcome() throws Exception {
        int threads = 20;
        ExecutorService exec = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LeaseResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                start.await();
                return service.acquire(acquireRequest("hot-key", false));
            }));
        }
        start.countDown();
        String leaseId = null;
        for (Future<LeaseResponse> f : futures) {
            LeaseResponse r = f.get(10, TimeUnit.SECONDS);
            if (leaseId == null) {
                leaseId = r.getLeaseId();
            } else {
                assertEquals(leaseId, r.getLeaseId());
            }
        }
        exec.shutdown();
        log.info("[idempotency-test] hot-key resolved to lease={} occupied={} handles={}",
                leaseId, pool.getOccupied(), provisioner.activeHandleCount());
        assertNotNull(leaseId);
        assertEquals(1, pool.getOccupied(), "concurrent duplicate submission occupies exactly one slot");
        assertEquals(1, provisioner.activeHandleCount());
    }

    @Test
    void autoReclaimAndManualReleaseRaceSettlesUniquely() throws Exception {
        LeaseResponse lease = service.acquire(acquireRequest("race-req", false));
        String leaseId = lease.getLeaseId();

        // 推进时钟使其刚好过期，然后让“自动回收”和“主动释放”并发
        clock.advanceSeconds(61);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<Boolean> reclaimFuture = exec.submit(() -> {
            start.await();
            return service.reclaimIfExpired(store.getRequired(leaseId));
        });
        Future<?> releaseFuture = exec.submit(() -> {
            start.await();
            try {
                service.release(leaseId, "race-release");
                return null;
            } catch (LeaseException ex) {
                return ex;
            }
        });
        start.countDown();
        boolean reclaimed = reclaimFuture.get(10, TimeUnit.SECONDS);
        Object releaseResult = releaseFuture.get(10, TimeUnit.SECONDS);
        exec.shutdown();

        Lease finalLease = store.getRequired(leaseId);
        log.info("[race-test] lease={} reclaimed={} releaseResult={} finalStatus={} occupied={} handles={}",
                leaseId, reclaimed,
                releaseResult instanceof LeaseException le ? le.getErrorCode() : "RELEASED",
                finalLease.getStatus(), pool.getOccupied(), provisioner.activeHandleCount());

        // 配额恰好归还一次：无论谁先到，余额唯一确定为 0，句柄唯一反注册
        assertEquals(0, pool.getOccupied());
        assertEquals(0, provisioner.activeHandleCount());
        assertEquals(1, finalLease.getVersion(), "only one terminal transition must occur");
        if (reclaimed) {
            assertEquals(LeaseStatus.EXPIRED, finalLease.getStatus());
            assertTrue(releaseResult instanceof LeaseException);
            assertEquals(ErrorCode.LEASE_EXPIRED, ((LeaseException) releaseResult).getErrorCode());
        } else {
            assertEquals(LeaseStatus.RELEASED, finalLease.getStatus());
            assertFalse(releaseResult instanceof LeaseException);
        }
    }

    @Test
    void doubleReleaseAndRenewAfterTerminalStateHaveDistinctReplayableReasons() {
        LeaseResponse lease = service.acquire(acquireRequest("terminal-req", false));
        String leaseId = lease.getLeaseId();

        service.release(leaseId, "release-first");
        LeaseException second = assertThrows(LeaseException.class,
                () -> service.release(leaseId, "release-second"));
        assertEquals(ErrorCode.LEASE_RELEASED, second.getErrorCode());
        assertEquals(0, pool.getOccupied(), "double release must not refund quota twice");

        LeaseException renew = assertThrows(LeaseException.class,
                () -> service.renew(leaseId, "renew-dead", 60L));
        assertEquals(ErrorCode.LEASE_RELEASED, renew.getErrorCode());

        // 同一失败操作重复提交，结论可复现
        LeaseException releaseReplay = assertThrows(LeaseException.class,
                () -> service.release(leaseId, "release-second"));
        assertEquals(ErrorCode.LEASE_RELEASED, releaseReplay.getErrorCode());
        assertEquals(0, pool.getOccupied());
        log.info("[terminal-test] lease={} doubleRelease={} renewAfterRelease={} occupied={}",
                leaseId, second.getErrorCode(), renew.getErrorCode(), pool.getOccupied());
    }

    @Test
    void expiredLeaseRejectsRenewAndOperationsSettleToSingleTerminalState() {
        LeaseResponse lease = service.acquire(acquireRequest("expire-req", false));
        String leaseId = lease.getLeaseId();
        clock.advanceSeconds(61);

        // 续约路径会先惰性终结：明确拒绝，原因可区分，配额恰好归还一次
        LeaseException renewEx = assertThrows(LeaseException.class,
                () -> service.renew(leaseId, "renew-late", 60L));
        assertEquals(ErrorCode.LEASE_EXPIRED, renewEx.getErrorCode());
        assertEquals(0, pool.getOccupied(), "expiry must refund quota exactly once");

        // 此时租约已是 EXPIRED 终态，释放也只能得到确定的 LEASE_EXPIRED 结论
        LeaseException releaseEx = assertThrows(LeaseException.class,
                () -> service.release(leaseId, "release-late"));
        assertEquals(ErrorCode.LEASE_EXPIRED, releaseEx.getErrorCode());
        assertEquals(0, pool.getOccupied(), "no double refund after terminal state");

        LeaseResponse queried = service.get(leaseId);
        assertEquals(LeaseStatus.EXPIRED, queried.getStatus());
        assertEquals(0, provisioner.activeHandleCount());
        log.info("[expiry-test] lease={} finalStatus={} occupied={} handles={}",
                leaseId, queried.getStatus(), pool.getOccupied(), provisioner.activeHandleCount());
    }

    @Test
    void releaseBeforeReaperOnOverdueLeaseStillProducesUniqueOutcome() {
        // 已过 expiresAt、但回收器尚未运行：主动释放合法终结，之后重复释放/回收都不会重复归还
        LeaseResponse lease = service.acquire(acquireRequest("overdue-release", false));
        String leaseId = lease.getLeaseId();
        clock.advanceSeconds(70);

        LeaseResponse released = service.release(leaseId, "release-overdue");
        assertEquals(LeaseStatus.RELEASED, released.getStatus());
        assertEquals(0, pool.getOccupied());
        assertEquals(0, provisioner.activeHandleCount());

        boolean reclaimed = service.reclaimIfExpired(store.getRequired(leaseId));
        assertFalse(reclaimed, "reaper must not finalize an already RELEASED lease");
        assertEquals(0, pool.getOccupied(), "quota stays at zero, no double accounting");

        LeaseException again = assertThrows(LeaseException.class,
                () -> service.release(leaseId, "release-again"));
        assertEquals(ErrorCode.LEASE_RELEASED, again.getErrorCode());
        log.info("[overdue-test] lease={} releasedWhileOverdue reclaimed={} occupied={}",
                leaseId, reclaimed, pool.getOccupied());
    }

    @Test
    void invalidRequestsAreRejectedBeforeTouchingQuota() {
        AcquireRequest blank = acquireRequest("invalid-1", false);
        blank.setRequestId("  ");
        LeaseException ex = assertThrows(LeaseException.class, () -> service.acquire(blank));
        assertEquals(ErrorCode.INVALID_REQUEST, ex.getErrorCode());

        AcquireRequest tooLong = acquireRequest("invalid-2", false);
        tooLong.setTtlSeconds(999_999L);
        LeaseException ex2 = assertThrows(LeaseException.class, () -> service.acquire(tooLong));
        assertEquals(ErrorCode.INVALID_REQUEST, ex2.getErrorCode());

        assertEquals(0, pool.getOccupied());
        assertEquals(0, provisioner.activeHandleCount());
        log.info("[validation-test] invalid requests rejected: {} / {} occupied={}",
                ex.getErrorCode(), ex2.getErrorCode(), pool.getOccupied());
    }

    @Test
    void unknownLeaseOperationsReturnNotFound() {
        String missing = "lease-" + UUID.randomUUID();
        assertEquals(ErrorCode.LEASE_NOT_FOUND,
                assertThrows(LeaseException.class, () -> service.get(missing)).getErrorCode());
        assertEquals(ErrorCode.LEASE_NOT_FOUND,
                assertThrows(LeaseException.class, () -> service.renew(missing, "r", 10L)).getErrorCode());
        assertEquals(ErrorCode.LEASE_NOT_FOUND,
                assertThrows(LeaseException.class, () -> service.release(missing, "x")).getErrorCode());
    }
}
