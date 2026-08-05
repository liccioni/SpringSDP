package com.sdp.websocket;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * Sends a HELLO envelope on connect, then keeps the session open for future
 * PRICE_TICK/TRADE_CREATED streaming and CREATE_TRADE handling.
 */
@Component
public class SdpWebSocketHandler implements WebSocketHandler {

	private final ObjectMapper objectMapper;

	public SdpWebSocketHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		Envelope hello = new Envelope("HELLO", "Hello from the SDP backend!");

		Mono<Void> sendHello = Mono.fromCallable(() -> objectMapper.writeValueAsString(hello))
				.map(session::textMessage)
				.flatMap(message -> session.send(Mono.just(message)));

		return sendHello.and(session.receive().then());
	}
}
