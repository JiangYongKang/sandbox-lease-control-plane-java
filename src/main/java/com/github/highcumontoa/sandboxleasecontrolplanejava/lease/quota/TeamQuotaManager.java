package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.quota;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamQuotaView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.pool.ResourcePool;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 团队额度管理：每团队的额度上限配置与占用记账。
 *
 * <p><b>记账不变量</b>：占用计数的所有变更都由 {@code LeaseService} 的全局锁串行调用，
 * 与整池 {@link ResourcePool} 的 acquire/release 一一配平——
 * 申请成功占用一次；主动释放 / 到期回收（定时与惰性）/ 申请失败补偿各归还一次，
 * 因此任意时刻所有团队占用之和 {@link #totalUsed()} 恒等于整池 {@code leased}。
 *
 * <p><b>限额语义</b>：未配置上限的团队 {@link #isLimited} 为 false，只受整池容量约束，
 * 保持与引入团队额度之前完全一致的兼容行为；已配置团队占用达到上限时
 * {@link #canAcquire} 为 false，调用方据此给出可区分的拒绝原因。
 *
 * <p><b>在线调整</b>：{@link #configure} 只改配置不动占用；调到低于当前占用时
 * 剩余额度按 0 呈现，仅拒绝后续申请，不抢占已开出租约。
 */
@Component
public class TeamQuotaManager {

    private static final Logger log = LoggerFactory.getLogger(TeamQuotaManager.class);

    /** 团队 -> 额度上限。仅出现在该表中的团队才受单独额度约束。 */
    private final ConcurrentMap<String, Integer> limits = new ConcurrentHashMap<>();

    /** 团队 -> 当前占用（非终态租约数）。 */
    private final ConcurrentMap<String, Integer> used = new ConcurrentHashMap<>();

    public TeamQuotaManager(LeaseProperties properties) {
        if (properties != null && properties.getTeamLimits() != null) {
            properties.getTeamLimits().forEach((tenant, value) -> {
                if (value != null && value >= 0) {
                    limits.put(tenant, value);
                }
            });
        }
        log.info("TeamQuotaManager initialized: configuredTeams={}, limits={}",
                limits.size(), limits);
    }

    /** 团队是否配置了单独额度上限。 */
    public boolean isLimited(String tenantId) {
        return limits.containsKey(tenantId);
    }

    /**
     * 在不动用任何资源的前提下，该团队当前是否还能再申请一个。
     * 未配置上限的团队恒为 true（兼容整池共享行为）。
     */
    public boolean canAcquire(String tenantId) {
        Integer cap = limits.get(tenantId);
        return cap == null || used.getOrDefault(tenantId, 0) < cap;
    }

    /** 记账：团队占用 +1（调用方须先经 {@link #canAcquire} 与整池容量校验）。 */
    public void acquire(String tenantId) {
        int next = used.merge(tenantId, 1, Integer::sum);
        Integer cap = limits.get(tenantId);
        log.info("team quota acquire: tenantId={}, leased {} -> {}, limit={}",
                tenantId, next - 1, next, cap);
    }

    /** 记账：团队占用 -1。与 acquire 严格配平，带下限保护防止重复归还破坏余额。 */
    public void release(String tenantId) {
        while (true) {
            int current = used.getOrDefault(tenantId, 0);
            if (current <= 0) {
                log.error("unbalanced team quota release detected: tenantId={}, leased already 0, "
                        + "ignored to protect quota balance", tenantId);
                return;
            }
            if (used.replace(tenantId, current, current - 1)) {
                log.info("team quota release: tenantId={}, leased {} -> {}, limit={}",
                        tenantId, current, current - 1, limits.get(tenantId));
                return;
            }
        }
    }

    /** 团队当前占用。 */
    public int used(String tenantId) {
        return used.getOrDefault(tenantId, 0);
    }

    /** 团队额度上限；未配置返回 null（沿用整池共享约束）。 */
    public Integer limit(String tenantId) {
        return limits.get(tenantId);
    }

    /** 所有团队占用之和（用于与整池 leased 做一致性校验）。 */
    public int totalUsed() {
        return used.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * 在线调整额度：limit 为 null 表示移除配置回到整池共享；0 表示禁止新申请。
     * 调整只影响后续申请，不抢占已开出租约。返回生效后的上限。
     */
    public Integer configure(String tenantId, Integer limit) {
        Integer effective;
        if (limit == null) {
            effective = limits.remove(tenantId);
            log.info("team quota config removed: tenantId={}, previousLimit={}, leased={}",
                    tenantId, effective, used(tenantId));
            return null;
        }
        limits.put(tenantId, limit);
        log.info("team quota configured: tenantId={}, limit={}, leased={}, remaining={}",
                tenantId, limit, used(tenantId), remainingLocked(tenantId));
        return limit;
    }

    /** 团队额度与整池配额的一致快照：团队口径与整池口径取自同一调用现场。 */
    public TeamQuotaView snapshot(String tenantId, ResourcePool pool) {
        Integer cap = limits.get(tenantId);
        int leased = used.getOrDefault(tenantId, 0);
        return new TeamQuotaView(
                tenantId,
                cap,
                leased,
                remainingLocked(tenantId),
                pool.capacity(),
                pool.leased(),
                pool.available());
    }

    /** 剩余额度：未配置上限为 null；已配置时下限保护到 0（额度调小后允许占用高于新上限）。 */
    private Integer remainingLocked(String tenantId) {
        Integer cap = limits.get(tenantId);
        if (cap == null) {
            return null;
        }
        return Math.max(0, cap - used.getOrDefault(tenantId, 0));
    }

    /** 只读查看当前全部上限配置（日志/排查用）。 */
    public Map<String, Integer> configuredLimits() {
        return Map.copyOf(limits);
    }
}
