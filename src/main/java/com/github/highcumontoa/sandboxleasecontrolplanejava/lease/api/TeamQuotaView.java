package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api;

/**
 * 团队额度快照。
 *
 * <p>{@code limit=null} 表示该团队未单独配置额度，沿用整池共享约束；
 * {@code leased/remaining} 始终为该团队当前实际占用，且团队占用汇总恒等于整池 leased。
 *
 * @param tenantId      团队标识
 * @param limit         团队额度上限；{@code null} 表示不限（仅受整池容量约束）
 * @param leased        团队当前占用（非终态租约数）
 * @param remaining     团队剩余额度；未配置上限时为 null，下限保护到 0（调小额度后可能为 0）
 * @param poolCapacity  整池容量快照（保证与 {@code GET /api/leases/quota} 口径一致）
 * @param poolLeased    整池当前占用快照
 * @param poolAvailable 整池剩余快照
 */
public record TeamQuotaView(
        String tenantId,
        Integer limit,
        int leased,
        Integer remaining,
        int poolCapacity,
        int poolLeased,
        int poolAvailable) {
}
