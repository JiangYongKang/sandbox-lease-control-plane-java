package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision;

/** 沙箱资源供应器抽象，便于注入确定性的失败桩。 */
public interface SandboxProvisioner {

    /** 申请并准备沙箱资源，成功返回资源标识；失败抛出 {@link SandboxProvisionException}。 */
    String provision(String leaseId, String tenantId);

    /** 回收已申请的沙箱资源；尽力而为，不向调用方抛出。 */
    void deprovision(String resourceId);
}
