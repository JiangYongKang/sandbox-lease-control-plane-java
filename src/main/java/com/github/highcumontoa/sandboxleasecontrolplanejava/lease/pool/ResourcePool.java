package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.pool;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config.LeaseProperties;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 进程内有限资源池。
 *
 * <p>使用 CAS 计数，保证并发抢占下已发放租约数绝不超过容量；
 * acquire/release 严格配平，归还侧也做下限保护，使资源余额唯一确定。
 */
@Component
public class ResourcePool {

    private static final Logger log = LoggerFactory.getLogger(ResourcePool.class);

    private final int capacity;
    private final AtomicInteger leased = new AtomicInteger(0);

    public ResourcePool(LeaseProperties properties) {
        this.capacity = properties.getPoolCapacity();
        log.info("ResourcePool initialized: capacity={}", capacity);
    }

    /** 预留一个资源槽；容量不足时返回 false（不抛出、不泄漏）。 */
    public boolean tryAcquire() {
        while (true) {
            int current = leased.get();
            if (current >= capacity) {
                log.warn("quota exhausted: capacity={}, leased={}", capacity, current);
                return false;
            }
            if (leased.compareAndSet(current, current + 1)) {
                log.info("quota acquire: leased {} -> {}, available={}",
                        current, current + 1, available());
                return true;
            }
        }
    }

    /** 归还一个资源槽；对异常的超额归还做下限保护并记录告警。 */
    public void release() {
        while (true) {
            int current = leased.get();
            if (current <= 0) {
                log.error("unbalanced release detected: leased already 0, ignored to protect quota balance");
                return;
            }
            if (leased.compareAndSet(current, current - 1)) {
                log.info("quota release: leased {} -> {}, available={}",
                        current, current - 1, available());
                return;
            }
        }
    }

    public int capacity() {
        return capacity;
    }

    public int available() {
        return capacity - leased.get();
    }

    public int leased() {
        return leased.get();
    }
}
