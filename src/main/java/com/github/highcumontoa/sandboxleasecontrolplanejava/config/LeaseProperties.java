package com.github.highcumontoa.sandboxleasecontrolplanejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租约相关配置（application.properties 可覆盖）。
 */
@ConfigurationProperties(prefix = "lease")
public class LeaseProperties {

    private int poolCapacity = 8;
    private long defaultTtlSeconds = 300;
    private long maxTtlSeconds = 3600;
    private long reaperIntervalMillis = 1000;
    private boolean autoReclaimEnabled = true;

    public int getPoolCapacity() {
        return poolCapacity;
    }

    public void setPoolCapacity(int poolCapacity) {
        this.poolCapacity = poolCapacity;
    }

    public long getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public void setDefaultTtlSeconds(long defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public long getMaxTtlSeconds() {
        return maxTtlSeconds;
    }

    public void setMaxTtlSeconds(long maxTtlSeconds) {
        this.maxTtlSeconds = maxTtlSeconds;
    }

    public long getReaperIntervalMillis() {
        return reaperIntervalMillis;
    }

    public void setReaperIntervalMillis(long reaperIntervalMillis) {
        this.reaperIntervalMillis = reaperIntervalMillis;
    }

    public boolean isAutoReclaimEnabled() {
        return autoReclaimEnabled;
    }

    public void setAutoReclaimEnabled(boolean autoReclaimEnabled) {
        this.autoReclaimEnabled = autoReclaimEnabled;
    }
}
