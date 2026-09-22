package com.github.highcumontoa.sandboxleasecontrolplanejava.lease;

/**
 * 对外错误码。每种业务拒绝/失败原因都有稳定且可区分的机器码。
 */
public enum ErrorCode {

    INVALID_REQUEST(400),
    IDEMPOTENCY_KEY_CONFLICT(409),
    LEASE_NOT_FOUND(404),
    QUOTA_EXHAUSTED(503),
    LEASE_EXPIRED(409),
    LEASE_NOT_ACTIVE(409),
    PROVISION_FAILED(502),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
