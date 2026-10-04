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
 * 进程内租约存储，由三张并发表构成：
 * <ul>
 *   <li>{@code leases}：leaseId -> 租约聚合（其状态变更由 LeaseService 的全局锁保护）；</li>
 *   <li>{@code applyRecords}：requestId -> 首次申请结论（申请幂等去重，
 *       成功/失败/被拒结论都落库，重复提交只能复现首次结论）；</li>
 *   <li>{@code opRecords}：requestId -> 续约/释放首次结论（操作幂等）。</li>
 * </ul>
 * 记录通过 putIfAbsent 原子落库，保证“先记录结论、再返回”，重复提交只能看到首次结论。
 */
@Component
public class LeaseStore {

    /**
     * 一次申请的幂等结论。
     *
     * <p>两种形态互斥：
     * <ul>
     *   <li>已落租约的结论（成功 ACTIVE 或补偿后 FAILED）：{@code leaseId/view} 非空、
     *       {@code rejectedWith} 为 null；</li>
     *   <li>准入即被拒的结论（团队额度超限 / 整池已满）：未创建任何租约，
     *       {@code leaseId/view} 为 null、{@code rejectedWith} 记录拒绝错误码，
     *       重复提交必须复现同一拒绝，不得在额度空出后悄悄成功。</li>
     * </ul>
     */
    public record IdempotentRecord(String leaseId, LeaseView view, ErrorCode rejectedWith) {

        /** 已落租约的结论（成功或补偿后失败）。 */
        public IdempotentRecord(String leaseId, LeaseView view) {
            this(leaseId, view, null);
        }

        /** 准入即被拒的结论。 */
        public static IdempotentRecord rejected(ErrorCode code) {
            return new IdempotentRecord(null, null, code);
        }
    }

    private final ConcurrentMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IdempotentRecord> applyRecords = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LeaseView> opRecords = new ConcurrentHashMap<>();

    /** 原子记录首次申请结论；若该 requestId 已存在，则返回既有记录（当前记录被拒绝落库）。 */
    public IdempotentRecord putApplyRecordIfAbsent(String requestId, IdempotentRecord record) {
        IdempotentRecord existing = applyRecords.putIfAbsent(requestId, record);
        return existing != null ? existing : record;
    }

    public Optional<IdempotentRecord> findApplyRecord(String requestId) {
        return Optional.ofNullable(applyRecords.get(requestId));
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
