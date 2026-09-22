package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用时钟：固定起点，可显式前进，从而确定性地触发超时回收。
 */
public class TestClock extends Clock {

    private Instant now;

    public TestClock() {
        this(Instant.parse("2026-09-23T00:00:00Z"));
    }

    public TestClock(Instant start) {
        this.now = start;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advanceSeconds(long seconds) {
        now = now.plusSeconds(seconds);
    }
}
