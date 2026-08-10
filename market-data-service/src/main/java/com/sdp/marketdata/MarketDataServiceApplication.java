package com.sdp.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Skeleton for the Market Data Service (issue #89): stands up alongside the
 * still-running monolith to prove the multi-service shape works. Nothing has
 * migrated onto it yet - see ADR 0022 for the strangler-fig plan that
 * #90-#94 carry out.
 */
@SpringBootApplication
public class MarketDataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketDataServiceApplication.class, args);
    }
}
