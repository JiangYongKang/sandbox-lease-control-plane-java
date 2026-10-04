package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 租约控制平面配置。 */
@ConfigurationProperties(prefix = "lease")
public class LeaseProperties {

    private int poolCapacity = 4;
    private long defaultTtlSeconds = 60;
    private long maxTtlSeconds = 3600;
    private long reaperIntervalMillis = 2000;

    /**
     * 按团队配置的额度上限：tenantId -> 上限。
     * 对应配置项 {@code lease.team-limits.<tenantId>=N}；未列出的团队不单独限额，
     * 继续沿用整池共享约束。运行期可经 API 在线调整。
     */
    private Map<String, Integer> teamLimits = new LinkedHashMap<>();

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

    public Map<String, Integer> getTeamLimits() {
        return teamLimits;
    }

    public void setTeamLimits(Map<String, Integer> teamLimits) {
        this.teamLimits = teamLimits;
    }
}
