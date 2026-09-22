package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api;

/** 资源池配额快照。 */
public record QuotaView(int capacity, int available, int leased) {
}
