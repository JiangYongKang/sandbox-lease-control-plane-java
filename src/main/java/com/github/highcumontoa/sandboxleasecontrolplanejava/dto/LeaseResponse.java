package com.github.highcumontoa.sandboxleasecontrolplanejava.dto;

import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.Lease;
import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.LeaseStatus;

import java.time.Instant;

/**
 * 租约操作结果（申请/续约/释放/查询统一返回），并附带操作后的配额快照便于对账。
 */
public class LeaseResponse {

    private String leaseId;
    private String requestId;
    private String envType;
    private String owner;
    private LeaseStatus status;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant releasedAt;
    private long version;
    private boolean idempotentReplay;
    private int quotaCapacity;
    private int quotaOccupied;
    private int quotaAvailable;

    public static LeaseResponse from(Lease lease, boolean replay, int capacity, int occupied) {
        LeaseResponse r = new LeaseResponse();
        r.leaseId = lease.getLeaseId();
        r.requestId = lease.getRequestId();
        r.envType = lease.getEnvType();
        r.owner = lease.getOwner();
        r.status = lease.getStatus();
        r.createdAt = lease.getCreatedAt();
        r.expiresAt = lease.getExpiresAt();
        r.releasedAt = lease.getReleasedAt();
        r.version = lease.getVersion();
        r.idempotentReplay = replay;
        r.quotaCapacity = capacity;
        r.quotaOccupied = occupied;
        r.quotaAvailable = capacity - occupied;
        return r;
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

    public LeaseStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public long getVersion() {
        return version;
    }

    public boolean isIdempotentReplay() {
        return idempotentReplay;
    }

    public int getQuotaCapacity() {
        return quotaCapacity;
    }

    public int getQuotaOccupied() {
        return quotaOccupied;
    }

    public int getQuotaAvailable() {
        return quotaAvailable;
    }
}
