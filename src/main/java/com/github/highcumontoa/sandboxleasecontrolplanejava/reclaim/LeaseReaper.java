package com.github.highcumontoa.sandboxleasecontrolplanejava.reclaim;

import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.Lease;
import com.github.highcumontoa.sandboxleasecontrolplanejava.repository.LeaseStore;
import com.github.highcumontoa.sandboxleasecontrolplanejava.service.LeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时自动回收：扫描 ACTIVE 且过期的租约。
 * 与主动释放竞争时，由 LeaseService 的临界区保证最终状态/配额唯一确定
 * （谁先拿到锁谁终结，另一方拿到 LEASE_EXPIRED / LEASE_RELEASED 的确定结论）。
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final LeaseService leaseService;
    private final LeaseStore store;

    public LeaseReaper(LeaseService leaseService, LeaseStore store) {
        this.leaseService = leaseService;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${lease.reaper.interval-millis:1000}")
    public void tick() {
        // 复制快照避免遍历期间并发修改；每个租约是否回收仍由服务层加锁判定
        for (Lease lease : store.all().toArray(new Lease[0])) {
            try {
                leaseService.reclaimIfExpired(lease);
            } catch (RuntimeException ex) {
                // 单条回收失败不影响本轮其他租约，且不会改变配额（回收是幂等判定）
                log.error("[reaper] reclaim attempt failed for lease={}: {}",
                        lease.getLeaseId(), ex.toString());
            }
        }
    }
}
