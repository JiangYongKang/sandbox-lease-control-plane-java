package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 租约控制平面配置。 */
@ConfigurationProperties(prefix = "lease")
public class LeaseProperties {

    private int poolCapacity = 4;
    private long defaultTtlSeconds = 60;
    private long maxTtlSeconds = 3600;
    private long reaperIntervalMillis = 2000;

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
}
