package com.sdp.websocket;

import com.sdp.eventbus.EventBus;
import com.sdp.trade.TradeRequest;
import com.sdp.trade.TradeService;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sends a HELLO envelope on connect, then streams every EventBus event and
 * handles incoming CREATE_TRADE envelopes for the session's lifetime. Doesn't
 * know or care which service published an event.
 */
@Component
public class SdpWebSocketHandler implements WebSocketHandler {

	private final ObjectMapper objectMapper;
	private final EventBus eventBus;
	private final TradeService tradeService;

	public SdpWebSocketHandler(ObjectMapper objectMapper, EventBus eventBus, TradeService tradeService) {
		this.objectMapper = objectMapper;
		this.eventBus = eventBus;
		this.tradeService = tradeService;
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		Mono<WebSocketMessage> hello = toMessage(session, new Envelope("HELLO", "Hello from the SDP backend!"));

		Flux<WebSocketMessage> events = eventBus.events()
				.map(event -> new Envelope(event.eventType(), event))
				.concatMap(envelope -> toMessage(session, envelope));

		Flux<WebSocketMessage> outbound = hello.concatWith(events);

		Mono<Void> inbound = session.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.flatMap(this::handleIncoming)
				.then();

		return session.send(outbound).and(inbound);
	}

	private Mono<WebSocketMessage> toMessage(WebSocketSession session, Envelope envelope) {
		return Mono.fromCallable(() -> objectMapper.writeValueAsString(envelope))
				.map(session::textMessage);
	}

	private Mono<Void> handleIncoming(String text) {
		Envelope envelope = objectMapper.readValue(text, Envelope.class);
		if (!"CREATE_TRADE".equals(envelope.type())) {
			return Mono.empty();
		}
		TradeRequest request = objectMapper.convertValue(envelope.payload(), TradeRequest.class);
		return tradeService.createTrade(request).then();
	}
}
