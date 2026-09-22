package com.github.highcumontoa.sandboxleasecontrolplanejava.error;

import java.time.Instant;

/**
 * 统一错误响应体。
 */
public class ErrorResponse {

    private final String requestId;
    private final ErrorCode errorCode;
    private final String message;
    private final Instant timestamp;

    public ErrorResponse(String requestId, ErrorCode errorCode, String message, Instant timestamp) {
        this.requestId = requestId;
        this.errorCode = errorCode;
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getRequestId() {
        return requestId;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
