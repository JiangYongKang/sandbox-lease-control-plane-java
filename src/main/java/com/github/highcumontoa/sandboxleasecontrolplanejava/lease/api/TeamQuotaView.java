package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api;

/**
 * 团队额度快照。
 *
 * @param limit     团队额度上限；未配置为 null（表示仅受整池容量约束）
 * @param used      当前占用（与整池 leased 口径一致：PENDING/ACTIVE/RELEASING 中的租约）
 * @param remaining 剩余额度；未配置上限为 null
 */
public record TeamQuotaView(String teamId, Integer limit, int used, Integer remaining) {
}
