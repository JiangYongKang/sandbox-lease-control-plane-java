package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.reap;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时驱动 {@link LeaseService#reapExpired()} 完成超时回收。
 * 回收在服务层同一把锁内与主动释放竞争，最终状态唯一确定。
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final LeaseService leaseService;

    public LeaseReaper(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    @Scheduled(fixedDelayString = "${lease.reaper-interval-millis:2000}")
    public void tick() {
        try {
            int reaped = leaseService.reapExpired();
            if (reaped > 0) {
                log.info("reaper tick reaped {} expired leases", reaped);
            }
        } catch (RuntimeException ex) {
            // 调度线程不能被异常打断；下一轮继续补偿。
            log.error("reaper tick failed, will retry next round", ex);
        }
    }
}
