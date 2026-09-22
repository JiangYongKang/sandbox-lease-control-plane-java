package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import com.github.highcumontoa.sandboxleasecontrolplanejava.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.LeaseStatus;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.AcquireRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.LeaseResponse;
import com.github.highcumontoa.sandboxleasecontrolplanejava.provision.InMemoryProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.quota.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.repository.LeaseStore;
import com.github.highcumontoa.sandboxleasecontrolplanejava.reclaim.LeaseReaper;
import com.github.highcumontoa.sandboxleasecontrolplanejava.service.LeaseService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 不启动 Spring 上下文的集成测试：用真实 TaskScheduler 驱动 LeaseReaper，
 * 时钟用 {@link TestClock} 显式推进，确定性地验证“超时 -> EXPIRED -> 配额/句柄归零”。
 */
class LeaseReaperIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaperIntegrationTest.class);

    @Test
    void overdueLeaseIsAutoReclaimedAndQuotaRestored() {
        LeaseProperties props = new LeaseProperties();
        props.setPoolCapacity(4);
        props.setDefaultTtlSeconds(2);
        props.setMaxTtlSeconds(600);

        LeaseStore store = new LeaseStore();
        ResourcePool pool = new ResourcePool(props);
        InMemoryProvisioner provisioner = new InMemoryProvisioner();
        TestClock clock = new TestClock();
        LeaseService service = new LeaseService(store, pool, provisioner, props, clock);
        LeaseReaper reaper = new LeaseReaper(service, store);

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("test-reaper-");
        scheduler.initialize();
        var future = scheduler.scheduleAtFixedRate(reaper::tick, Duration.ofMillis(20));

        try {
            AcquireRequest req = new AcquireRequest();
            req.setRequestId("auto-expire-req");
            req.setEnvType("devbox");
            req.setOwner("alice");
            LeaseResponse lease = service.acquire(req);
            log.info("[reaper-it] requestId=auto-expire-req lease={} expiresAt={} quota={}/{}",
                    lease.getLeaseId(), lease.getExpiresAt(), pool.getOccupied(), pool.getCapacity());
            assertEquals(1, pool.getOccupied());

            clock.advanceSeconds(3);

            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertEquals(LeaseStatus.EXPIRED, store.getRequired(lease.getLeaseId()).getStatus());
                assertEquals(0, pool.getOccupied());
                assertEquals(0, provisioner.activeHandleCount());
            });
            log.info("[reaper-it] lease={} auto-reclaimed status={} occupied={} handles={}",
                    lease.getLeaseId(), store.getRequired(lease.getLeaseId()).getStatus(),
                    pool.getOccupied(), provisioner.activeHandleCount());

            // 回收后的余额可被新租约复用
            req.setRequestId("auto-expire-req-2");
            LeaseResponse second = service.acquire(req);
            assertEquals(LeaseStatus.ACTIVE, second.getStatus());
            assertEquals(1, pool.getOccupied());
            log.info("[reaper-it] quota reused by lease={} occupied={}",
                    second.getLeaseId(), pool.getOccupied());
        } finally {
            future.cancel(true);
            scheduler.shutdown();
        }
    }
}
