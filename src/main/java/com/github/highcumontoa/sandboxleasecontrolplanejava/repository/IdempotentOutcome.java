package com.github.highcumontoa.sandboxleasecontrolplanejava.repository;

import com.github.highcumontoa.sandboxleasecontrolplanejava.domain.Lease;
import com.github.highcumontoa.sandboxleasecontrolplanejava.error.ErrorCode;

/**
 * 某个 requestId 的首次执行结论快照，用于重复提交回放。
 * 成功时 leaseSnapshot 非空（冻结首次执行后的租约视图）；
 * 失败时 errorCode 非空，重复提交得到完全相同的失败结论，且不会再次占用资源。
 */
public final class IdempotentOutcome {

    private final String requestId;
    private final String fingerprint;
    private final boolean success;
    private final Lease leaseSnapshot;
    private final ErrorCode errorCode;
    private final String message;

    public IdempotentOutcome(String requestId, String fingerprint, boolean success,
                             Lease leaseSnapshot, ErrorCode errorCode, String message) {
        this.requestId = requestId;
        this.fingerprint = fingerprint;
        this.success = success;
        this.leaseSnapshot = leaseSnapshot;
        this.errorCode = errorCode;
        this.message = message;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public boolean isSuccess() {
        return success;
    }

    public Lease getLeaseSnapshot() {
        return leaseSnapshot;
    }

    /** 快照对应的 leaseId（成功路径）。 */
    public String getLeaseId() {
        return leaseSnapshot == null ? null : leaseSnapshot.getLeaseId();
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public static IdempotentOutcome success(String requestId, String fingerprint, Lease lease) {
        return new IdempotentOutcome(requestId, fingerprint, true, new Lease(lease), null, null);
    }

    public static IdempotentOutcome failure(String requestId, String fingerprint,
                                            ErrorCode errorCode, String message) {
        return new IdempotentOutcome(requestId, fingerprint, false, null, errorCode, message);
    }
}
