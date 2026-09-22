package com.github.highcumontoa.sandboxleasecontrolplanejava.domain;

import java.time.Instant;

/**
 * 一条环境租约记录（进程内状态）。
 * 状态迁移必须在持有自身监视器锁的情况下进行（见 LeaseService）。
 */
public final class Lease {

    private final String leaseId;
    private final String requestId;
    private final String envType;
    private final String owner;
    /** 底层 provisioner 分配的环境句柄；终态回收时用于反注册。 */
    private final String envHandle;
    private volatile LeaseStatus status;
    private volatile Instant createdAt;
    private volatile Instant expiresAt;
    /** 进入终态（RELEASED / EXPIRED）的时间。 */
    private volatile Instant releasedAt;
    private volatile long version;
    /** true 表示该终态记录来自申请失败补偿（便于与超时回收区分与对账）。 */
    private volatile boolean compensated;

    public Lease(String leaseId, String requestId, String envType, String owner, String envHandle,
                 LeaseStatus status, Instant createdAt, Instant expiresAt, long version) {
        this.leaseId = leaseId;
        this.requestId = requestId;
        this.envType = envType;
        this.owner = owner;
        this.envHandle = envHandle;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.version = version;
    }

    /** 防御性拷贝：用于把“首次执行结论”的租约视图冻结为快照。 */
    public Lease(Lease other) {
        this.leaseId = other.leaseId;
        this.requestId = other.requestId;
        this.envType = other.envType;
        this.owner = other.owner;
        this.envHandle = other.envHandle;
        this.status = other.status;
        this.createdAt = other.createdAt;
        this.expiresAt = other.expiresAt;
        this.releasedAt = other.releasedAt;
        this.version = other.version;
        this.compensated = other.compensated;
    }

    public String getLeaseId() {
        return leaseId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getEnvType() {
        return envType;
    }

    public String getOwner() {
        return owner;
    }

    public String getEnvHandle() {
        return envHandle;
    }

    public LeaseStatus getStatus() {
        return status;
    }

    public void setStatus(LeaseStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public boolean isCompensated() {
        return compensated;
    }

    public void setCompensated(boolean compensated) {
        this.compensated = compensated;
    }

    /** 当前是否已过期（惰性回收判定依据）。 */
    public boolean isExpiredAt(Instant now) {
        return status == LeaseStatus.ACTIVE && !now.isBefore(expiresAt);
    }
}
