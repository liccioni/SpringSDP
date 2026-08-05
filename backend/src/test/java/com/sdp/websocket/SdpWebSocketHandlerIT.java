package com.sdp.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class SdpWebSocketHandlerIT {

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void sendsHelloEnvelopeOnConnect() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();

		client.execute(URI.create("ws://localhost:" + port + "/ws"),
				session -> session.receive()
						.next()
						.map(message -> message.getPayloadAsText())
						.doOnNext(received::set)
						.then())
				.block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("HELLO");
		assertThat(envelope.payload()).isEqualTo("Hello from the SDP backend!");
	}
}
