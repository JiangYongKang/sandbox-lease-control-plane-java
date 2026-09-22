package com.github.highcumontoa.sandboxleasecontrolplanejava.lease;

/**
 * 租约状态。
 *
 * <pre>
 * (申请) PENDING -> ACTIVE -> RELEASED   (主动释放)
 *                    |          ^
 *                    |          |
 *                    ----> EXPIRED       (超时自动回收 / 惰性到期)
 * PENDING -> FAILED                      (申请链路失败并完成补偿)
 * </pre>
 */
public enum LeaseStatus {

    /** 配额已预留、沙箱资源尚在准备中。 */
    PENDING(false),
    /** 资源就绪、租约生效。 */
    ACTIVE(false),
    /** 释放流程进行中的瞬态。 */
    RELEASING(false),
    /** 主动释放完成（终态）。 */
    RELEASED(true),
    /** 超时回收完成（终态）。 */
    EXPIRED(true),
    /** 申请失败且补偿完成（终态）。 */
    FAILED(true);

    private final boolean terminal;

    LeaseStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
