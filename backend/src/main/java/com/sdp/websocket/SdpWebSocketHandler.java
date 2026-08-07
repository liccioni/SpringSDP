package com.sdp.websocket;

import com.sdp.auth.AuthService;
import com.sdp.common.PriceTick;
import com.sdp.eventbus.DomainEvent;
import com.sdp.eventbus.EventBus;
import com.sdp.market.SubscriptionRequest;
import com.sdp.trade.TradeRequest;
import com.sdp.trade.TradeService;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Requires a valid token (see AuthService/ADR 0016) before anything else -
 * a connection with a missing or invalid token is closed immediately, with
 * no HELLO. Once authenticated, sends a HELLO envelope, then streams
 * EventBus events and handles incoming
 * CREATE_TRADE/SUBSCRIBE/UNSUBSCRIBE/GET_TRADE_HISTORY envelopes for the
 * session's lifetime. Doesn't know or care which service published an event.
 *
 * Each connection starts subscribed to no symbols, so PRICE_TICK delivery is
 * scoped to that connection's own subscriptions via SUBSCRIBE/UNSUBSCRIBE.
 * TRADE_CREATED and TRADE_REJECTED are unaffected and stay broadcast to every
 * session, per docs/protocol.md. TRADE_HISTORY is different again: it's a
 * targeted reply to that connection's own GET_TRADE_HISTORY request, sent
 * only to the requesting connection via its own per-connection sink rather
 * than the shared, broadcast EventBus.
 */
@Component
public class SdpWebSocketHandler implements WebSocketHandler {

	private final ObjectMapper objectMapper;
	private final EventBus eventBus;
	private final TradeService tradeService;
	private final AuthService authService;

	public SdpWebSocketHandler(ObjectMapper objectMapper, EventBus eventBus, TradeService tradeService, AuthService authService) {
		this.objectMapper = objectMapper;
		this.eventBus = eventBus;
		this.tradeService = tradeService;
		this.authService = authService;
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		if (tokenFrom(session).flatMap(authService::username).isEmpty()) {
			return session.close();
		}

		Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
		Sinks.Many<Envelope> directMessages = Sinks.many().unicast().onBackpressureBuffer();

		Mono<WebSocketMessage> hello = toMessage(session, new Envelope("HELLO", "Hello from the SDP backend!"));

		Flux<WebSocketMessage> events = eventBus.events()
				.filter(event -> isVisible(event, subscribedSymbols))
				.map(event -> new Envelope(event.eventType(), event))
				.concatMap(envelope -> toMessage(session, envelope));

		Flux<WebSocketMessage> direct = directMessages.asFlux()
				.concatMap(envelope -> toMessage(session, envelope));

		Flux<WebSocketMessage> outbound = hello.concatWith(events).mergeWith(direct);

		Mono<Void> inbound = session.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.flatMap(text -> handleIncoming(text, subscribedSymbols, directMessages))
				.then();

		return session.send(outbound).and(inbound);
	}

	private Optional<String> tokenFrom(WebSocketSession session) {
		return Optional.ofNullable(
				UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri())
						.build()
						.getQueryParams()
						.getFirst("token"));
	}

	// A PriceTick is only visible to sessions subscribed to its symbol; every
	// other event (trades) stays broadcast to all sessions.
	private boolean isVisible(DomainEvent event, Set<String> subscribedSymbols) {
		return !(event instanceof PriceTick tick) || subscribedSymbols.contains(tick.symbol());
	}

	private Mono<WebSocketMessage> toMessage(WebSocketSession session, Envelope envelope) {
		return Mono.fromCallable(() -> objectMapper.writeValueAsString(envelope))
				.map(session::textMessage);
	}

	private Mono<Void> handleIncoming(String text, Set<String> subscribedSymbols, Sinks.Many<Envelope> directMessages) {
		Envelope envelope = objectMapper.readValue(text, Envelope.class);
		return switch (envelope.type()) {
			case "CREATE_TRADE" -> handleCreateTrade(envelope);
			case "SUBSCRIBE" -> handleSubscribe(envelope, subscribedSymbols);
			case "UNSUBSCRIBE" -> handleUnsubscribe(envelope, subscribedSymbols);
			case "GET_TRADE_HISTORY" -> handleGetTradeHistory(directMessages);
			default -> Mono.empty();
		};
	}

	private Mono<Void> handleCreateTrade(Envelope envelope) {
		TradeRequest request = objectMapper.convertValue(envelope.payload(), TradeRequest.class);
		return tradeService.createTrade(request).then();
	}

	private Mono<Void> handleSubscribe(Envelope envelope, Set<String> subscribedSymbols) {
		subscribedSymbols.add(readSymbol(envelope));
		return Mono.empty();
	}

	private Mono<Void> handleUnsubscribe(Envelope envelope, Set<String> subscribedSymbols) {
		subscribedSymbols.remove(readSymbol(envelope));
		return Mono.empty();
	}

	private String readSymbol(Envelope envelope) {
		return objectMapper.convertValue(envelope.payload(), SubscriptionRequest.class).symbol();
	}

	private Mono<Void> handleGetTradeHistory(Sinks.Many<Envelope> directMessages) {
		return tradeService.history()
				.collectList()
				.doOnNext(trades -> emitDirect(directMessages, new Envelope("TRADE_HISTORY", trades)))
				.then();
	}

	// Sinks.Many requires the caller to serialize emissions (same reason as
	// EventBus.publish()); lock on the sink itself since each connection owns
	// its own instance.
	private void emitDirect(Sinks.Many<Envelope> directMessages, Envelope envelope) {
		synchronized (directMessages) {
			directMessages.tryEmitNext(envelope);
		}
	}
}
