package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api;

/** 租约只读视图，重复查询必须得到可复现的结论。 */
public record LeaseView(
        String leaseId,
        String requestId,
        String tenantId,
        String status,
        String resourceId,
        long createdAtMillis,
        long expiresAtMillis,
        long endedAtMillis,
        String failedReason) {
}
