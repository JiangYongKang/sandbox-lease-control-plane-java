package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api;

/**
 * 在线调整团队额度请求。
 *
 * @param limit 新的额度上限；{@code null} 表示移除团队额度配置，回到整池共享约束。
 *              0 表示禁止该团队新申请（已开租约不受影响）。
 */
public record TeamQuotaUpdateRequest(Integer limit) {
}
