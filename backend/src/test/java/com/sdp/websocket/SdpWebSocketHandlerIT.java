package com.sdp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;

import com.sdp.PostgresIntegrationTest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
class SdpWebSocketHandlerIT implements PostgresIntegrationTest, RedisIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReactiveRedisSessionRepository sessionRepository;

	private String sessionCookie;

	@BeforeEach
	void logIn() {
		sessionCookie = authenticatedSessionId("trader1");
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

	@Test
	void streamsPriceTicksForASubscribedSymbol() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			Mono<Void> sendSubscribe = sendEnvelope(session, "SUBSCRIBE", new SubscriptionRequest("EUR/USD"))
					.delaySubscription(Duration.ofMillis(200));

			Mono<Void> receiveTick = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.filter(text -> text.contains("PRICE_TICK"))
					.next()
					.doOnNext(received::set)
					.then();

			return sendSubscribe.and(receiveTick);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("PRICE_TICK");

		PriceTick tick = objectMapper.convertValue(envelope.payload(), PriceTick.class);
		assertThat(tick.symbol()).isEqualTo("EUR/USD");
		assertThat(tick.bid()).isLessThan(tick.ask());
	}

	@Test
	void receivesNoPriceTicksBeforeSubscribing() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		List<String> received = new ArrayList<>();

		client.execute(wsUri(), sessionCookieHeader(),
				session -> session.receive()
						.map(WebSocketMessage::getPayloadAsText)
						.take(Duration.ofMillis(1500))
						.doOnNext(received::add)
						.then())
				.block(Duration.ofSeconds(5));

		assertThat(received).noneMatch(text -> text.contains("PRICE_TICK"));
	}

	@Test
	void receivesNoPriceTicksForASymbolItSubscribedThenUnsubscribedFrom() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		List<String> received = new ArrayList<>();

		client.execute(wsUri(), sessionCookieHeader(), session -> {
			// Subscribe and immediately unsubscribe, well inside one tick interval
			// (1s), so no PRICE_TICK for this symbol can be delivered in between.
			Mono<Void> subscribeThenUnsubscribe = sendEnvelope(session, "SUBSCRIBE", new SubscriptionRequest("GBP/USD"))
					.then(sendEnvelope(session, "UNSUBSCRIBE", new SubscriptionRequest("GBP/USD")));

			Mono<Void> collect = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.filter(text -> text.contains("PRICE_TICK"))
					.take(Duration.ofMillis(1500))
					.doOnNext(received::add)
					.then();

			return subscribeThenUnsubscribe.and(collect);
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
					.then(sendEnvelope(session, "GET_TRADE_HISTORY", null));

			Mono<Void> receiveHistory = incoming
					.filter(text -> text.contains("TRADE_HISTORY"))
					.next()
					.doOnNext(historyMessage::set)
					.then();

			return createThenRequestHistory.and(receiveHistory);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(historyMessage.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("TRADE_HISTORY");

		// Other tests in this class persist trades against the same shared
		// container/table, so history isn't necessarily just this one trade -
		// assert this trade is present rather than asserting an exact list.
		Trade[] history = objectMapper.convertValue(envelope.payload(), Trade[].class);
		assertThat(history).anySatisfy(trade -> {
			assertThat(trade.symbol()).isEqualTo("USD/JPY");
			assertThat(trade.side()).isEqualTo(Side.SELL);
			assertThat(trade.quantity()).isEqualByComparingTo("250000");
		});
	}

	private Mono<Void> sendEnvelope(WebSocketSession session, String type, Object payload) {
		return Mono.fromCallable(() -> objectMapper.writeValueAsString(new Envelope(type, payload)))
				.map(session::textMessage)
				.flatMap(message -> session.send(Mono.just(message)));
	}
}
