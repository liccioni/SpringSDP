package com.sdp.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.PostgresIntegrationTest;
import com.sdp.auth.AuthService;
import com.sdp.common.PriceTick;
import com.sdp.common.Side;
import com.sdp.common.Trade;
import com.sdp.market.SubscriptionRequest;
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
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class SdpWebSocketHandlerIT implements PostgresIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AuthService authService;

	private String token;

	@BeforeEach
	void logIn() {
		token = authService.login("trader1", "trader1pass").block();
	}

	private URI wsUri() {
		return URI.create("ws://localhost:" + port + "/ws?token=" + token);
	}

	@Test
	void rejectsConnectionWithNoToken() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		List<String> received = new ArrayList<>();

		client.execute(URI.create("ws://localhost:" + port + "/ws"),
				session -> session.receive()
						.map(WebSocketMessage::getPayloadAsText)
						.doOnNext(received::add)
						.then())
				.block(Duration.ofSeconds(5));

		assertThat(received).isEmpty();
	}

	@Test
	void rejectsConnectionWithInvalidToken() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		List<String> received = new ArrayList<>();

		client.execute(URI.create("ws://localhost:" + port + "/ws?token=not-a-real-token"),
				session -> session.receive()
						.map(WebSocketMessage::getPayloadAsText)
						.doOnNext(received::add)
						.then())
				.block(Duration.ofSeconds(5));

		assertThat(received).isEmpty();
	}

	@Test
	void sendsHelloEnvelopeOnConnect() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();

		client.execute(wsUri(),
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

	@Test
	void streamsPriceTicksForASubscribedSymbol() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();

		client.execute(wsUri(), session -> {
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

		client.execute(wsUri(),
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

		client.execute(wsUri(), session -> {
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
	void createsTradeAndBroadcastsTradeCreated() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();
		TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("1000000"));

		client.execute(wsUri(), session -> {
			Mono<Void> sendCreateTrade = sendEnvelope(session, "CREATE_TRADE", request)
					.delaySubscription(Duration.ofMillis(200));

			Mono<Void> receiveTradeCreated = session.receive()
					.map(WebSocketMessage::getPayloadAsText)
					.filter(text -> text.contains("TRADE_CREATED"))
					.next()
					.doOnNext(received::set)
					.then();

			return sendCreateTrade.and(receiveTradeCreated);
		}).block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("TRADE_CREATED");

		Trade trade = objectMapper.convertValue(envelope.payload(), Trade.class);
		assertThat(trade.symbol()).isEqualTo("EUR/USD");
		assertThat(trade.side()).isEqualTo(Side.BUY);
		assertThat(trade.price()).isEqualByComparingTo("1.0850");
		assertThat(trade.quantity()).isEqualByComparingTo("1000000");
	}

	@Test
	void rejectsAnInvalidTradeAndBroadcastsTradeRejected() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();
		TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("0"));

		client.execute(wsUri(), session -> {
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

		client.execute(wsUri(), session -> {
			// .share() so both branches below correlate off one subscription to
			// session.receive() - waiting for the actual TRADE_CREATED
			// confirmation (proving the Postgres write committed) rather than a
			// blind delay, which raced the real write and read back nothing.
			Flux<String> incoming = session.receive().map(WebSocketMessage::getPayloadAsText).share();

			Mono<Void> createThenRequestHistory = sendEnvelope(session, "CREATE_TRADE", request)
					.delaySubscription(Duration.ofMillis(200))
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
