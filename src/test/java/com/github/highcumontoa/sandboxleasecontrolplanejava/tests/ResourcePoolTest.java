package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import com.github.highcumontoa.sandboxleasecontrolplanejava.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.quota.ResourcePool;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额池：并发抢占绝不超发；释放后余额回到初始值。
 */
class ResourcePoolTest {

    private static final Logger log = LoggerFactory.getLogger(ResourcePoolTest.class);

    private ResourcePool poolWithCapacity(int capacity) {
        LeaseProperties props = new LeaseProperties();
        props.setPoolCapacity(capacity);
        return new ResourcePool(props);
    }

    @Test
    void concurrentReserveNeverExceedsCapacity() throws Exception {
        int capacity = 10;
        int threads = 64;
        ResourcePool pool = poolWithCapacity(capacity);
        ExecutorService poolExec = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            String requestId = "quota-req-" + i;
            poolExec.submit(() -> {
                try {
                    start.await();
                    if (pool.reserve()) {
                        granted.incrementAndGet();
                        log.info("[quota-test] requestId={} quota after reserve={}/{}",
                                requestId, pool.getOccupied(), pool.getCapacity());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        poolExec.shutdown();

        log.info("[quota-test] settled: granted={} occupied={} available={}",
                granted.get(), pool.getOccupied(), pool.getAvailable());
        assertEquals(capacity, granted.get(), "exactly capacity requests must succeed");
        assertEquals(capacity, pool.getOccupied());
        assertEquals(0, pool.getAvailable());

        // 全部归还后必须回到初始余额，重复归还不得“还穿”
        for (int i = 0; i < capacity + 5; i++) {
            pool.release();
        }
        log.info("[quota-test] after release: occupied={} (extra 5 releases must be ignored)",
                pool.getOccupied());
        assertEquals(0, pool.getOccupied());
    }
}
