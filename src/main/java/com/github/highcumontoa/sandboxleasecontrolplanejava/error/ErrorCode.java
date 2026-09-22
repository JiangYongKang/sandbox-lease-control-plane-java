package com.github.highcumontoa.sandboxleasecontrolplanejava.error;

/**
 * 对外可区分的错误码。底层异常不会原样透出，只会被归类到这里的某一种。
 */
public enum ErrorCode {
    QUOTA_EXHAUSTED,
    LEASE_NOT_FOUND,
    LEASE_EXPIRED,
    LEASE_RELEASED,
    ILLEGAL_STATE,
    INVALID_REQUEST,
    IDEMPOTENCY_KEY_CONFLICT,
    PROVISIONING_FAILED,
    INTERNAL_ERROR
}
