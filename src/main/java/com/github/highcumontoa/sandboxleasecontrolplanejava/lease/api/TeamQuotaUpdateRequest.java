package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api;

/** 在线调整团队额度：limit 为非负整数；传 null 表示清除上限、恢复整池共享。 */
public record TeamQuotaUpdateRequest(Integer limit) {
}
