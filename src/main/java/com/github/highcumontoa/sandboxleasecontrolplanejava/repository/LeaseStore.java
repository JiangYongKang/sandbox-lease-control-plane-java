package com.github.highcumontoa.sandboxleasecontrolplanejava.repository;

import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.Lease;
import com.github.highcumontoa.sandboxleasecontrolplanejava.error.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.error.LeaseException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租约仓储（进程内）：
 * - leaseId -> Lease
 * - requestId -> 首次执行结论（幂等回放）
 * 本身只负责存取；跨组件一致性与状态迁移由 LeaseService 在临界区内编排。
 */
@Component
public class LeaseStore {

    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final Map<String, IdempotentOutcome> outcomes = new ConcurrentHashMap<>();

    public void save(Lease lease) {
        leases.put(lease.getLeaseId(), lease);
    }

    public Optional<Lease> find(String leaseId) {
        return Optional.ofNullable(leases.get(leaseId));
    }

    public Lease getRequired(String leaseId) {
        return find(leaseId)
                .orElseThrow(() -> new LeaseException(ErrorCode.LEASE_NOT_FOUND,
                        "lease not found: " + leaseId));
    }

    public Collection<Lease> all() {
        return leases.values();
    }

    public Optional<IdempotentOutcome> findOutcome(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(outcomes.get(requestId));
    }

    /**
     * 记录首次执行结论。requestId 已存在时保留原结论（正常情况下上层已先查过，
     * 这里再用 putIfAbsent 兜底，保证并发下结论唯一）。
     */
    public IdempotentOutcome saveOutcome(IdempotentOutcome outcome) {
        IdempotentOutcome existing = outcomes.putIfAbsent(outcome.getRequestId(), outcome);
        return existing != null ? existing : outcome;
    }

    /** 仅供测试：清空全部状态。 */
    public void clear() {
        leases.clear();
        outcomes.clear();
    }
}
