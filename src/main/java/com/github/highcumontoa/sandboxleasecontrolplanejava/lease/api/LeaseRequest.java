package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api;

/** 申请租约：requestId 用于幂等去重。 */
public record LeaseRequest(String requestId, String tenantId, long ttlSeconds) {
}
