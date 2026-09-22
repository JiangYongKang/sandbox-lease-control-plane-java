package com.github.highcumontoa.sandboxleasecontrolplanejava.domain;

/**
 * 租约状态机：
 * <pre>
 *   ACTIVE --release()---------> RELEASED
 *   ACTIVE --expire/reclaim----> EXPIRED
 *   ACTIVE --renew()-----------> ACTIVE（延长 expiresAt）
 *   RELEASED / EXPIRED 为终态，不允许任何迁移
 * </pre>
 */
public enum LeaseStatus {
    ACTIVE,
    RELEASED,
    EXPIRED
}
