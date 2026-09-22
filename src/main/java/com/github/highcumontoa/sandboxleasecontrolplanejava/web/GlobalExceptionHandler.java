package com.github.highcumontoa.sandboxleasecontrolplanejava.web;

import com.github.highcumontoa.sandboxleasecontrolplanejava.error.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.error.ErrorResponse;
import com.github.highcumontoa.sandboxleasecontrolplanejava.error.LeaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * 把业务异常映射为可区分的 HTTP 响应；任何未预期异常都收敛为 INTERNAL_ERROR，
 * 不向调用方透出底层异常类型/堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<ErrorCode, HttpStatus> STATUS_MAP = new EnumMap<>(ErrorCode.class);

    static {
        STATUS_MAP.put(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST);
        STATUS_MAP.put(ErrorCode.LEASE_NOT_FOUND, HttpStatus.NOT_FOUND);
        STATUS_MAP.put(ErrorCode.QUOTA_EXHAUSTED, HttpStatus.CONFLICT);
        STATUS_MAP.put(ErrorCode.LEASE_EXPIRED, HttpStatus.CONFLICT);
        STATUS_MAP.put(ErrorCode.LEASE_RELEASED, HttpStatus.CONFLICT);
        STATUS_MAP.put(ErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT);
        STATUS_MAP.put(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, HttpStatus.CONFLICT);
        STATUS_MAP.put(ErrorCode.PROVISIONING_FAILED, HttpStatus.BAD_GATEWAY);
        STATUS_MAP.put(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(LeaseException.class)
    public ResponseEntity<ErrorResponse> handleLease(LeaseException ex) {
        HttpStatus status = STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return ResponseEntity.status(status)
                .body(new ErrorResponse(null, ex.getErrorCode(), ex.getMessage(), Instant.now(clock)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(null, ErrorCode.INVALID_REQUEST,
                        "malformed request body", Instant.now(clock)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        // 底层/未预期异常仅记录日志，对外统一归类，不原样透出
        log.error("[web] unclassified exception mapped to INTERNAL_ERROR: {}", ex.toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(null, ErrorCode.INTERNAL_ERROR,
                        "internal error", Instant.now(clock)));
    }
}
