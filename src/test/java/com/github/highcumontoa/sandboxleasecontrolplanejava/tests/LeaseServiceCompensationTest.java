package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseService;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseServiceException;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.ReleaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.RenewRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.pool.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.quota.TeamQuotaManager;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision.LocalSandboxProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.store.LeaseStore;
import com.github.highcumontoa.sandboxleasecontrolplanejava.tests.LeaseServiceLifecycleTest.MutableClock;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 失败补偿、超时回收与重复释放测试。
 */
class LeaseServiceCompensationTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseServiceCompensationTest.class);

    private record Harness(LeaseService service, ResourcePool pool, MutableClock clock,
                           LeaseStore store) {
    }

    private Harness harness(int capacity) {
        LeaseProperties props = new LeaseProperties();
        props.setPoolCapacity(capacity);
        props.setDefaultTtlSeconds(60);
        props.setMaxTtlSeconds(3600);
        MutableClock clock = new MutableClock();
        ResourcePool pool = new ResourcePool(props);
        LeaseStore store = new LeaseStore();
        LeaseService service = new LeaseService(store, pool,
                new TeamQuotaManager(props), new LocalSandboxProvisioner(), clock, props);
        return new Harness(service, pool, clock, store);
    }

    @Test
    void provisionFailure_compensatesQuotaAndLeavesFailedLease() {
        Harness h = harness(1);

        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("fail-req-1", "fail:broken-team", 30)));
        log.warn("[test] requestId=fail-req-1 rejected code={}, reason={}, leased={}",
                ex.code(), ex.getMessage(), h.pool.leased());
        assertEquals(ErrorCode.PROVISION_FAILED, ex.code());
        assertEquals(0, h.pool.leased(), "失败路径不得泄漏配额");
        assertEquals(1, h.pool.available());

        // 幂等记录中的首次结论为 FAILED，重复提交得到同一结论，而不会第二次成功占用配额
        LeaseView recorded = h.store.findApplyRecord("fail-req-1").orElseThrow().view();
        assertEquals("FAILED", recorded.status());
        LeaseServiceException again = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("fail-req-1", "fail:broken-team", 30)));
        assertEquals(ErrorCode.PROVISION_FAILED, again.code());
        assertEquals(0, h.pool.leased());
        log.info("[test] requestId=fail-req-1 duplicate result reproducible status={}, leased=0",
                recorded.status());

        // 补偿后其它租户仍可拿到唯一的一个配额 -> 没有遗留悬挂租约
        LeaseView healthy = h.service.apply(new LeaseRequest("healthy-req", "team-ok", 30));
        assertEquals("ACTIVE", healthy.status());
        assertEquals(1, h.pool.leased());
        log.info("[test] requestId=healthy-req acquired after compensation leaseId={}, leased=1",
                healthy.leaseId());
    }

    @Test
    void expiredLease_isReapedAndQuotaReturnedAndRenewRejected() {
        Harness h = harness(2);
        LeaseView v = h.service.apply(new LeaseRequest("exp-req", "team-a", 10));
        assertEquals(1, h.pool.leased());

        h.clock.advanceMillis(10_001);
        int reaped = h.service.reapExpired();
        log.info("[test] auto-reap requestId=exp-req leaseId={} reaped={}, status=EXPIRED, leased={}",
                v.leaseId(), reaped, h.pool.leased());
        assertEquals(1, reaped);
        assertEquals(0, h.pool.leased());

        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.renew(v.leaseId(), new RenewRequest("exp-renew", 10)));
        assertEquals(ErrorCode.LEASE_EXPIRED, ex.code());
        log.warn("[test] requestId=exp-renew rejected on expired lease code={}", ex.code());
    }

    @Test
    void doubleRelease_isIdempotentAndQuotaStaysBalanced() {
        Harness h = harness(1);
        LeaseView v = h.service.apply(new LeaseRequest("rel-req", "team-a", 60));

        LeaseView r1 = h.service.release(v.leaseId(), new ReleaseRequest("rel-op-1"));
        LeaseView r2 = h.service.release(v.leaseId(), new ReleaseRequest("rel-op-2"));
        log.info("[test] double release leaseId={} status1={} status2={}, leased={}",
                v.leaseId(), r1.status(), r2.status(), h.pool.leased());
        assertEquals("RELEASED", r1.status());
        assertEquals("RELEASED", r2.status());
        assertEquals(0, h.pool.leased(), "重复释放不得重复归还配额");

        // 对已释放租约续约必须被明确拒绝
        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.renew(v.leaseId(), new RenewRequest("renew-after-release", 10)));
        assertEquals(ErrorCode.LEASE_NOT_ACTIVE, ex.code());
    }

    @Test
    void releaseAfterExpiry_returnsExpiredWithoutDoubleRefund() {
        Harness h = harness(1);
        LeaseView v = h.service.apply(new LeaseRequest("race-req", "team-a", 5));
        h.clock.advanceMillis(5_001);

        // 未跑 reaper，直接释放：惰性到期先生效，结论为 EXPIRED，配额只归还一次
        LeaseView view = h.service.release(v.leaseId(), new ReleaseRequest("race-rel"));
        log.info("[test] release vs expiry race leaseId={} finalStatus={}, leased={}",
                v.leaseId(), view.status(), h.pool.leased());
        assertEquals("EXPIRED", view.status());
        assertEquals(0, h.pool.leased());
    }
}
