package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.quota;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config.LeaseProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 团队额度登记处。
 *
 * <p>职责与不变量：
 * <ul>
 *   <li>维护每个团队的额度上限（可在线调整；未配置的团队不额外受限，仅受整池容量约束）；</li>
 *   <li>为<strong>所有</strong>团队（含未配置上限的）记账当前占用，保证任意时刻
 *       “所有团队占用之和 == 资源池整体 leased”——每次池配额的 acquire/release
 *       都在 LeaseService 同一把公平锁内与本登记处成对执行，因此检查-记账的
 *       check-then-act 不存在并发窗口；</li>
 *   <li>归还侧做下限保护并告警，防止余额被破坏、重复归还。</li>
 * </ul>
 *
 * <p>额度上限的读取/调整与占用记账全部发生在服务层锁内，因此在线调整对后续申请
 * 立即生效，且不影响存量租约（调低到当前占用之下只是让后续申请被拒）。
 */
@Component
public class TeamQuotaRegistry {

    private static final Logger log = LoggerFactory.getLogger(TeamQuotaRegistry.class);

    /** teamId -> 额度上限；未配置的团队不在此表中。 */
    private final ConcurrentMap<String, Integer> limits = new ConcurrentHashMap<>();
    /** teamId -> 当前占用；所有团队（含未配置上限的）都记账。 */
    private final ConcurrentMap<String, AtomicInteger> usage = new ConcurrentHashMap<>();

    public TeamQuotaRegistry(LeaseProperties properties) {
        for (Map.Entry<String, Integer> e : properties.getTeamQuotas().entrySet()) {
            validateLimit(e.getKey(), e.getValue());
            limits.put(e.getKey(), e.getValue());
        }
        log.info("TeamQuotaRegistry initialized: staticLimits={}", limits);
    }

    /**
     * 尝试为团队占用一份额度；已配上限且达到上限时返回 false（不抛出、不泄漏）。
     * 未配置上限的团队始终准入，但仍记账以维持全局占用不变量。
     */
    public boolean tryAcquire(String teamId) {
        AtomicInteger used = usage.computeIfAbsent(teamId, k -> new AtomicInteger(0));
        Integer limit = limits.get(teamId);
        if (limit != null && used.get() >= limit) {
            log.warn("team quota exhausted: teamId={}, limit={}, used={}", teamId, limit, used.get());
            return false;
        }
        int after = used.incrementAndGet();
        log.info("team quota acquire: teamId={}, used {} -> {}, limit={}",
                teamId, after - 1, after, limit);
        return true;
    }

    /** 归还一份团队占用；对异常的超额归还做下限保护并记录告警。 */
    public void release(String teamId) {
        AtomicInteger used = usage.computeIfAbsent(teamId, k -> new AtomicInteger(0));
        while (true) {
            int current = used.get();
            if (current <= 0) {
                log.error("unbalanced team quota release detected: teamId={}, used already 0, ignored",
                        teamId);
                return;
            }
            if (used.compareAndSet(current, current - 1)) {
                log.info("team quota release: teamId={}, used {} -> {}, limit={}",
                        teamId, current, current - 1, limits.get(teamId));
                return;
            }
        }
    }

    /** 在线设置团队额度上限，对后续申请立即生效，不影响存量租约。 */
    public void setLimit(String teamId, int limit) {
        validateLimit(teamId, limit);
        Integer before = limits.put(teamId, limit);
        log.info("team quota limit updated: teamId={}, limit {} -> {}, used={}",
                teamId, before, limit, usedOf(teamId));
    }

    /** 清除团队额度上限，恢复整池共享约束（占用记账继续保留）。 */
    public void clearLimit(String teamId) {
        Integer before = limits.remove(teamId);
        log.info("team quota limit cleared: teamId={}, previousLimit={}, used={}",
                teamId, before, usedOf(teamId));
    }

    /** 团队当前上限；未配置返回 null。 */
    public Integer limitOf(String teamId) {
        return limits.get(teamId);
    }

    /** 团队当前占用。 */
    public int usedOf(String teamId) {
        AtomicInteger used = usage.get(teamId);
        return used == null ? 0 : used.get();
    }

    private static void validateLimit(String teamId, Integer limit) {
        if (teamId == null || teamId.isBlank()) {
            throw new IllegalArgumentException("teamId must not be blank");
        }
        if (limit == null || limit < 0) {
            throw new IllegalArgumentException("team quota limit must be a non-negative integer");
        }
    }
}
