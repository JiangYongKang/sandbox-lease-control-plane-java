package com.github.highcumontoa.sandboxleasecontrolplanejava.quota;

import com.github.highcumontoa.sandboxleasecontrolplanejava.config.LeaseProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内有限资源池：维护容量/已占用配额。
 * reserve / release 使用 CAS，保证并发抢占不超发。
 */
@Component
public class ResourcePool {

    private final AtomicInteger capacity;
    private final AtomicInteger occupied = new AtomicInteger(0);

    public ResourcePool(LeaseProperties properties) {
        this.capacity = new AtomicInteger(properties.getPoolCapacity());
    }

    /** 尝试占用 1 个配额；成功返回 true，容量不足返回 false，不改变余额。 */
    public boolean reserve() {
        while (true) {
            int current = occupied.get();
            if (current >= capacity.get()) {
                return false;
            }
            if (occupied.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 归还 1 个配额；归还量不得超过已占用量（防御性，超出会被钳制到 0）。 */
    public void release() {
        while (true) {
            int current = occupied.get();
            if (current <= 0) {
                // 任何路径都不允许把余额“还穿”，防御重复回收/重复释放
                return;
            }
            if (occupied.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    public int getCapacity() {
        return capacity.get();
    }

    public int getOccupied() {
        return occupied.get();
    }

    public int getAvailable() {
        return capacity.get() - occupied.get();
    }
}
