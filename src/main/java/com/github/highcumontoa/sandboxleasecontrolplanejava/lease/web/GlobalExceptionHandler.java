package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.web;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.ErrorCode;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseServiceException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将业务异常翻译为稳定的错误载荷 {@code {error, message}}。
 * 底层异常类型与堆栈不会透出给调用方，只暴露归类后的错误码与安全文案。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LeaseServiceException.class)
    public ResponseEntity<Map<String, Object>> handle(LeaseServiceException ex) {
        ErrorCode code = ex.code();
        log.warn("business error: code={}, message={}", code, ex.getMessage());
        return ResponseEntity.status(code.httpStatus())
                .body(Map.of("error", code.name(), "message", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.httpStatus())
                .body(Map.of("error", ErrorCode.INVALID_REQUEST.name(),
                        "message", "request body is missing or malformed"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("unexpected error, classified as INTERNAL_ERROR", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ErrorCode.INTERNAL_ERROR.name(),
                        "message", "internal error"));
    }
}
