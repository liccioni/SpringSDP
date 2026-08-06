package com.sdp.websocket;

import com.sdp.market.MarketDataService;
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
 * Sends a HELLO envelope on connect, then streams PRICE_TICK and TRADE_CREATED
 * envelopes and handles incoming CREATE_TRADE envelopes for the session's lifetime.
 */
@Component
public class SdpWebSocketHandler implements WebSocketHandler {

	private final ObjectMapper objectMapper;
	private final MarketDataService marketDataService;
	private final TradeService tradeService;

	public SdpWebSocketHandler(ObjectMapper objectMapper, MarketDataService marketDataService, TradeService tradeService) {
		this.objectMapper = objectMapper;
		this.marketDataService = marketDataService;
		this.tradeService = tradeService;
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		Mono<WebSocketMessage> hello = toMessage(session, new Envelope("HELLO", "Hello from the SDP backend!"));

		Flux<WebSocketMessage> priceTicks = marketDataService.priceTicks()
				.map(tick -> new Envelope("PRICE_TICK", tick))
				.concatMap(envelope -> toMessage(session, envelope));

		Flux<WebSocketMessage> tradesCreated = tradeService.tradeCreated()
				.map(trade -> new Envelope("TRADE_CREATED", trade))
				.concatMap(envelope -> toMessage(session, envelope));

		Flux<WebSocketMessage> tradesRejected = tradeService.tradeRejected()
				.map(rejection -> new Envelope("TRADE_REJECTED", rejection))
				.concatMap(envelope -> toMessage(session, envelope));

		Flux<WebSocketMessage> outbound = hello.concatWith(Flux.merge(priceTicks, tradesCreated, tradesRejected));

		Mono<Void> inbound = session.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.doOnNext(this::handleIncoming)
				.then();

		return session.send(outbound).and(inbound);
	}

	private Mono<WebSocketMessage> toMessage(WebSocketSession session, Envelope envelope) {
		return Mono.fromCallable(() -> objectMapper.writeValueAsString(envelope))
				.map(session::textMessage);
	}

	private void handleIncoming(String text) {
		Envelope envelope = objectMapper.readValue(text, Envelope.class);
		if ("CREATE_TRADE".equals(envelope.type())) {
			TradeRequest request = objectMapper.convertValue(envelope.payload(), TradeRequest.class);
			tradeService.createTrade(request);
		}
	}
}
