package com.github.highcumontoa.sandboxleasecontrolplanejava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 可替换时钟：测试里可换成固定/步进时钟来确定性地验证超时回收。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
