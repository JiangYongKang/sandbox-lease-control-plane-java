package com.github.highcumontoa.sandboxleasecontrolplanejava.service;

import com.github.highcumontoa.sandboxleasecontrolplanejava.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.Lease;
import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.LeaseStatus;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.AcquireRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.LeaseResponse;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.PoolStatusResponse;
import com.github.highcumontoa.sandboxleasecontrolplanejava.error.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.error.LeaseException;
import com.github.highcumontoa.sandboxleasecontrolplanejava.provision.EnvironmentProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.provision.InMemoryProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.quota.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.repository.IdempotentOutcome;
import com.github.highcumontoa.sandboxleasecontrolplanejava.repository.LeaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 租约控制平面核心服务。
 *
 * 一致性模型：
 * - 单一 ReentrantLock 串行化所有写操作（申请/续约/释放/回收），
 *   配合 ResourcePool 的 CAS，保证并发抢占不超发、状态迁移不冲突；
 * - 以 requestId 记录首次执行结论，重复提交直接回放，绝不二次占用配额；
 * - 申请链路按“校验 -> 预留配额 -> 建租约 -> provision”推进，任一步失败按反向顺序补偿。
 */
@Service
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final LeaseStore store;
    private final ResourcePool pool;
    private final EnvironmentProvisioner provisioner;
    private final LeaseProperties properties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public LeaseService(LeaseStore store, ResourcePool pool, EnvironmentProvisioner provisioner,
                        LeaseProperties properties, Clock clock) {
        this.store = store;
        this.pool = pool;
        this.provisioner = provisioner;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 申请
    // ------------------------------------------------------------------

    public LeaseResponse acquire(AcquireRequest request) {
        String requestId = requireRequestId(request.getRequestId());
        String envType = requireText(request.getEnvType(), "envType");
        String owner = requireText(request.getOwner(), "owner");
        long ttlSeconds = resolveTtl(request.getTtlSeconds());
        String fingerprint = acquireFingerprint(envType, owner, ttlSeconds,
                request.isSimulateProvisioningFailure());

        lock.lock();
        try {
            // 1) 幂等：同 requestId 直接回放首次结论
            IdempotentOutcome existing = store.findOutcome(requestId).orElse(null);
            if (existing != null) {
                return replayAcquire(requestId, existing, fingerprint);
            }

            Instant now = Instant.now(clock);

            // 2) 预留配额（CAS，容量不足明确拒绝）
            if (!pool.reserve()) {
                log.info("[lease] acquire rejected: requestId={} reason=QUOTA_EXHAUSTED occupied={}/{}",
                        requestId, pool.getOccupied(), pool.getCapacity());
                throw failureOutcome(requestId, fingerprint, ErrorCode.QUOTA_EXHAUSTED,
                        "resource pool exhausted, capacity=" + pool.getCapacity());
            }

            // 3) 创建 ACTIVE 租约（先落库，保证任何失败都不会遗留悬挂租约：
            //    后续失败时该记录会被显式补偿清理）
            String leaseId = "lease-" + UUID.randomUUID();
            Lease lease;
            try {
                lease = new Lease(leaseId, requestId, envType, owner, null,
                        LeaseStatus.ACTIVE, now, now.plusSeconds(ttlSeconds), 0);
                store.save(lease);
                log.info("[lease] state transition: lease={} requestId={} (none)->ACTIVE quota={}/{}",
                        leaseId, requestId, pool.getOccupied(), pool.getCapacity());
            } catch (RuntimeException ex) {
                pool.release();
                throw classify(ex, requestId, ErrorCode.INTERNAL_ERROR, "lease persistence failed");
            }

            // 4) 调用底层 provisioner 分配环境；失败时执行补偿：反注册句柄（如有）
            //    -> 终结租约记录（EXPIRED 补偿态，保留可追溯）-> 归还配额
            String handle;
            try {
                handle = provisioner.provision(leaseId, envType, request.isSimulateProvisioningFailure());
            } catch (RuntimeException ex) {
                compensateFailedAcquire(lease, ex, requestId);
                throw failureOutcome(requestId, fingerprint, ErrorCode.PROVISIONING_FAILED,
                        "environment provisioning failed, quota compensated");
            }

            // 正常路径：envHandle 为 final，用一次带句柄的重存更新记录
            Lease active = new Lease(leaseId, requestId, envType, owner, handle,
                    LeaseStatus.ACTIVE, now, now.plusSeconds(ttlSeconds), 0);
            store.save(active);

            IdempotentOutcome outcome = IdempotentOutcome.success(requestId, fingerprint, active);
            store.saveOutcome(outcome);
            log.info("[lease] acquire completed: lease={} requestId={} quota={}/{} available={}",
                    leaseId, requestId, pool.getOccupied(), pool.getCapacity(), pool.getAvailable());
            return toResponse(active, false);
        } finally {
            lock.unlock();
        }
    }

    private void compensateFailedAcquire(Lease lease, RuntimeException cause, String requestId) {
        // 补偿顺序与申请顺序严格相反。补偿本身不允许再抛出对外异常。
        log.warn("[lease] acquire failed, compensating: lease={} requestId={} cause={}",
                lease.getLeaseId(), requestId, cause.toString());
        try {
            if (lease.getEnvHandle() != null) {
                provisioner.deprovision(lease.getEnvHandle());
            }
        } catch (RuntimeException compensationEx) {
            log.error("[lease] compensation deprovision failed (ignored): lease={} cause={}",
                    lease.getLeaseId(), compensationEx.toString());
        }
        try {
            synchronized (lease) {
                lease.setStatus(LeaseStatus.EXPIRED);
                lease.setReleasedAt(Instant.now(clock));
                lease.setVersion(lease.getVersion() + 1);
                lease.setCompensated(true);
            }
            // 用补偿后的视图覆盖仓储（envHandle 为 null，不会被重复反注册）
            store.save(lease);
            log.info("[lease] state transition: lease={} requestId={} ACTIVE->EXPIRED(compensated) quota={}/{}",
                    lease.getLeaseId(), requestId, pool.getOccupied(), pool.getCapacity());
        } catch (RuntimeException compensationEx) {
            log.error("[lease] compensation lease-finalize failed: lease={} cause={}",
                    lease.getLeaseId(), compensationEx.toString());
        }
        pool.release();
        log.info("[lease] quota refunded after compensation: requestId={} quota={}/{} available={}",
                requestId, pool.getOccupied(), pool.getCapacity(), pool.getAvailable());
    }

    private LeaseResponse replayAcquire(String requestId, IdempotentOutcome existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            // 幂等键被复用于参数不同的请求：明确拒绝，不改动既有结论
            throw new LeaseException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "requestId already used with a different request payload: " + requestId);
        }
        if (!existing.isSuccess()) {
            log.info("[lease] acquire replay (same failure): requestId={} errorCode={}",
                    requestId, existing.getErrorCode());
            throw new LeaseException(existing.getErrorCode(), existing.getMessage());
        }
        Lease snapshot = existing.getLeaseSnapshot();
        Lease current = store.find(snapshot.getLeaseId())
                .orElseThrow(() -> new LeaseException(ErrorCode.INTERNAL_ERROR,
                        "inconsistent state: lease missing for requestId " + requestId));
        log.info("[lease] acquire replay (same result): requestId={} lease={} status={}",
                requestId, current.getLeaseId(), current.getStatus());
        return toResponse(current, true);
    }

    // ------------------------------------------------------------------
    // 续约
    // ------------------------------------------------------------------

    public LeaseResponse renew(String leaseId, String requestId, Long ttlSeconds) {
        String rid = requireRequestId(requestId);
        long ttl = resolveTtl(ttlSeconds);
        String fingerprint = renewFingerprint(leaseId, ttl);

        lock.lock();
        try {
            IdempotentOutcome existing = store.findOutcome(rid).orElse(null);
            if (existing != null) {
                return replayOnLease(rid, existing, leaseId, fingerprint);
            }

            Lease lease = store.getRequired(leaseId);
            Instant now = Instant.now(clock);
            synchronized (lease) {
                if (lease.isExpiredAt(now)) {
                    return expiredReject(rid, lease, ttl);
                }
                if (lease.getStatus() == LeaseStatus.RELEASED) {
                    return terminalReject(rid, fingerprint, lease, ErrorCode.LEASE_RELEASED,
                            "lease already released: " + leaseId);
                }
                if (lease.getStatus() != LeaseStatus.ACTIVE) {
                    return terminalReject(rid, fingerprint, lease, ErrorCode.LEASE_EXPIRED,
                            "lease not active: " + leaseId);
                }
                Instant oldExpiry = lease.getExpiresAt();
                // 续约在“当前时刻 + ttl”与“原到期 + ttl”中取较晚者，且钳制最大时长
                Instant base = oldExpiry.isAfter(now) ? oldExpiry : now;
                Instant newExpiry = base.plusSeconds(ttl);
                Instant capped = now.plusSeconds(properties.getMaxTtlSeconds());
                if (newExpiry.isAfter(capped)) {
                    newExpiry = capped;
                }
                lease.setExpiresAt(newExpiry);
                lease.setVersion(lease.getVersion() + 1);
                log.info("[lease] state transition: lease={} requestId={} ACTIVE->ACTIVE(renewed) expiry {}->{} v={}",
                        leaseId, rid, oldExpiry, newExpiry, lease.getVersion());
            }
            store.saveOutcome(IdempotentOutcome.success(rid, renewFingerprint(leaseId, ttl), lease));
            return toResponse(lease, false);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 释放
    // ------------------------------------------------------------------

    public LeaseResponse release(String leaseId, String requestId) {
        String rid = requireRequestId(requestId);
        String fingerprint = releaseFingerprint(leaseId);

        lock.lock();
        try {
            IdempotentOutcome existing = store.findOutcome(rid).orElse(null);
            if (existing != null) {
                return replayOnLease(rid, existing, leaseId, fingerprint);
            }

            Lease lease = store.getRequired(leaseId);
            Instant now = Instant.now(clock);
            synchronized (lease) {
                // 竞争点：只要租约仍是 ACTIVE（即使已超过 expiresAt 但回收线程尚未终结），
                // 主动释放先拿到锁则由释放终结为 RELEASED；回收先拿到锁则状态已是 EXPIRED。
                // 终态与余额由本临界区唯一裁决，绝不允许重复终结。
                if (lease.getStatus() == LeaseStatus.RELEASED) {
                    return terminalReject(rid, fingerprint, lease, ErrorCode.LEASE_RELEASED,
                            "lease already released: " + leaseId);
                }
                if (lease.getStatus() == LeaseStatus.EXPIRED) {
                    return terminalReject(rid, fingerprint, lease, ErrorCode.LEASE_EXPIRED,
                            "lease already expired: " + leaseId);
                }

                // 唯一的 ACTIVE -> RELEASED 迁移路径
                lease.setStatus(LeaseStatus.RELEASED);
                lease.setReleasedAt(now);
                lease.setVersion(lease.getVersion() + 1);
                log.info("[lease] state transition: lease={} requestId={} ACTIVE->RELEASED v={}",
                        leaseId, rid, lease.getVersion());
            }
            // 反注册底层环境（幂等），然后归还配额；顺序失败不影响最终一致性
            safeDeprovision(lease);
            pool.release();
            log.info("[lease] released: lease={} requestId={} quota={}/{} available={}",
                    leaseId, rid, pool.getOccupied(), pool.getCapacity(), pool.getAvailable());
            store.saveOutcome(IdempotentOutcome.success(rid, releaseFingerprint(leaseId), lease));
            return toResponse(lease, false);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public LeaseResponse get(String leaseId) {
        lock.lock();
        try {
            Lease lease = store.getRequired(leaseId);
            Instant now = Instant.now(clock);
            synchronized (lease) {
                if (lease.isExpiredAt(now)) {
                    finalizeExpired(lease, now, "lazy-expire on query");
                }
            }
            // 查询无副作用回放：同一租约重复查询结论可复现（状态/配额不再变化）
            return toResponse(lease, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 回收一条已过期的 ACTIVE 租约（惰性回收与定时回收共用）。
     * @return true 表示本次调用实际完成了回收
     */
    public boolean reclaimIfExpired(Lease lease) {
        lock.lock();
        try {
            Instant now = Instant.now(clock);
            synchronized (lease) {
                if (lease.getStatus() != LeaseStatus.ACTIVE || !lease.isExpiredAt(now)) {
                    return false;
                }
                finalizeExpired(lease, now, "auto-reclaim");
            }
            safeDeprovision(lease);
            pool.release();
            log.info("[lease] auto-reclaimed: lease={} quota={}/{} available={}",
                    lease.getLeaseId(), pool.getOccupied(), pool.getCapacity(), pool.getAvailable());
            return true;
        } finally {
            lock.unlock();
        }
    }

    public PoolStatusResponse poolStatus() {
        lock.lock();
        try {
            long active = store.all().stream()
                    .filter(l -> l.getStatus() == LeaseStatus.ACTIVE)
                    .count();
            return new PoolStatusResponse(pool.getCapacity(), pool.getOccupied(), (int) active);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private void finalizeExpired(Lease lease, Instant now, String reason) {
        // 调用方必须已持有 this.lock 与 lease 监视器
        lease.setStatus(LeaseStatus.EXPIRED);
        lease.setReleasedAt(now);
        lease.setVersion(lease.getVersion() + 1);
        log.info("[lease] state transition: lease={} ACTIVE->EXPIRED reason={} v={}",
                lease.getLeaseId(), reason, lease.getVersion());
        safeDeprovision(lease);
        pool.release();
        log.info("[lease] quota refunded on expiry: lease={} quota={}/{} available={}",
                lease.getLeaseId(), pool.getOccupied(), pool.getCapacity(), pool.getAvailable());
    }

    private void safeDeprovision(Lease lease) {
        if (lease.getEnvHandle() == null) {
            return;
        }
        try {
            provisioner.deprovision(lease.getEnvHandle());
        } catch (RuntimeException ex) {
            // 底层反注册失败不得影响状态/配额的唯一终态；句柄反注册本身幂等
            log.error("[lease] deprovision failed (state/quota still finalized): lease={} cause={}",
                    lease.getLeaseId(), ex.toString());
        }
    }

    private LeaseResponse replayOnLease(String requestId, IdempotentOutcome existing,
                                        String leaseId, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new LeaseException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "requestId already used with a different operation: " + requestId);
        }
        Lease snapshot = existing.getLeaseSnapshot();
        if (snapshot == null) {
            log.info("[lease] replay (same failure): requestId={} errorCode={}",
                    requestId, existing.getErrorCode());
            throw new LeaseException(existing.getErrorCode(), existing.getMessage());
        }
        if (!snapshot.getLeaseId().equals(leaseId)) {
            throw new LeaseException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "requestId bound to a different lease: " + requestId);
        }
        Lease current = store.getRequired(leaseId);
        log.info("[lease] replay (same result): requestId={} lease={} snapshotStatus={} currentStatus={}",
                requestId, leaseId, snapshot.getStatus(), current.getStatus());
        // 重复操作返回首次结论快照，保证与第一次完全一致（例如首次 RENEWED 后租约又过期，
        // 重复提交续约仍得到首次成功时的结论），不会再次推进状态或扣配额
        return toResponse(snapshot, true);
    }

    private LeaseResponse terminalReject(String requestId, String fingerprint, Lease lease,
                                         ErrorCode code, String message) {
        store.saveOutcome(IdempotentOutcome.failure(requestId, fingerprint, code, message));
        log.info("[lease] operation rejected: lease={} requestId={} reason={}",
                lease.getLeaseId(), requestId, code);
        throw new LeaseException(code, message);
    }

    private LeaseResponse expiredReject(String requestId, Lease lease, long ttl) {
        Instant now = Instant.now(clock);
        finalizeExpired(lease, now, "lazy-expire on renew request " + requestId);
        return terminalReject(requestId, renewFingerprint(lease.getLeaseId(), ttl),
                lease, ErrorCode.LEASE_EXPIRED,
                "lease already expired at renew: " + lease.getLeaseId());
    }

    private LeaseException failureOutcome(String requestId, String fingerprint,
                                          ErrorCode code, String message) {
        store.saveOutcome(IdempotentOutcome.failure(requestId, fingerprint, code, message));
        return new LeaseException(code, message);
    }

    private LeaseResponse toResponse(Lease lease, boolean replay) {
        return LeaseResponse.from(lease, replay, pool.getCapacity(), pool.getOccupied());
    }

    private LeaseException classify(RuntimeException ex, String requestId,
                                    ErrorCode fallback, String message) {
        if (ex instanceof LeaseException leaseEx) {
            return leaseEx;
        }
        if (ex instanceof InMemoryProvisioner.ProvisionerInfraException) {
            return new LeaseException(ErrorCode.PROVISIONING_FAILED, message, ex);
        }
        // 底层异常不原样透出，统一归类
        log.error("[lease] unexpected error classified: requestId={} cause={}", requestId, ex.toString());
        return new LeaseException(fallback, message, ex);
    }

    private String requireRequestId(String requestId) {
        return requireText(requestId, "requestId");
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new LeaseException(ErrorCode.INVALID_REQUEST, field + " must not be blank");
        }
        return value.trim();
    }

    private long resolveTtl(Long ttlSeconds) {
        long ttl = ttlSeconds == null ? properties.getDefaultTtlSeconds() : ttlSeconds;
        if (ttl <= 0) {
            throw new LeaseException(ErrorCode.INVALID_REQUEST, "ttlSeconds must be positive");
        }
        if (ttl > properties.getMaxTtlSeconds()) {
            throw new LeaseException(ErrorCode.INVALID_REQUEST,
                    "ttlSeconds exceeds max " + properties.getMaxTtlSeconds());
        }
        return ttl;
    }

    private String acquireFingerprint(String envType, String owner, long ttlSeconds, boolean failInjected) {
        return "acquire|" + envType + "|" + owner + "|" + ttlSeconds + "|fail=" + failInjected;
    }

    private String renewFingerprint(String leaseId, long ttlSeconds) {
        return "renew|" + leaseId + "|" + ttlSeconds;
    }

    private String releaseFingerprint(String leaseId) {
        return "release|" + leaseId;
    }

}
