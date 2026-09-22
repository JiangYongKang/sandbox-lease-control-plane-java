package com.github.highcumontoa.sandboxleasecontrolplanejava.dto;

/**
 * 申请租约请求。requestId 为幂等键；simulateProvisioningFailure 仅用于本地故障注入测试。
 */
public class AcquireRequest {

    private String requestId;
    private String envType;
    private String owner;
    private Long ttlSeconds;
    private boolean simulateProvisioningFailure;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getEnvType() {
        return envType;
    }

    public void setEnvType(String envType) {
        this.envType = envType;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public boolean isSimulateProvisioningFailure() {
        return simulateProvisioningFailure;
    }

    public void setSimulateProvisioningFailure(boolean simulateProvisioningFailure) {
        this.simulateProvisioningFailure = simulateProvisioningFailure;
    }
}
