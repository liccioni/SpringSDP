package com.sdp.trading;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

// spring.sql.init.mode=always (application.yml default) runs schema.sql
// eagerly at context startup regardless of connection pooling - see
// backend's SdpApplicationTests for the same override, same reason: this is
// the only plain-unit-tier @SpringBootTest, with no database available.
@SpringBootTest
@TestPropertySource(properties = "spring.sql.init.mode=never")
class TradingServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
