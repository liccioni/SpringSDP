package com.sdp.websocket;

import com.sdp.auth.AuthService;
import com.sdp.eventbus.EventBus;
import com.sdp.market.SubscriptionRequest;
import com.sdp.market.SymbolSubscription;
import com.sdp.session.Session;
import com.sdp.trade.TradeRequest;
import com.sdp.trade.TradeService;

import java.util.Optional;

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
 * no HELLO. Once authenticated, resolves the connection's Session (see
 * ADR 0017) and sends a personalized HELLO envelope, then streams EventBus
 * events and handles incoming
 * CREATE_TRADE/SUBSCRIBE/UNSUBSCRIBE/GET_TRADE_HISTORY envelopes for the
 * session's lifetime. Doesn't know or care which service published an event.
 *
 * Each connection starts subscribed to no symbols, so PRICE_TICK delivery is
 * scoped to that connection's own subscriptions via SUBSCRIBE/UNSUBSCRIBE
 * (see SymbolSubscription for the visibility rule). TRADE_CREATED and
 * TRADE_REJECTED are unaffected and stay broadcast to every session, per
 * docs/protocol.md. TRADE_HISTORY is different again: it's a targeted reply
 * to that connection's own GET_TRADE_HISTORY request, sent only to the
 * requesting connection via its own per-connection sink rather than the
 * shared, broadcast EventBus.
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
	public Mono<Void> handle(WebSocketSession webSocketSession) {
		Optional<String> username = tokenFrom(webSocketSession).flatMap(authService::username);
		if (username.isEmpty()) {
			return webSocketSession.close();
		}
		Session session = new Session(webSocketSession.getId(), username.get());

		SymbolSubscription subscriptions = new SymbolSubscription();
		Sinks.Many<Envelope> directMessages = Sinks.many().unicast().onBackpressureBuffer();

		Mono<WebSocketMessage> hello = toMessage(webSocketSession, new Envelope("HELLO", "Hello, " + session.username() + "!"));

		Flux<WebSocketMessage> events = eventBus.events()
				.filter(subscriptions::isVisible)
				.map(event -> new Envelope(event.eventType(), event))
				.concatMap(envelope -> toMessage(webSocketSession, envelope));

		Flux<WebSocketMessage> direct = directMessages.asFlux()
				.concatMap(envelope -> toMessage(webSocketSession, envelope));

		Flux<WebSocketMessage> outbound = hello.concatWith(events).mergeWith(direct);

		Mono<Void> inbound = webSocketSession.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.flatMap(text -> handleIncoming(text, subscriptions, directMessages))
				.then();

		return webSocketSession.send(outbound).and(inbound);
	}

	private Optional<String> tokenFrom(WebSocketSession webSocketSession) {
		return Optional.ofNullable(
				UriComponentsBuilder.fromUri(webSocketSession.getHandshakeInfo().getUri())
						.build()
						.getQueryParams()
						.getFirst("token"));
	}

	private Mono<WebSocketMessage> toMessage(WebSocketSession session, Envelope envelope) {
		return Mono.fromCallable(() -> objectMapper.writeValueAsString(envelope))
				.map(session::textMessage);
	}

	private Mono<Void> handleIncoming(String text, SymbolSubscription subscriptions, Sinks.Many<Envelope> directMessages) {
		Envelope envelope = objectMapper.readValue(text, Envelope.class);
		return switch (envelope.type()) {
			case "CREATE_TRADE" -> handleCreateTrade(envelope);
			case "SUBSCRIBE" -> handleSubscribe(envelope, subscriptions);
			case "UNSUBSCRIBE" -> handleUnsubscribe(envelope, subscriptions);
			case "GET_TRADE_HISTORY" -> handleGetTradeHistory(directMessages);
			default -> Mono.empty();
		};
	}

	private Mono<Void> handleCreateTrade(Envelope envelope) {
		TradeRequest request = objectMapper.convertValue(envelope.payload(), TradeRequest.class);
		return tradeService.createTrade(request).then();
	}

	private Mono<Void> handleSubscribe(Envelope envelope, SymbolSubscription subscriptions) {
		subscriptions.subscribe(readSymbol(envelope));
		return Mono.empty();
	}

	private Mono<Void> handleUnsubscribe(Envelope envelope, SymbolSubscription subscriptions) {
		subscriptions.unsubscribe(readSymbol(envelope));
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
