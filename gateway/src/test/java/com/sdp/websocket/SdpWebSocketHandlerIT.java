package com.sdp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;

import com.sdp.RabbitMqIntegrationTest;
import com.sdp.RedisIntegrationTest;
import com.sdp.common.PriceTick;
import com.sdp.common.Side;
import com.sdp.common.Trade;
import com.sdp.market.SubscriptionRequest;
import com.sdp.trade.PendingTrade;
import com.sdp.trade.PendingTradeId;
import com.sdp.trade.TradeRejected;
import com.sdp.trade.TradeRequest;

import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// Authenticates test WS connections by seeding a session directly into
// Redis (attribute "SPRING_SECURITY_CONTEXT", the same one
// WebSessionServerSecurityContextRepository reads/writes) rather than
// driving a real browser through Keycloak's login page - once a session
// with a valid Authentication exists, the WS handshake's
// getHandshakeInfo().getPrincipal() doesn't care how it got there. This
// proves the real Redis-backed session/handshake wiring; the actual
// Keycloak authorization-code exchange is live-verified separately (see
// PR description), since automating a real browser-driven OAuth2 login
// inside a JUnit test is disproportionate for this project's scale.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class SdpWebSocketHandlerIT implements RedisIntegrationTest, RabbitMqIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private org.springframework.amqp.core.AmqpAdmin amqpAdmin;

	@Autowired
	private ReactiveRedisSessionRepository sessionRepository;

	private String sessionCookie;
	private FakeTradingService fakeTradingService;
	private String sessionStartedQueue;

	@BeforeEach
	void logIn() {
		sessionCookie = authenticatedSessionId("trader1");
	}

	// Stands in for the real Backend/Trading Service's consumption of
	// SESSION_STARTED (issue #93) - this test only starts the monolith's
	// own context, so nothing else would receive what SdpWebSocketHandler
	// publishes without a queue bound here first. Not auto-delete/exclusive
	// instead: same reasoning as TradeServiceIT's bindAnonymousQueue (see
	// docs/testing.md) - avoids the queue vanishing between declare and
	// receive.
	@BeforeEach
	void bindSessionStartedQueue() {
		FanoutExchange exchange = new FanoutExchange("session-started");
		amqpAdmin.declareExchange(exchange);
		sessionStartedQueue = amqpAdmin.declareQueue(new Queue("", false, true, false));
		amqpAdmin.declareBinding(BindingBuilder.bind(new Queue(sessionStartedQueue)).to(exchange));
	}

	@AfterEach
	void deleteSessionStartedQueue() {
		amqpAdmin.deleteQueue(sessionStartedQueue);
	}

	// Stands in for the real Backend/Trading Service (see #92, ADR 0022's
	// update) - this test only starts the monolith's own Spring context, so
	// nothing else would answer CREATE_TRADE/CONFIRM_TRADE/CANCEL_TRADE/
	// GET_TRADE_HISTORY without it.
	@BeforeEach
	void startFakeTradingService() {
		fakeTradingService = new FakeTradingService(rabbitTemplate.getConnectionFactory(), amqpAdmin, rabbitTemplate, objectMapper);
	}

	@org.junit.jupiter.api.AfterEach
	void stopFakeTradingService() {
		fakeTradingService.stop();
	}

	private String authenticatedSessionId(String username) {
		return createAuthenticatedSession(sessionRepository, username);
	}

	// ReactiveRedisSessionRepository.RedisSession (the type createSession()
	// and save() actually exchange) is package-private, so it can't be named
	// here at all - a generic helper lets the compiler carry that type
	// through as its own type variable instead.
	private <S extends Session> String createAuthenticatedSession(ReactiveSessionRepository<S> repository, String username) {
		var authentication = new UsernamePasswordAuthenticationToken(username, null, List.of(new SimpleGrantedAuthority("trader")));
		return repository.createSession()
				.flatMap(session -> {
					session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
					return repository.save(session).thenReturn(session.getId());
				})
				.block(Duration.ofSeconds(5));
	}

	private URI wsUri() {
		return URI.create("ws://localhost:" + port + "/ws");
	}

	private HttpHeaders sessionCookieHeader() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("Cookie", "SESSION=" + sessionCookie);
		return headers;
	}

	// Simulates the Market Data Service's (#90) / Backend-Trading Service's
	// (#91) production side directly against their fanout exchanges, rather
	// than relying on an in-process generator or a real CREATE_TRADE trigger
	// - neither exists in the monolith anymore/yet (see ADR 0022). Bypasses
	// RabbitTemplate's default (non-JSON) message converter deliberately:
	// Spring Cloud Stream's functional binder reads the contentType header
	// itself to pick a converter for each consumer function's declared
	// parameter type.
	private void publishToFanoutExchange(String exchange, Object payload) {
		byte[] body = objectMapper.writeValueAsBytes(payload);
		MessageProperties properties = new MessageProperties();
		properties.setContentType("application/json");
		rabbitTemplate.send(exchange, "", new Message(body, properties));
	}

	private void publishPriceTick(com.sdp.contracts.PriceTick tick) {
		publishToFanoutExchange("price-ticks", tick);
	}

	@Test
	void rejectsConnectionWithNoSession() {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

		// Spring Security's default entry point for an unauthenticated request
		// to a protected path is an HTTP redirect (to Keycloak's login), not a
		// WS-level close - the handshake itself fails with a non-101 response,
		// which the frontend's socket.ts treats as "never opened, go log in".
		assertThatThrownBy(() -> client.execute(wsUri(), session -> Mono.empty()).block(Duration.ofSeconds(5)))
				.isInstanceOf(WebSocketClientHandshakeException.class);
	}

	@Test
	void rejectsConnectionWithAnInvalidSessionCookie() {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		HttpHeaders headers = new HttpHeaders();
		headers.add("Cookie", "SESSION=not-a-real-session");

		assertThatThrownBy(() -> client.execute(wsUri(), headers, session -> Mono.empty()).block(Duration.ofSeconds(5)))
				.isInstanceOf(WebSocketClientHandshakeException.class);
	}

	@Test
	void sendsHelloEnvelopeOnConnect() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();

		client.execute(wsUri(), sessionCookieHeader(),
				session -> session.receive()
						.next()
						.map(message -> message.getPayloadAsText())
						.doOnNext(received::set)
						.then())
				.block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("HELLO");
		assertThat(envelope.payload()).isEqualTo("Hello, trader1!");
	}

	// Proves the producer side of #93: connecting publishes a SESSION_STARTED
	// event onto the "session-started" fanout exchange for the Backend/
	// Trading Service to audit, in place of the direct in-process
	// AuditService.record call this replaced.
	@Test
	void publishesSessionStartedOnConnect() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

		client.execute(wsUri(), sessionCookieHeader(), session -> session.receive().next().then())
				.block(Duration.ofSeconds(5));

		Message message = rabbitTemplate.receive(sessionStartedQueue, 5000);
		assertThat(message).isNotNull();
		com.sdp.contracts.SessionStarted event = objectMapper.readValue(message.getBody(), com.sdp.contracts.SessionStarted.class);
		assertThat(event.username()).isEqualTo("trader1");
		assertThat(event.sessionId()).isNotBlank();
	}

	@Test
	void streamsPriceTicksForASubscribedSymbol() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();
		com.sdp.contracts.PriceTick published = new com.sdp.contracts.PriceTick(
				"EUR/USD", new BigDecimal("1.0849"), new BigDecimal("1.0851"), Instant.now());

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			Mono<Void> sendSubscribeThenPublish = sendEnvelope(session, "SUBSCRIBE", new SubscriptionRequest("EUR/USD"))
					.delaySubscription(Duration.ofMillis(200))
					.doOnSuccess(v -> publishPriceTick(published));

			Mono<Void> receiveTick = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.filter(text -> text.contains("PRICE_TICK"))
					.next()
					.doOnNext(received::set)
					.then();

			return sendSubscribeThenPublish.and(receiveTick);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("PRICE_TICK");

		PriceTick tick = objectMapper.convertValue(envelope.payload(), PriceTick.class);
		assertThat(tick.symbol()).isEqualTo("EUR/USD");
		assertThat(tick.bid()).isEqualByComparingTo("1.0849");
		assertThat(tick.ask()).isEqualByComparingTo("1.0851");
	}

	@Test
	void receivesNoPriceTicksBeforeSubscribing() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		List<String> received = new ArrayList<>();
		com.sdp.contracts.PriceTick published = new com.sdp.contracts.PriceTick(
				"EUR/USD", new BigDecimal("1.0849"), new BigDecimal("1.0851"), Instant.now());

		client.execute(wsUri(), sessionCookieHeader(),
				session -> {
					Mono<Void> collect = session.receive()
							.map(WebSocketMessage::getPayloadAsText)
							.take(Duration.ofMillis(1000))
							.doOnNext(received::add)
							.then();

					// Never subscribed to anything on this connection - a real
					// tick is published regardless, to prove absence is due to
					// filtering, not just "no traffic happened to flow".
					Mono<Void> publish = Mono.fromRunnable(() -> publishPriceTick(published))
							.delaySubscription(Duration.ofMillis(200))
							.then();

					return collect.and(publish);
				})
				.block(Duration.ofSeconds(5));

		assertThat(received).noneMatch(text -> text.contains("PRICE_TICK"));
	}

	@Test
	void receivesNoPriceTicksForASymbolItSubscribedThenUnsubscribedFrom() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		List<String> received = new ArrayList<>();
		com.sdp.contracts.PriceTick published = new com.sdp.contracts.PriceTick(
				"GBP/USD", new BigDecimal("1.2649"), new BigDecimal("1.2651"), Instant.now());

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			// Subscribe, unsubscribe, then publish a real tick for that same
			// symbol - deterministic proof unsubscribing actually stops
			// delivery, rather than relying on beating a generator's interval.
			Mono<Void> subscribeUnsubscribeThenPublish = sendEnvelope(session, "SUBSCRIBE", new SubscriptionRequest("GBP/USD"))
					.then(sendEnvelope(session, "UNSUBSCRIBE", new SubscriptionRequest("GBP/USD")))
					.delaySubscription(Duration.ofMillis(200))
					.doOnSuccess(v -> publishPriceTick(published));

			Mono<Void> collect = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.filter(text -> text.contains("PRICE_TICK"))
					.take(Duration.ofMillis(1000))
					.doOnNext(received::add)
					.then();

			return subscribeUnsubscribeThenPublish.and(collect);
		}).block(Duration.ofSeconds(5));

		assertThat(received).noneMatch(text -> text.contains("GBP/USD"));
	}

	@Test
	void createTradeRepliesWithATradePendingToTheSubmitterOnly() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();
		TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("1000000"));

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			Mono<Void> sendCreateTrade = sendEnvelope(session, "CREATE_TRADE", request)
					.delaySubscription(Duration.ofMillis(200));

			Mono<Void> receiveTradePending = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.filter(text -> text.contains("TRADE_PENDING"))
					.next()
					.doOnNext(received::set)
					.then();

			return sendCreateTrade.and(receiveTradePending);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("TRADE_PENDING");

		PendingTrade pending = objectMapper.convertValue(envelope.payload(), PendingTrade.class);
		assertThat(pending.id()).isNotBlank();
		assertThat(pending.symbol()).isEqualTo("EUR/USD");
		assertThat(pending.side()).isEqualTo(Side.BUY);
		assertThat(pending.price()).isEqualByComparingTo("1.0850");
		assertThat(pending.quantity()).isEqualByComparingTo("1000000");
	}

	@Test
	void confirmingAPendingTradeBroadcastsTradeCreated() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();
		TradeRequest request = new TradeRequest("EUR/USD", Side.SELL, new BigDecimal("1.0855"), new BigDecimal("750000"));

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			Flux<String> incoming = session.receive().map(WebSocketMessage::getPayloadAsText).share();

			Mono<Void> createThenConfirm = sendEnvelope(session, "CREATE_TRADE", request)
					.delaySubscription(Duration.ofMillis(200))
					.then(incoming.filter(text -> text.contains("TRADE_PENDING")).next())
					.flatMap(this::extractPendingTradeId)
					.flatMap(id -> sendEnvelope(session, "CONFIRM_TRADE", new PendingTradeId(id)));

			Mono<Void> receiveTradeCreated = incoming
					.filter(text -> text.contains("TRADE_CREATED"))
					.next()
					.doOnNext(received::set)
					.then();

			return createThenConfirm.and(receiveTradeCreated);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("TRADE_CREATED");

		Trade trade = objectMapper.convertValue(envelope.payload(), Trade.class);
		assertThat(trade.symbol()).isEqualTo("EUR/USD");
		assertThat(trade.side()).isEqualTo(Side.SELL);
		assertThat(trade.price()).isEqualByComparingTo("1.0855");
		assertThat(trade.quantity()).isEqualByComparingTo("750000");
	}

	// The next two tests prove the #91 consumer path (Backend-Trading
	// Service -> RabbitMQ fanout -> monolith -> EventBus -> every connected
	// session), using two connections rather than one: TRADE_CREATED/
	// TRADE_REJECTED are broadcast to everyone (docs/protocol.md), unlike
	// PRICE_TICK's subscription filtering, so this is the meaningful proof
	// for this path specifically. No real CREATE_TRADE trigger exists yet
	// (that's #92) - the Backend-Trading Service's production side is
	// simulated the same way #90 simulated the Market Data Service's.
	@Test
	void tradeCreatedFromTheBackendTradingServiceReachesEveryConnectedSession() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> receivedByFirst = new AtomicReference<>();
		AtomicReference<String> receivedBySecond = new AtomicReference<>();
		com.sdp.contracts.Trade published = new com.sdp.contracts.Trade(
				java.util.UUID.randomUUID().toString(), "USD/JPY", com.sdp.contracts.Side.BUY,
				new BigDecimal("149.60"), new BigDecimal("500000"), Instant.now());

		Mono<Void> first = client.execute(wsUri(), sessionCookieHeader(),
				session -> session.receive().map(WebSocketMessage::getPayloadAsText)
						.filter(text -> text.contains("TRADE_CREATED"))
						.next().doOnNext(receivedByFirst::set).then());
		Mono<Void> second = client.execute(wsUri(), sessionCookieHeader(),
				session -> session.receive().map(WebSocketMessage::getPayloadAsText)
						.filter(text -> text.contains("TRADE_CREATED"))
						.next().doOnNext(receivedBySecond::set).then());
		Mono<Void> publish = Mono.fromRunnable(() -> publishToFanoutExchange("trade-created", published))
				.delaySubscription(Duration.ofMillis(200))
				.then();

		Mono.when(first, second, publish).block(Duration.ofSeconds(5));

		for (AtomicReference<String> received : List.of(receivedByFirst, receivedBySecond)) {
			Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
			assertThat(envelope.type()).isEqualTo("TRADE_CREATED");
			Trade trade = objectMapper.convertValue(envelope.payload(), Trade.class);
			assertThat(trade.id()).isEqualTo(published.id());
			assertThat(trade.symbol()).isEqualTo("USD/JPY");
			assertThat(trade.side()).isEqualTo(Side.BUY);
			assertThat(trade.price()).isEqualByComparingTo("149.60");
		}
	}

	@Test
	void tradeRejectedFromTheBackendTradingServiceReachesEveryConnectedSession() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> receivedByFirst = new AtomicReference<>();
		AtomicReference<String> receivedBySecond = new AtomicReference<>();
		com.sdp.contracts.TradeRejected published = new com.sdp.contracts.TradeRejected(
				"USD/JPY", com.sdp.contracts.Side.SELL, new BigDecimal("149.60"), new BigDecimal("0"),
				"quantity must be greater than zero");

		Mono<Void> first = client.execute(wsUri(), sessionCookieHeader(),
				session -> session.receive().map(WebSocketMessage::getPayloadAsText)
						.filter(text -> text.contains("TRADE_REJECTED"))
						.next().doOnNext(receivedByFirst::set).then());
		Mono<Void> second = client.execute(wsUri(), sessionCookieHeader(),
				session -> session.receive().map(WebSocketMessage::getPayloadAsText)
						.filter(text -> text.contains("TRADE_REJECTED"))
						.next().doOnNext(receivedBySecond::set).then());
		Mono<Void> publish = Mono.fromRunnable(() -> publishToFanoutExchange("trade-rejected", published))
				.delaySubscription(Duration.ofMillis(200))
				.then();

		Mono.when(first, second, publish).block(Duration.ofSeconds(5));

		for (AtomicReference<String> received : List.of(receivedByFirst, receivedBySecond)) {
			Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
			assertThat(envelope.type()).isEqualTo("TRADE_REJECTED");
			TradeRejected rejected = objectMapper.convertValue(envelope.payload(), TradeRejected.class);
			assertThat(rejected.symbol()).isEqualTo("USD/JPY");
			assertThat(rejected.reason()).isEqualTo("quantity must be greater than zero");
		}
	}

	@Test
	void cancellingAPendingTradeRepliesWithTradeCancelledToTheSubmitterOnly() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();
		TradeRequest request = new TradeRequest("GBP/USD", Side.BUY, new BigDecimal("1.2660"), new BigDecimal("300000"));

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			Flux<String> incoming = session.receive().map(WebSocketMessage::getPayloadAsText).share();

			Mono<Void> createThenCancel = sendEnvelope(session, "CREATE_TRADE", request)
					.delaySubscription(Duration.ofMillis(200))
					.then(incoming.filter(text -> text.contains("TRADE_PENDING")).next())
					.flatMap(this::extractPendingTradeId)
					.flatMap(id -> sendEnvelope(session, "CANCEL_TRADE", new PendingTradeId(id)));

			Mono<Void> receiveTradeCancelled = incoming
					.filter(text -> text.contains("TRADE_CANCELLED"))
					.next()
					.doOnNext(received::set)
					.then();

			return createThenCancel.and(receiveTradeCancelled);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("TRADE_CANCELLED");

		PendingTrade cancelled = objectMapper.convertValue(envelope.payload(), PendingTrade.class);
		assertThat(cancelled.symbol()).isEqualTo("GBP/USD");
		assertThat(cancelled.quantity()).isEqualByComparingTo("300000");
	}

	// Proves issue #79/ADR 0024: closing the connection with a trade still
	// PENDING cancels it automatically, without the client ever sending
	// CANCEL_TRADE itself. Never sending CANCEL_TRADE and simply letting the
	// handler Mono complete (rather than blocking on session.receive()
	// indefinitely) is what closes this connection - the same idiom
	// sendsHelloEnvelopeOnConnect already relies on.
	@Test
	void closingTheConnectionCancelsItsStillPendingTrade() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> pendingId = new AtomicReference<>();
		TradeRequest request = new TradeRequest("EUR/USD", Side.SELL, new BigDecimal("1.0860"), new BigDecimal("400000"));

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			Mono<Void> sendCreateTrade = sendEnvelope(session, "CREATE_TRADE", request)
					.delaySubscription(Duration.ofMillis(200));

			Mono<Void> receiveTradePending = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.filter(text -> text.contains("TRADE_PENDING"))
					.next()
					.flatMap(this::extractPendingTradeId)
					.doOnNext(pendingId::set)
					.then();

			return sendCreateTrade.and(receiveTradePending);
		}).block(Duration.ofSeconds(5));

		fakeTradingService.awaitCancellation(pendingId.get(), Duration.ofSeconds(5)).block();
	}

	private Mono<String> extractPendingTradeId(String tradePendingText) {
		return Mono.fromCallable(() -> {
			Envelope envelope = objectMapper.readValue(tradePendingText, Envelope.class);
			return objectMapper.convertValue(envelope.payload(), PendingTrade.class).id();
		});
	}

	@Test
	void rejectsAnInvalidTradeAndBroadcastsTradeRejected() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();
		TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("0"));

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			Mono<Void> sendCreateTrade = sendEnvelope(session, "CREATE_TRADE", request)
					.delaySubscription(Duration.ofMillis(200));

			Mono<Void> receiveTradeRejected = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.filter(text -> text.contains("TRADE_REJECTED"))
					.next()
					.doOnNext(received::set)
					.then();

			return sendCreateTrade.and(receiveTradeRejected);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("TRADE_REJECTED");

		TradeRejected rejection = objectMapper.convertValue(envelope.payload(), TradeRejected.class);
		assertThat(rejection.symbol()).isEqualTo("EUR/USD");
		assertThat(rejection.reason()).isEqualTo("quantity must be greater than zero");
	}

	@Test
	void answersGetTradeHistoryWithPersistedTrades() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		TradeRequest request = new TradeRequest("USD/JPY", Side.SELL, new BigDecimal("149.75"), new BigDecimal("250000"));
		com.sdp.contracts.TradeHistoryQuery query = new com.sdp.contracts.TradeHistoryQuery(50, null, null, null);
		AtomicReference<String> historyMessage = new AtomicReference<>();

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			// .share() so both branches below correlate off one subscription to
			// session.receive() - waiting for the actual TRADE_CREATED
			// confirmation (proving the Postgres write committed) rather than a
			// blind delay, which raced the real write and read back nothing.
			Flux<String> incoming = session.receive().map(WebSocketMessage::getPayloadAsText).share();

			Mono<Void> createThenRequestHistory = sendEnvelope(session, "CREATE_TRADE", request)
					.delaySubscription(Duration.ofMillis(200))
					.then(incoming.filter(text -> text.contains("TRADE_PENDING")).next())
					.flatMap(this::extractPendingTradeId)
					.flatMap(id -> sendEnvelope(session, "CONFIRM_TRADE", new PendingTradeId(id)))
					.then(incoming.filter(text -> text.contains("TRADE_CREATED")).next())
					.then(sendEnvelope(session, "GET_TRADE_HISTORY", query, "correlation-1"));

			Mono<Void> receiveHistory = incoming
					.filter(text -> text.contains("TRADE_HISTORY"))
					.next()
					.doOnNext(historyMessage::set)
					.then();

			return createThenRequestHistory.and(receiveHistory);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(historyMessage.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("TRADE_HISTORY");
		assertThat(envelope.correlationId()).isEqualTo("correlation-1");

		// Other tests in this class persist trades against the same shared
		// container/table, so history isn't necessarily just this one trade -
		// assert this trade is present rather than asserting an exact list.
		com.sdp.contracts.TradeHistoryPage page = objectMapper.convertValue(envelope.payload(), com.sdp.contracts.TradeHistoryPage.class);
		assertThat(page.rows()).anySatisfy(trade -> {
			assertThat(trade.symbol()).isEqualTo("USD/JPY");
			assertThat(trade.side()).isEqualTo(com.sdp.contracts.Side.SELL);
			assertThat(trade.quantity()).isEqualByComparingTo("250000");
		});
	}

	// Proves the query payload actually round-trips over the wire and through
	// RabbitMQ (issue #131) - not that filtering itself is correct, which is
	// trading-service's own TradeHistoryQueryService and its issue #130 tests.
	@Test
	void answersAFilteredGetTradeHistoryRequestWithOnlyMatchingTrades() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		TradeRequest request = new TradeRequest("GBP/USD", Side.BUY, new BigDecimal("1.2670"), new BigDecimal("600000"));
		com.sdp.contracts.TradeHistoryQuery filteredQuery = new com.sdp.contracts.TradeHistoryQuery(
				50, null, new com.sdp.contracts.TradeSort("timestamp", true),
				List.of(new com.sdp.contracts.TradeFilter("symbol", "equals", "GBP/USD", null)));
		AtomicReference<String> historyMessage = new AtomicReference<>();

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			Flux<String> incoming = session.receive().map(WebSocketMessage::getPayloadAsText).share();

			Mono<Void> createThenRequestHistory = sendEnvelope(session, "CREATE_TRADE", request)
					.delaySubscription(Duration.ofMillis(200))
					.then(incoming.filter(text -> text.contains("TRADE_PENDING")).next())
					.flatMap(this::extractPendingTradeId)
					.flatMap(id -> sendEnvelope(session, "CONFIRM_TRADE", new PendingTradeId(id)))
					.then(incoming.filter(text -> text.contains("TRADE_CREATED")).next())
					.then(sendEnvelope(session, "GET_TRADE_HISTORY", filteredQuery, "correlation-filtered"));

			Mono<Void> receiveHistory = incoming
					.filter(text -> text.contains("TRADE_HISTORY"))
					.next()
					.doOnNext(historyMessage::set)
					.then();

			return createThenRequestHistory.and(receiveHistory);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(historyMessage.get(), Envelope.class);
		com.sdp.contracts.TradeHistoryPage page = objectMapper.convertValue(envelope.payload(), com.sdp.contracts.TradeHistoryPage.class);
		assertThat(page.rows()).isNotEmpty();
		assertThat(page.rows()).allSatisfy(trade -> assertThat(trade.symbol()).isEqualTo("GBP/USD"));
	}

	// Proves the correlation mechanism itself (issue #131): two
	// GET_TRADE_HISTORY requests in flight at once on the same connection,
	// each carrying a distinct correlationId and a distinct filter, both
	// resolve to their own caller rather than crossing wires.
	@Test
	void concurrentGetTradeHistoryRequestsEachResolveToTheirOwnCorrelationId() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		TradeRequest eurRequest = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0870"), new BigDecimal("200000"));
		TradeRequest jpyRequest = new TradeRequest("USD/JPY", Side.SELL, new BigDecimal("149.90"), new BigDecimal("300000"));
		com.sdp.contracts.TradeHistoryQuery eurQuery = new com.sdp.contracts.TradeHistoryQuery(
				50, null, null, List.of(new com.sdp.contracts.TradeFilter("symbol", "equals", "EUR/USD", null)));
		com.sdp.contracts.TradeHistoryQuery jpyQuery = new com.sdp.contracts.TradeHistoryQuery(
				50, null, null, List.of(new com.sdp.contracts.TradeFilter("symbol", "equals", "USD/JPY", null)));
		Map<String, String> repliesByCorrelationId = new ConcurrentHashMap<>();

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			// .publish()+.connect() rather than .share(): the two
			// createAndConfirm() calls below subscribe to (and fully drain)
			// `incoming` one after another, with nothing else subscribed in
			// between - .share()'s refCount would drop to zero and try to
			// re-subscribe to session.receive() for the second call, which
			// Reactor Netty's WebSocketSession rejects outright
			// ("Rejecting additional inbound receiver"). connect() once,
			// up front, decouples the single underlying subscription from
			// however many downstream subscribers come and go afterward.
			ConnectableFlux<String> incoming = session.receive().map(WebSocketMessage::getPayloadAsText).publish();
			incoming.connect();

			Mono<Void> seedBothTrades = createAndConfirm(session, incoming, eurRequest)
					.then(createAndConfirm(session, incoming, jpyRequest));

			Mono<Void> requestBothHistoriesConcurrently = Mono.when(
					sendEnvelope(session, "GET_TRADE_HISTORY", eurQuery, "correlation-eur"),
					sendEnvelope(session, "GET_TRADE_HISTORY", jpyQuery, "correlation-jpy"));

			Mono<Void> collectBothReplies = incoming
					.filter(text -> text.contains("TRADE_HISTORY"))
					.map(text -> objectMapper.readValue(text, Envelope.class))
					.doOnNext(envelope -> repliesByCorrelationId.put(envelope.correlationId(), text(envelope)))
					.take(2)
					.then();

			return seedBothTrades.then(requestBothHistoriesConcurrently.and(collectBothReplies));
		}).block(Duration.ofSeconds(5));

		com.sdp.contracts.TradeHistoryPage eurPage = objectMapper.convertValue(
				objectMapper.readValue(repliesByCorrelationId.get("correlation-eur"), Envelope.class).payload(),
				com.sdp.contracts.TradeHistoryPage.class);
		com.sdp.contracts.TradeHistoryPage jpyPage = objectMapper.convertValue(
				objectMapper.readValue(repliesByCorrelationId.get("correlation-jpy"), Envelope.class).payload(),
				com.sdp.contracts.TradeHistoryPage.class);

		assertThat(eurPage.rows()).allSatisfy(trade -> assertThat(trade.symbol()).isEqualTo("EUR/USD"));
		assertThat(jpyPage.rows()).allSatisfy(trade -> assertThat(trade.symbol()).isEqualTo("USD/JPY"));
	}

	private String text(Envelope envelope) {
		return objectMapper.writeValueAsString(envelope);
	}

	private Mono<Void> createAndConfirm(WebSocketSession session, Flux<String> incoming, TradeRequest request) {
		return sendEnvelope(session, "CREATE_TRADE", request)
				.then(incoming.filter(text -> text.contains("TRADE_PENDING")).next())
				.flatMap(this::extractPendingTradeId)
				.flatMap(id -> sendEnvelope(session, "CONFIRM_TRADE", new PendingTradeId(id)))
				.then(incoming.filter(text -> text.contains("TRADE_CREATED")).next())
				.then();
	}

	private Mono<Void> sendEnvelope(WebSocketSession session, String type, Object payload) {
		return sendEnvelope(session, type, payload, null);
	}

	private Mono<Void> sendEnvelope(WebSocketSession session, String type, Object payload, String correlationId) {
		return Mono.fromCallable(() -> objectMapper.writeValueAsString(new Envelope(type, payload, correlationId)))
				.map(session::textMessage)
				.flatMap(message -> session.send(Mono.just(message)));
	}
}
