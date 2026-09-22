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
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision.LocalSandboxProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.store.LeaseStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生命周期、配额核算与申请幂等测试。
 * 日志统一打印 requestId、配额变化与状态迁移，便于复现排查。
 */
class LeaseServiceLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseServiceLifecycleTest.class);

    /** 可控时钟，保证超时场景结果确定、可复现。 */
    static final class MutableClock extends Clock {
        private long millis = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        void advanceMillis(long delta) {
            millis += delta;
        }
    }

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
                new LocalSandboxProvisioner(), clock, props);
        return new Harness(service, pool, clock);
    }

    @Test
    void fullLifecycle_applyRenewReleaseAndQuotaBalance() {
        Harness h = harness(2);
        LeaseView v = h.service.apply(new LeaseRequest("req-1", "team-a", 30));
        log.info("[test] requestId=req-1 acquired leaseId={}, quota leased={}/{}, status={}",
                v.leaseId(), h.pool.leased(), h.pool.capacity(), v.status());
        assertEquals("ACTIVE", v.status());
        assertEquals(1, h.pool.leased());

        h.clock.advanceMillis(20_000);
        LeaseView renewed = h.service.renew(v.leaseId(), new RenewRequest("req-renew-1", 30));
        log.info("[test] requestId=req-renew-1 renewed leaseId={}, expiresAt={}, quota leased={}",
                renewed.leaseId(), renewed.expiresAtMillis(), h.pool.leased());
        assert renewed.expiresAtMillis() > v.expiresAtMillis();

        LeaseView released = h.service.release(v.leaseId(), new ReleaseRequest("req-rel-1"));
        log.info("[test] requestId=req-rel-1 released leaseId={}, status={}, quota leased={}",
                released.leaseId(), released.status(), h.pool.leased());
        assertEquals("RELEASED", released.status());
        assertEquals(0, h.pool.leased());
    }

    @Test
    void duplicateApply_sameRequestIdReturnsSameLeaseAndDoesNotDoubleOccupy() {
        Harness h = harness(3);
        LeaseRequest req = new LeaseRequest("dup-req", "team-a", 30);
        LeaseView first = h.service.apply(req);
        LeaseView second = h.service.apply(req);

        log.info("[test] duplicate apply requestId=dup-req first.leaseId={} second.leaseId={}, leased={}",
                first.leaseId(), second.leaseId(), h.pool.leased());
        assertEquals(first.leaseId(), second.leaseId());
        assertEquals(first.resourceId(), second.resourceId());
        assertEquals(1, h.pool.leased(), "重复提交不得重复占用资源");
    }

    @Test
    void quotaExhaustion_isRejectedWithDistinguishableReason() {
        Harness h = harness(2);
        List<String> leaseIds = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String rid = "q-req-" + i;
            leaseIds.add(h.service.apply(new LeaseRequest(rid, "team-a", 60)).leaseId());
            log.info("[test] requestId={} acquired, leased={}/2", rid, h.pool.leased());
        }

        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> h.service.apply(new LeaseRequest("q-req-3rd", "team-a", 60)));
        log.warn("[test] requestId=q-req-3rd rejected code={}, reason={}", ex.code(), ex.getMessage());
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, ex.code());
        assertEquals(2, h.pool.leased());

        // 释放一个后配额恢复，可再次申请
        h.service.release(leaseIds.get(0), new ReleaseRequest("q-rel-0"));
        LeaseView extra = h.service.apply(new LeaseRequest("q-req-retry", "team-a", 60));
        log.info("[test] requestId=q-req-retry acquired after release leaseId={}, leased={}",
                extra.leaseId(), h.pool.leased());
        assertEquals(2, h.pool.leased());
        assertNotEquals(leaseIds.get(0), extra.leaseId());
    }
}
