package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api;

import java.util.List;

/**
 * 团队当前挂着的非终态租约清单（PENDING / ACTIVE / RELEASING）。
 *
 * <p>清单内租约数即为该团队当前占用，与 {@link TeamQuotaView#leased()} 一致；
 * 所有团队占用之和恒等于整池 leased。
 */
public record TeamLeasesView(String tenantId, int leased, List<LeaseView> leases) {
}
