package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api;

/** 续约：以新的 TTL 从当前时刻顺延到期时间。 */
public record RenewRequest(String requestId, long ttlSeconds) {
}
