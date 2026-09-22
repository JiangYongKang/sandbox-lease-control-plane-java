package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.domain;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 租约聚合根。
 *
 * <p>状态迁移只能通过 {@link #transitionTo} 进行，统一校验合法迁移并记录轨迹，
 * 保证同一租约不会同时处于相互冲突的状态，终态不可再变更。
 *
 * <pre>
 * PENDING  -> ACTIVE | FAILED
 * ACTIVE   -> RELEASING | EXPIRED
 * RELEASING-> RELEASED
 * RELEASED / EXPIRED / FAILED 为终态
 * </pre>
 */
public class Lease {

    private static final Map<LeaseStatus, Set<LeaseStatus>> ALLOWED = new EnumMap<>(LeaseStatus.class);

    static {
        ALLOWED.put(LeaseStatus.PENDING, EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.FAILED));
        ALLOWED.put(LeaseStatus.ACTIVE, EnumSet.of(LeaseStatus.RELEASING, LeaseStatus.EXPIRED));
        ALLOWED.put(LeaseStatus.RELEASING, EnumSet.of(LeaseStatus.RELEASED));
        ALLOWED.put(LeaseStatus.RELEASED, EnumSet.noneOf(LeaseStatus.class));
        ALLOWED.put(LeaseStatus.EXPIRED, EnumSet.noneOf(LeaseStatus.class));
        ALLOWED.put(LeaseStatus.FAILED, EnumSet.noneOf(LeaseStatus.class));
    }

    private final String leaseId;
    private final String requestId;
    private final String tenantId;
    private final long createdAtMillis;

    private LeaseStatus status;
    private String resourceId;
    private long expiresAtMillis;
    private long endedAtMillis;
    private String failedReason;

    private Lease(String leaseId, String requestId, String tenantId, long createdAtMillis, long expiresAtMillis) {
        this.leaseId = leaseId;
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.status = LeaseStatus.PENDING;
    }

    /** 创建一个处于 PENDING 的租约：此时资源槽已被预留。 */
    public static Lease pending(String leaseId, String requestId, String tenantId,
                                long nowMillis, long expiresAtMillis) {
        return new Lease(leaseId, requestId, tenantId, nowMillis, expiresAtMillis);
    }

    /** 状态迁移守卫：仅允许状态机中定义的合法迁移，终态不可再变更。 */
    public void transitionTo(LeaseStatus target) {
        LeaseStatus current = this.status;
        if (current == target) {
            throw new IllegalStateException("lease " + leaseId + " is already in status " + target);
        }
        Set<LeaseStatus> allowed = ALLOWED.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new IllegalStateException("illegal lease status transition for " + leaseId
                    + ": " + current + " -> " + target);
        }
        this.status = target;
    }

    /** 续约成功：仅推进到期时间，不改变状态。 */
    public void extendExpiry(long newExpiresAtMillis) {
        this.expiresAtMillis = Math.max(this.expiresAtMillis, newExpiresAtMillis);
    }

    public boolean isExpiredAt(long nowMillis) {
        return !status.isTerminal() && nowMillis >= expiresAtMillis;
    }

    public String getLeaseId() {
        return leaseId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public LeaseStatus getStatus() {
        return status;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public long getEndedAtMillis() {
        return endedAtMillis;
    }

    public void setEndedAtMillis(long endedAtMillis) {
        this.endedAtMillis = endedAtMillis;
    }

    public String getFailedReason() {
        return failedReason;
    }

    public void setFailedReason(String failedReason) {
        this.failedReason = failedReason;
    }
}
