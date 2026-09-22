package com.github.highcumontoa.sandboxleasecontrolplanejava.error;

/**
 * 业务异常：携带可区分错误码，供控制层映射为 HTTP 响应。
 */
public class LeaseException extends RuntimeException {

    private final ErrorCode errorCode;

    public LeaseException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LeaseException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
