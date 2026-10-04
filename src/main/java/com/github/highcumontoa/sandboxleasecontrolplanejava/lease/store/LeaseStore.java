package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.store;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.domain.Lease;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 进程内租约存储，由四张并发表构成：
 * <ul>
 *   <li>{@code leases}：leaseId -> 租约聚合（其状态变更由 LeaseService 的全局锁保护）；</li>
 *   <li>{@code applyRecords}：requestId -> 首次申请结论（申请幂等去重，含成功与 FAILED）；</li>
 *   <li>{@code rejectedApplies}：requestId -> 首次“未占用即拒绝”的结论
 *       （整池已满 / 团队额度已满），重复提交必须复现同一拒绝原因；</li>
 *   <li>{@code opRecords}：requestId -> 续约/释放首次结论（操作幂等）。</li>
 * </ul>
 * 记录通过 putIfAbsent 原子落库，保证“先记录结论、再返回”，重复提交只能看到首次结论。
 */
@Component
public class LeaseStore {

    /** 一次申请的幂等结论：包含首次分配的 leaseId 与结论快照。 */
    public record IdempotentRecord(String leaseId, LeaseView view) {
    }

    /** 一次“未产生租约即被拒”申请的首次结论（如 QUOTA_EXHAUSTED / TEAM_QUOTA_EXHAUSTED）。 */
    public record RejectedApplyRecord(ErrorCode code, String message) {
    }

    private final ConcurrentMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IdempotentRecord> applyRecords = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RejectedApplyRecord> rejectedApplies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LeaseView> opRecords = new ConcurrentHashMap<>();

    /** 原子记录首次申请结论；若该 requestId 已存在，则返回既有记录（当前记录被拒绝落库）。 */
    public IdempotentRecord putApplyRecordIfAbsent(String requestId, IdempotentRecord record) {
        IdempotentRecord existing = applyRecords.putIfAbsent(requestId, record);
        return existing != null ? existing : record;
    }

    public Optional<IdempotentRecord> findApplyRecord(String requestId) {
        return Optional.ofNullable(applyRecords.get(requestId));
    }

    /** 原子记录首次“未占用即拒绝”结论；若已存在则保留首次结论（拒绝同样按 requestId 幂等）。 */
    public RejectedApplyRecord putRejectedApplyIfAbsent(String requestId, RejectedApplyRecord record) {
        RejectedApplyRecord existing = rejectedApplies.putIfAbsent(requestId, record);
        return existing != null ? existing : record;
    }

    public Optional<RejectedApplyRecord> findRejectedApply(String requestId) {
        return Optional.ofNullable(rejectedApplies.get(requestId));
    }

    /** 原子记录首次续约/释放操作结论，重复 requestId 返回既有结论。 */
    public LeaseView putOpRecordIfAbsent(String requestId, LeaseView view) {
        return opRecords.putIfAbsent(requestId, view) == null
                ? view
                : opRecords.get(requestId);
    }

    public Optional<LeaseView> findOpRecord(String requestId) {
        return Optional.ofNullable(opRecords.get(requestId));
    }

    public void put(Lease lease) {
        leases.put(lease.getLeaseId(), lease);
    }

    public Optional<Lease> find(String leaseId) {
        return Optional.ofNullable(leases.get(leaseId));
    }

    public List<Lease> findAll() {
        return leases.values().stream()
                .sorted(Comparator.comparing(Lease::getLeaseId))
                .toList();
    }
}
