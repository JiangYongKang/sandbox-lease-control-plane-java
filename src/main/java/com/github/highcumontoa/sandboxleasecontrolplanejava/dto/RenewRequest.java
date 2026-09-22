package com.github.highcumontoa.sandboxleasecontrolplanejava.dto;

public class RenewRequest {

    private String requestId;
    private Long ttlSeconds;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
