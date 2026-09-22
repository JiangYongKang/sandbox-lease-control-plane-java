package com.github.highcumontoa.sandboxleasecontrolplanejava.lease;

/**
 * 租约控制平面对外抛出的唯一异常类型。
 * 底层异常必须先被归类为某个 {@link ErrorCode}，不得原样透出。
 */
public class LeaseServiceException extends RuntimeException {

    private final ErrorCode code;

    public LeaseServiceException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public LeaseServiceException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
