package com.sdp.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.common.PriceTick;
import com.sdp.common.Side;
import com.sdp.common.Trade;
import com.sdp.trade.TradeRejected;
import com.sdp.trade.TradeRequest;

import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.WebSocketMessage;
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
	void streamsPriceTicksFromMarketData() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();

		client.execute(URI.create("ws://localhost:" + port + "/ws"),
				session -> session.receive()
						.map(WebSocketMessage::getPayloadAsText)
						.filter(text -> text.contains("PRICE_TICK"))
						.next()
						.doOnNext(received::set)
						.then())
				.block(Duration.ofSeconds(5));

		Envelope envelope = objectMapper.readValue(received.get(), Envelope.class);
		assertThat(envelope.type()).isEqualTo("PRICE_TICK");

		PriceTick tick = objectMapper.convertValue(envelope.payload(), PriceTick.class);
		assertThat(tick.symbol()).isNotBlank();
		assertThat(tick.bid()).isLessThan(tick.ask());
	}

	@Test
	void createsTradeAndBroadcastsTradeCreated() throws Exception {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
		AtomicReference<String> received = new AtomicReference<>();
		TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("1000000"));

		client.execute(URI.create("ws://localhost:" + port + "/ws"), session -> {
			Mono<Void> sendCreateTrade = Mono.fromCallable(() -> objectMapper.writeValueAsString(new Envelope("CREATE_TRADE", request)))
					.map(session::textMessage)
					.delayElement(Duration.ofMillis(200))
					.flatMap(message -> session.send(Mono.just(message)));

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
			Mono<Void> sendCreateTrade = Mono.fromCallable(() -> objectMapper.writeValueAsString(new Envelope("CREATE_TRADE", request)))
					.map(session::textMessage)
					.delayElement(Duration.ofMillis(200))
					.flatMap(message -> session.send(Mono.just(message)));

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
}
