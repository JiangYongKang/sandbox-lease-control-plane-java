package com.github.highcumontoa.sandboxleasecontrolplanejava.provision;

/**
 * 模拟底层环境分配/销毁组件（本地资源，无外部依赖）。
 * 可通过故障注入让 provision 抛出底层异常，用于验证申请链路补偿。
 */
public interface EnvironmentProvisioner {

    /**
     * 分配一个环境句柄。
     * @param failWhenTrue 故障注入开关：为 true 时抛出底层异常
     * @return 环境句柄（此处用字符串模拟）
     */
    String provision(String leaseId, String envType, boolean failWhenTrue);

    /** 回收环境句柄。 */
    void deprovision(String handle);
}
