package com.sdp.websocket;

import com.sdp.eventbus.EventBus;
import com.sdp.market.SubscriptionRequest;
import com.sdp.session.Session;
import com.sdp.trade.PendingTradeId;
import com.sdp.trade.TradeRequest;
import com.sdp.trade.TradeService;

import java.security.Principal;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Requires an authenticated Spring Security session before anything else -
 * see SecurityConfig/ADR 0020, superseding the token-based gating from ADR
 * 0016. The security filter chain rejects an unauthenticated WS upgrade
 * before handle() is ever invoked, so the principal from the handshake is
 * always present here. Once authenticated, resolves the connection's
 * Session (see ADR 0017), publishes a SESSION_STARTED event over RabbitMQ
 * for the Backend/Trading Service to audit (see ADR 0019, and ADR 0022's
 * update for issue #93 - this monolith plays the Gateway role, same as
 * #90-#92), and sends a personalized HELLO envelope, then streams EventBus
 * events and handles incoming
 * CREATE_TRADE/CONFIRM_TRADE/CANCEL_TRADE/SUBSCRIBE/UNSUBSCRIBE/GET_TRADE_HISTORY
 * envelopes for the session's lifetime. Doesn't know or care which service
 * published an event.
 *
 * Each connection starts subscribed to no symbols, so PRICE_TICK delivery is
 * scoped to that connection's own subscriptions via SUBSCRIBE/UNSUBSCRIBE
 * (see SymbolSubscription for the visibility rule). TRADE_CREATED and
 * TRADE_REJECTED stay broadcast to every session, per docs/protocol.md.
 * TRADE_PENDING, TRADE_CANCELLED, and TRADE_HISTORY are different: each is a
 * targeted reply to that connection's own request (CREATE_TRADE,
 * CANCEL_TRADE, GET_TRADE_HISTORY respectively), sent only to the requesting
 * connection via its own per-connection sink rather than the shared,
 * broadcast EventBus. See ADR 0018 for the two-step CREATE_TRADE ->
 * CONFIRM_TRADE/CANCEL_TRADE execution workflow this implements.
 */
@Component
public class SdpWebSocketHandler implements WebSocketHandler {

	private static final String SESSION_STARTED_BINDING = "sessionStarted-out-0";

	private final ObjectMapper objectMapper;
	private final EventBus eventBus;
	private final TradeService tradeService;
	private final StreamBridge streamBridge;

	public SdpWebSocketHandler(ObjectMapper objectMapper, EventBus eventBus, TradeService tradeService, StreamBridge streamBridge) {
		this.objectMapper = objectMapper;
		this.eventBus = eventBus;
		this.tradeService = tradeService;
		this.streamBridge = streamBridge;
	}

	@Override
	public Mono<Void> handle(WebSocketSession webSocketSession) {
		return webSocketSession.getHandshakeInfo().getPrincipal()
				.flatMap(principal -> handleAuthenticated(webSocketSession, principal));
	}

	// The handshake principal is an OAuth2AuthenticationToken (Authentication
	// extends Principal), carrying the Keycloak realm roles mapped onto it by
	// KeycloakRealmRoleOidcUserService - see ADR 0025. Threaded onto Session
	// so trading-service can authorize CREATE_TRADE itself.
	private Mono<Void> handleAuthenticated(WebSocketSession webSocketSession, Principal principal) {
		Set<String> roles = principal instanceof Authentication authentication
				? authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet())
				: Set.of();
		Session session = new Session(webSocketSession.getId(), principal.getName(), roles);

		Sinks.Many<Envelope> directMessages = Sinks.many().unicast().onBackpressureBuffer();

		Mono<WebSocketMessage> hello = Mono.fromRunnable(() -> streamBridge.send(
						SESSION_STARTED_BINDING, new com.sdp.contracts.SessionStarted(session.id(), session.username())))
				.then(toMessage(webSocketSession, new Envelope("HELLO", "Hello, " + session.username() + "!")));

		Flux<WebSocketMessage> events = eventBus.events()
				.filter(session.subscriptions()::isVisible)
				.map(event -> new Envelope(event.eventType(), event))
				.concatMap(envelope -> toMessage(webSocketSession, envelope));

		Flux<WebSocketMessage> direct = directMessages.asFlux()
				.concatMap(envelope -> toMessage(webSocketSession, envelope));

		Flux<WebSocketMessage> outbound = hello.concatWith(events).mergeWith(direct);

		Mono<Void> inbound = webSocketSession.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.flatMap(text -> handleIncoming(text, session, directMessages))
				.then();

		return webSocketSession.send(outbound).and(inbound)
				.doFinally(signalType -> cancelPendingTrades(session));
	}

	// The connection's own PendingTrades (issue #79) - not gated on the
	// cancel actually succeeding, since the connection is already gone and
	// there's nowhere left to report a failure to. CANCEL_TRADE's existing
	// "unknown or already-resolved id is a silent no-op" contract already
	// covers a trade this session confirmed/cancelled itself just before
	// disconnecting.
	private void cancelPendingTrades(Session session) {
		session.pendingTrades().all().forEach(id -> tradeService.cancelTrade(id, session).subscribe());
	}

	private Mono<WebSocketMessage> toMessage(WebSocketSession session, Envelope envelope) {
		return Mono.fromCallable(() -> objectMapper.writeValueAsString(envelope))
				.map(session::textMessage);
	}

	private Mono<Void> handleIncoming(String text, Session session, Sinks.Many<Envelope> directMessages) {
		Envelope envelope = objectMapper.readValue(text, Envelope.class);
		return switch (envelope.type()) {
			case "CREATE_TRADE" -> handleCreateTrade(envelope, session, directMessages);
			case "CONFIRM_TRADE" -> handleConfirmTrade(envelope, session);
			case "CANCEL_TRADE" -> handleCancelTrade(envelope, session, directMessages);
			case "SUBSCRIBE" -> handleSubscribe(envelope, session);
			case "UNSUBSCRIBE" -> handleUnsubscribe(envelope, session);
			case "GET_TRADE_HISTORY" -> handleGetTradeHistory(directMessages);
			default -> Mono.empty();
		};
	}

	private Mono<Void> handleCreateTrade(Envelope envelope, Session session, Sinks.Many<Envelope> directMessages) {
		TradeRequest request = objectMapper.convertValue(envelope.payload(), TradeRequest.class);
		return tradeService.requestTrade(request, session)
				.doOnNext(pending -> {
					session.pendingTrades().add(pending.id());
					emitDirect(directMessages, new Envelope("TRADE_PENDING", pending));
				})
				.then();
	}

	private Mono<Void> handleConfirmTrade(Envelope envelope, Session session) {
		String id = readPendingTradeId(envelope);
		session.pendingTrades().remove(id);
		return tradeService.confirmTrade(id, session).then();
	}

	private Mono<Void> handleCancelTrade(Envelope envelope, Session session, Sinks.Many<Envelope> directMessages) {
		String id = readPendingTradeId(envelope);
		session.pendingTrades().remove(id);
		return tradeService.cancelTrade(id, session)
				.doOnNext(pending -> emitDirect(directMessages, new Envelope("TRADE_CANCELLED", pending)))
				.then();
	}

	private String readPendingTradeId(Envelope envelope) {
		return objectMapper.convertValue(envelope.payload(), PendingTradeId.class).id();
	}

	private Mono<Void> handleSubscribe(Envelope envelope, Session session) {
		session.subscriptions().subscribe(readSymbol(envelope));
		return Mono.empty();
	}

	private Mono<Void> handleUnsubscribe(Envelope envelope, Session session) {
		session.subscriptions().unsubscribe(readSymbol(envelope));
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
