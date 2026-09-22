package com.github.highcumontoa.sandboxleasecontrolplanejava.web;

import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.AcquireRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.LeaseResponse;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.PoolStatusResponse;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.ReleaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.dto.RenewRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.service.LeaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租约控制平面 HTTP 接口（仅本地使用）。
 */
@RestController
@RequestMapping("/api/leases")
public class LeaseController {

    private final LeaseService leaseService;

    public LeaseController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    @PostMapping
    public ResponseEntity<LeaseResponse> acquire(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AcquireRequest request) {
        // 幂等键：优先使用请求头，其次使用请求体中的 requestId
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            request.setRequestId(idempotencyKey.trim());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(leaseService.acquire(request));
    }

    @PostMapping("/{leaseId}/renew")
    public LeaseResponse renew(@PathVariable String leaseId, @RequestBody RenewRequest request) {
        return leaseService.renew(leaseId, request.getRequestId(), request.getTtlSeconds());
    }

    @PostMapping("/{leaseId}/release")
    public LeaseResponse release(@PathVariable String leaseId, @RequestBody ReleaseRequest request) {
        return leaseService.release(leaseId, request.getRequestId());
    }

    @GetMapping("/{leaseId}")
    public LeaseResponse get(@PathVariable String leaseId) {
        return leaseService.get(leaseId);
    }

    @GetMapping("/pool/status")
    public PoolStatusResponse poolStatus() {
        return leaseService.poolStatus();
    }
}
