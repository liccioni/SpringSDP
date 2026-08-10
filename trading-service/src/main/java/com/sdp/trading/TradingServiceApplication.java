package com.sdp.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Owns trade/, audit/, and common/Trade's R2DBC/Postgres access as of #91
 * (see ADR 0022's update) - `com.sdp.audit` is a sibling package, not a
 * child of this class's own `com.sdp.trading`, so both component scanning
 * and R2DBC repository scanning need explicit base packages rather than
 * relying on the default (scan-from-here-down) behavior.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.sdp.trading", "com.sdp.audit"})
@EnableR2dbcRepositories(basePackages = {"com.sdp.trading", "com.sdp.audit"})
public class TradingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingServiceApplication.class, args);
    }
}
