package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

/** 查询可复现、非法状态拒绝与请求标识冲突的守卫测试。 */
class LeaseServiceGuardTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseServiceGuardTest.class);

    private record Harness(LeaseService service, ResourcePool pool, MutableClock clock) {
    }

    private Harness harness(int capacity) {
        LeaseProperties props = new LeaseProperties();
        props.setPoolCapacity(capacity);
        props.setDefaultTtlSeconds(60);
        props.setMaxTtlSeconds(3600);
        MutableClock clock = new MutableClock();
        ResourcePool pool = new ResourcePool(props);
        LeaseService service = new LeaseService(new LeaseStore(), pool,
                new TeamQuotaManager(props), new LocalSandboxProvisioner(), clock, props);
        return new Harness(service, pool, clock);
    }

    @Test
    void repeatedQuery_isReproducibleAndNotFoundIsDistinguishable() {
        Harness h = harness(1);
        LeaseView v = h.service.apply(new LeaseRequest("g-req", "team-a", 60));
        LeaseView q1 = h.service.get(v.leaseId());
        LeaseView q2 = h.service.get(v.leaseId());
        log.info("[test] repeated query leaseId={} status1={} status2={}", v.leaseId(), q1.status(), q2.status());
        assertEquals(q1, q2, "同一租约重复查询结论必须可复现");

        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.get("does-not-exist"));
        assertEquals(ErrorCode.LEASE_NOT_FOUND, ex.code());
    }

    @Test
    void renewWithRequestIdUsedByAnotherLease_conflicts() {
        Harness h = harness(3);
        LeaseView a = h.service.apply(new LeaseRequest("owner-a", "team-a", 60));
        LeaseView b = h.service.apply(new LeaseRequest("owner-b", "team-b", 60));
        // owner-a 是租约 a 的申请标识；把它拿去操作租约 b 必须被拒绝
        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.renew(b.leaseId(), new RenewRequest("owner-a", 60)));
        log.warn("[test] requestId reuse across leases rejected code={}", ex.code());
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ex.code());
    }

    @Test
    void repeatedOperation_sameRequestIdReturnsSameConclusion() {
        Harness h = harness(2);
        LeaseView v = h.service.apply(new LeaseRequest("op-req", "team-a", 60));
        LeaseView r1 = h.service.release(v.leaseId(), new ReleaseRequest("same-op"));
        LeaseView r2 = h.service.release(v.leaseId(), new ReleaseRequest("same-op"));
        assertEquals(r1, r2);
        assertEquals(0, h.pool.leased());
        log.info("[test] repeated op requestId=same-op reproducible status={}, leased=0", r2.status());

        // 非法参数与非法 ttl 的错误码可区分
        assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest(null, "team", 60)));
        LeaseServiceException badTtl = assertThrows(LeaseServiceException.class,
                () -> h.service.renew(v.leaseId(), new RenewRequest("bad-ttl", 0)));
        assertEquals(ErrorCode.INVALID_REQUEST, badTtl.code());
        assertNotEquals(ErrorCode.LEASE_NOT_ACTIVE, badTtl.code());
    }
}
