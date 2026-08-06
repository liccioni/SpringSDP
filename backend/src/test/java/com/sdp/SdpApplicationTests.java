package com.sdp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

// spring.sql.init.mode=always (application.yml default) runs schema.sql
// eagerly at context startup regardless of connection pooling, so this test
// - the only plain-unit-tier @SpringBootTest, with no database available -
// overrides it to never. Real runs and PostgresIntegrationTest-backed
// integration tests get the real default and actually initialize the schema.
@SpringBootTest
@TestPropertySource(properties = "spring.sql.init.mode=never")
class SdpApplicationTests {

	@Test
	void contextLoads() {
	}
}
