package com.sdp.websocket;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

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

	@Test
	void streamsPriceTicksForASubscribedSymbol() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();

		client.execute(URI.create("ws://localhost:" + port + "/ws"), session -> {
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

		client.execute(URI.create("ws://localhost:" + port + "/ws"),
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

		client.execute(URI.create("ws://localhost:" + port + "/ws"), session -> {
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

		client.execute(URI.create("ws://localhost:" + port + "/ws"), session -> {
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

		client.execute(URI.create("ws://localhost:" + port + "/ws"), session -> {
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

	private Mono<Void> sendEnvelope(WebSocketSession session, String type, Object payload) {
		return Mono.fromCallable(() -> objectMapper.writeValueAsString(new Envelope(type, payload)))
				.map(session::textMessage)
				.flatMap(message -> session.send(Mono.just(message)));
	}
}
