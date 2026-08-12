package com.sdp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The WebSocket Gateway (issue #89, absorbing the monolith's remaining
 * packages as of #94's decommission) - the only service exposed to the
 * browser now, terminating the real WebSocket connection and the OAuth2
 * login flow. Sits at bare {@code com.sdp}, matching the monolith's old
 * {@code SdpApplication} placement, rather than nesting under
 * {@code com.sdp.gateway} - this service absorbed 7+ sibling packages from
 * the monolith (websocket, session, market, trade, common, eventbus,
 * config), and a common parent package avoids needing an explicit
 * {@code @ComponentScan} across all of them (see docs/testing.md's
 * sibling-package-scanning gotcha, which market-data-service/
 * trading-service both hit with far fewer packages).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
