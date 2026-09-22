package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 进程内控制平面的基础设施装配。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(LeaseProperties.class)
public class InfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
