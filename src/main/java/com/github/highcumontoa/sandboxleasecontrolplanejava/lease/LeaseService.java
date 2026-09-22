package com.github.highcumontoa.sandboxleasecontrolplanejava.lease;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.QuotaView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.ReleaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.RenewRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.domain.Lease;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.pool.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision.SandboxProvisionException;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision.SandboxProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.store.LeaseStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 租约控制平面核心服务。
 *
 * <p>所有状态变更都在同一把公平锁内串行完成，保证：
 * <ul>
 *   <li>并发抢占时资源占用不超上限（资源池 CAS 之外再做状态串行化）；</li>
 *   <li>同一租约不会被重复释放、不会同时处于相互冲突的状态；</li>
 *   <li>自动回收与主动释放竞争时，最终状态与资源余额唯一确定；</li>
 *   <li>申请链路任何失败都先补偿配额再落 FAILED，不泄漏配额、不留悬挂租约。</li>
 * </ul>
 */
@Service
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final LeaseStore store;
    private final ResourcePool pool;
    private final SandboxProvisioner provisioner;
    private final Clock clock;
    private final LeaseProperties properties;

    /** 公平锁：所有写操作按到达顺序串行化，结论可复现。 */
    private final ReentrantLock lock = new ReentrantLock(true);

    public LeaseService(LeaseStore store, ResourcePool pool, SandboxProvisioner provisioner,
                        Clock clock, LeaseProperties properties) {
        this.store = store;
        this.pool = pool;
        this.provisioner = provisioner;
        this.clock = clock;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- 申请

    public LeaseView apply(LeaseRequest request) {
        validateApply(request);
        String requestId = request.requestId();
        String tenantId = request.tenantId();
        long ttl = normalizeTtl(request.ttlSeconds());

        lock.lock();
        try {
            // 1) 幂等：同一 requestId 的重复提交必须得到同一结果，且不得重复占用资源。
            var existing = store.findApplyRecord(requestId);
            if (existing.isPresent()) {
                LeaseView view = existing.get().view();
                log.info("apply idempotent hit: requestId={}, leaseId={}, status={}",
                        requestId, existing.get().leaseId(), view.status());
                // 首次结论为失败时，重复提交必须复现同一失败结论，而不是在第二次悄悄成功。
                if (LeaseStatus.FAILED.name().equals(view.status())) {
                    throw new LeaseServiceException(ErrorCode.PROVISION_FAILED,
                            "sandbox provision failed, quota has been compensated");
                }
                return view;
            }

            long now = clock.millis();
            String leaseId = "lease-" + UUID.randomUUID();

            // 2) 先预留配额；不足则明确拒绝（此时没有任何已落库状态，无需补偿）。
            if (!pool.tryAcquire()) {
                log.warn("apply rejected quota exhausted: requestId={}, leaseId={}, available=0/{}",
                        requestId, leaseId, pool.capacity());
                throw new LeaseServiceException(ErrorCode.QUOTA_EXHAUSTED,
                        "no available sandbox slot, capacity=" + pool.capacity());
            }

            // 3) 配额预留成功 -> 创建 PENDING 租约并落库（不会遗留“无主”资源）。
            Lease lease = Lease.pending(leaseId, requestId, tenantId, now, now + ttl * 1000);
            store.put(lease);
            log.info("state transition: requestId={}, leaseId={}, status null -> PENDING, expiresAt={}",
                    requestId, leaseId, lease.getExpiresAtMillis());

            // 4) 申请底层沙箱；任何失败都归类为可区分错误，并执行补偿。
            String resourceId;
            try {
                resourceId = provisioner.provision(leaseId, tenantId);
            } catch (SandboxProvisionException ex) {
                compensateFailedProvision(requestId, lease, ex);
                throw new LeaseServiceException(ErrorCode.PROVISION_FAILED,
                        "sandbox provision failed, quota has been compensated", ex);
            } catch (RuntimeException ex) {
                compensateFailedProvision(requestId, lease, ex);
                throw new LeaseServiceException(ErrorCode.INTERNAL_ERROR,
                        "unexpected error during provision, quota has been compensated", ex);
            }

            // 5) 成功：绑定资源、迁移到 ACTIVE。
            lease.setResourceId(resourceId);
            lease.transitionTo(LeaseStatus.ACTIVE);
            LeaseView view = snapshot(lease);
            persistApplyResult(requestId, leaseId, view);
            log.info("state transition: requestId={}, leaseId={}, status PENDING -> ACTIVE, resourceId={}, available={}",
                    requestId, leaseId, resourceId, pool.available());
            return view;
        } finally {
            lock.unlock();
        }
    }

    /** 失败补偿：先归还配额，再把 PENDING 迁移到 FAILED，保证余额与状态唯一确定。 */
    private void compensateFailedProvision(String requestId, Lease lease, Exception cause) {
        pool.release();
        lease.setFailedReason(cause.getClass().getSimpleName());
        lease.setEndedAtMillis(clock.millis());
        lease.transitionTo(LeaseStatus.FAILED);
        LeaseView failedView = snapshot(lease);
        persistApplyResult(requestId, lease.getLeaseId(), failedView);
        log.warn("compensation done: requestId={}, leaseId={}, status PENDING -> FAILED, reason={}, available={}",
                requestId, lease.getLeaseId(), cause.getClass().getSimpleName(), pool.available());
    }

    private void persistApplyResult(String requestId, String leaseId, LeaseView view) {
        store.putApplyRecordIfAbsent(requestId, new LeaseStore.IdempotentRecord(leaseId, view));
    }

    // ---------------------------------------------------------------- 续约

    public LeaseView renew(String leaseId, RenewRequest request) {
        validateRenew(request);
        String requestId = request.requestId();
        long ttl = normalizeTtl(request.ttlSeconds());

        lock.lock();
        try {
            var opExisting = store.findOpRecord(requestId);
            if (opExisting.isPresent()) {
                log.info("renew idempotent hit: requestId={}, leaseId={}, status={}",
                        requestId, leaseId, opExisting.get().leaseId(), opExisting.get().status());
                return opExisting.get();
            }

            Lease lease = requireLease(leaseId);

            // requestId 若曾用于其它租约的申请/操作，属于冲突而非静默复用。
            var applyRecord = store.findApplyRecord(requestId);
            if (applyRecord.isPresent() && !applyRecord.get().leaseId().equals(leaseId)) {
                throw new LeaseServiceException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                        "requestId " + requestId + " already used by another lease");
            }

            LeaseStatus before = lease.getStatus();
            expireIfNeeded(lease, "renew");

            if (lease.getStatus() == LeaseStatus.EXPIRED) {
                throw new LeaseServiceException(ErrorCode.LEASE_EXPIRED,
                        "lease " + leaseId + " already expired, cannot renew");
            }
            if (lease.getStatus() != LeaseStatus.ACTIVE) {
                throw new LeaseServiceException(ErrorCode.LEASE_NOT_ACTIVE,
                        "lease " + leaseId + " is " + lease.getStatus() + ", only ACTIVE can renew");
            }

            lease.extendExpiry(clock.millis() + ttl * 1000);
            LeaseView view = snapshot(lease);
            LeaseView stored = store.putOpRecordIfAbsent(requestId, view);
            log.info("renew done: requestId={}, leaseId={}, status remains {}, expiresAt={}, available={}",
                    requestId, leaseId, before, lease.getExpiresAtMillis(), pool.available());
            return stored;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- 释放

    public LeaseView release(String leaseId, ReleaseRequest request) {
        String requestId = request == null ? null : request.requestId();

        lock.lock();
        try {
            if (requestId != null) {
                var opExisting = store.findOpRecord(requestId);
                if (opExisting.isPresent()) {
                    log.info("release idempotent hit: requestId={}, leaseId={}, status={}",
                            requestId, leaseId, opExisting.get().leaseId(), opExisting.get().status());
                    return opExisting.get();
                }
            }

            Lease lease = requireLease(leaseId);

            // 自动回收与主动释放竞争：谁先拿到锁谁终结租约，另一方看到终态后返回同一结论。
            expireIfNeeded(lease, "release");

            LeaseStatus status = lease.getStatus();
            if (status == LeaseStatus.RELEASED || status == LeaseStatus.EXPIRED) {
                LeaseView view = snapshot(lease);
                if (requestId != null) {
                    view = store.putOpRecordIfAbsent(requestId, view);
                }
                log.info("release on terminal lease accepted: requestId={}, leaseId={}, status={}",
                        requestId, leaseId, status);
                return view;
            }
            if (status == LeaseStatus.FAILED) {
                throw new LeaseServiceException(ErrorCode.LEASE_NOT_ACTIVE,
                        "lease " + leaseId + " failed during application, nothing to release");
            }
            if (status == LeaseStatus.RELEASING) {
                throw new LeaseServiceException(ErrorCode.LEASE_NOT_ACTIVE,
                        "lease " + leaseId + " is already releasing");
            }

            // PENDING（供应尚未返回）或 ACTIVE：回收底层资源（尽力而为）并归还配额。
            String resourceId = lease.getResourceId();
            lease.transitionTo(LeaseStatus.RELEASING);
            log.info("state transition: requestId={}, leaseId={}, status {} -> RELEASING",
                    requestId, leaseId, status);
            try {
                provisioner.deprovision(resourceId);
            } catch (RuntimeException ex) {
                log.error("deprovision failed but release proceeds: leaseId={}, resourceId={}",
                        leaseId, resourceId, ex);
            }
            pool.release();
            lease.setEndedAtMillis(clock.millis());
            lease.transitionTo(LeaseStatus.RELEASED);
            LeaseView view = snapshot(lease);
            if (requestId != null) {
                view = store.putOpRecordIfAbsent(requestId, view);
            }
            log.info("state transition: requestId={}, leaseId={}, status RELEASING -> RELEASED, available={}",
                    requestId, leaseId, pool.available());
            return view;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- 查询 / 配额

    public LeaseView get(String leaseId) {
        lock.lock();
        try {
            Lease lease = requireLease(leaseId);
            expireIfNeeded(lease, "query");
            return snapshot(lease);
        } finally {
            lock.unlock();
        }
    }

    public QuotaView quota() {
        lock.lock();
        try {
            return new QuotaView(pool.capacity(), pool.available(), pool.leased());
        } finally {
            lock.unlock();
        }
    }

    /** 扫描并回收已到期租约，返回本次回收数量。与主动释放共用同一把锁。 */
    public int reapExpired() {
        lock.lock();
        try {
            int reaped = 0;
            List<Lease> leases = store.findAll();
            for (Lease lease : leases) {
                if (!lease.isExpiredAt(clock.millis())) {
                    continue;
                }
                String resourceId = lease.getResourceId();
                LeaseStatus before = lease.getStatus();
                try {
                    provisioner.deprovision(resourceId);
                } catch (RuntimeException ex) {
                    log.error("deprovision failed during reap but expiry proceeds: leaseId={}",
                            lease.getLeaseId(), ex);
                }
                pool.release();
                lease.setEndedAtMillis(clock.millis());
                lease.transitionTo(LeaseStatus.EXPIRED);
                reaped++;
                log.info("state transition (auto-reap): leaseId={}, status {} -> EXPIRED, available={}",
                        lease.getLeaseId(), before, pool.available());
            }
            if (reaped > 0) {
                log.info("auto-reap finished: reaped={}, available={}", reaped, pool.available());
            }
            return reaped;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- 内部方法

    /** 惰性到期判定：到达时间的非终态租约在此终结，保证任何入口看到的结论一致。 */
    private void expireIfNeeded(Lease lease, String scene) {
        if (lease.isExpiredAt(clock.millis())) {
            LeaseStatus before = lease.getStatus();
            try {
                provisioner.deprovision(lease.getResourceId());
            } catch (RuntimeException ex) {
                log.error("deprovision failed during lazy expiry but expiry proceeds: leaseId={}",
                        lease.getLeaseId(), ex);
            }
            pool.release();
            lease.setEndedAtMillis(clock.millis());
            lease.transitionTo(LeaseStatus.EXPIRED);
            log.info("state transition (lazy-expiry in {}): requestId={}, leaseId={}, status {} -> EXPIRED, available={}",
                    scene, lease.getRequestId(), lease.getLeaseId(), before, pool.available());
        }
    }

    private Lease requireLease(String leaseId) {
        return store.find(leaseId)
                .orElseThrow(() -> new LeaseServiceException(ErrorCode.LEASE_NOT_FOUND,
                        "lease " + leaseId + " not found"));
    }

    private long normalizeTtl(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return properties.getDefaultTtlSeconds();
        }
        return Math.min(ttlSeconds, properties.getMaxTtlSeconds());
    }

    private void validateApply(LeaseRequest request) {
        if (request == null) {
            throw new LeaseServiceException(ErrorCode.INVALID_REQUEST, "request body is required");
        }
        if (isBlank(request.requestId())) {
            throw new LeaseServiceException(ErrorCode.INVALID_REQUEST, "requestId is required");
        }
        if (isBlank(request.tenantId())) {
            throw new LeaseServiceException(ErrorCode.INVALID_REQUEST, "tenantId is required");
        }
        if (request.ttlSeconds() < 0) {
            throw new LeaseServiceException(ErrorCode.INVALID_REQUEST, "ttlSeconds must be positive or zero");
        }
    }

    private void validateRenew(RenewRequest request) {
        if (request == null || isBlank(request.requestId())) {
            throw new LeaseServiceException(ErrorCode.INVALID_REQUEST, "requestId is required");
        }
        if (request.ttlSeconds() <= 0) {
            throw new LeaseServiceException(ErrorCode.INVALID_REQUEST, "ttlSeconds must be positive");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static LeaseView snapshot(Lease lease) {
        return new LeaseView(
                lease.getLeaseId(),
                lease.getRequestId(),
                lease.getTenantId(),
                lease.getStatus().name(),
                lease.getResourceId(),
                lease.getCreatedAtMillis(),
                lease.getExpiresAtMillis(),
                lease.getEndedAtMillis(),
                lease.getFailedReason());
    }
}
