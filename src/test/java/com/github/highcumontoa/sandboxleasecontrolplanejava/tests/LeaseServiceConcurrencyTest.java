package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseService;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseServiceException;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.ReleaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.pool.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.quota.TeamQuotaManager;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision.LocalSandboxProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.store.LeaseStore;
import com.github.highcumontoa.sandboxleasecontrolplanejava.tests.LeaseServiceLifecycleTest.MutableClock;
import java.util.HashSet;
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
 * 并发抢占一致性测试：容量上限、资源余额与重复提交去重。
 */
class LeaseServiceConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseServiceConcurrencyTest.class);

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
    void concurrentContention_neverExceedsCapacity() throws Exception {
        int capacity = 4;
        int threads = 40;
        Harness h = harness(capacity);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> grantedLeaseIds = ConcurrentHashMap.newKeySet();
        Set<Integer> observedInvariant = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    LeaseView v = h.service.apply(new LeaseRequest("c-req-" + idx, "team-" + idx, 60));
                    success.incrementAndGet();
                    grantedLeaseIds.add(v.leaseId());
                } catch (LeaseServiceException ex) {
                    rejected.incrementAndGet();
                    assertEquals(com.github.highcumontoa.sandboxleasecontrolplanejava.lease.ErrorCode.QUOTA_EXHAUSTED,
                            ex.code());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (h) {
                        observedInvariant.add(h.pool.leased());
                    }
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[test] concurrent contention threads={} success={} rejected={} leased={}/{} distinctLeases={}",
                threads, success.get(), rejected.get(), h.pool.leased(), capacity, grantedLeaseIds.size());
        assertEquals(capacity, success.get());
        assertEquals(threads - capacity, rejected.get());
        assertEquals(capacity, h.pool.leased());
        assertEquals(capacity, grantedLeaseIds.size(), "每个成功请求分配不同租约");
        for (int leased : observedInvariant) {
            assertTrue(leased <= capacity, "任何时刻占用都不得超过容量: " + leased);
        }
    }

    @Test
    void duplicateSubmitUnderConcurrency_sameRequestOccupiesOnce() throws Exception {
        Harness h = harness(4);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> leaseIds = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    LeaseView v = h.service.apply(new LeaseRequest("same-request-id", "team-a", 60));
                    leaseIds.add(v.leaseId());
                } catch (LeaseServiceException e) {
                    // 不期望出现异常
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[test] concurrent duplicate submit requestId=same-request-id distinctLeases={} leased={}",
                leaseIds.size(), h.pool.leased());
        assertEquals(1, leaseIds.size());
        assertEquals(1, h.pool.leased());
    }

    @Test
    void concurrentReleaseAndReap_finalStateUniqueAndQuotaBalanced() throws Exception {
        int capacity = 20;
        Harness h = harness(capacity);
        Set<String> leaseIds = new HashSet<>();
        for (int i = 0; i < capacity; i++) {
            leaseIds.add(h.service.apply(new LeaseRequest("rc-req-" + i, "team", 5)).leaseId());
        }
        assertEquals(capacity, h.pool.leased());

        // 全部到期：主动释放与自动回收同时发生
        h.clock.advanceMillis(5_001);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(capacity * 2);
        AtomicInteger released = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();

        for (String id : leaseIds) {
            pool.submit(() -> {
                try {
                    start.await();
                    LeaseView v = h.service.release(id, new ReleaseRequest("rel-" + id));
                    if ("RELEASED".equals(v.status())) {
                        released.incrementAndGet();
                    } else if ("EXPIRED".equals(v.status())) {
                        expired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (LeaseServiceException e) {
                    log.warn("[test] unexpected release failure: leaseId={}, code={}",
                            id, e.code());
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    h.service.reapExpired();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // 回收无租约可处理时正常返回
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        // 再次执行两种终结操作，结论必须保持稳定且配额不发生变化
        int secondReap = h.service.reapExpired();
        for (String id : leaseIds) {
            LeaseView v = h.service.release(id, new ReleaseRequest("rel-dup-" + id));
            assertTrue(v.status().equals("RELEASED") || v.status().equals("EXPIRED"));
        }
        log.info("[test] release/reap race released={} expired={} secondReap={} finalLeased={}/{}",
                released.get(), expired.get(), secondReap, h.pool.leased(), capacity);
        assertEquals(capacity, released.get() + expired.get(), "每个租约恰好终结一次");
        assertEquals(0, secondReap);
        assertEquals(0, h.pool.leased(), "竞争结束后资源余额唯一确定为 0");
    }
}
