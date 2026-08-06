package com.sdp.trade;

import com.sdp.common.Side;
import com.sdp.common.Trade;
import com.sdp.eventbus.EventBus;
import com.sdp.market.MarketDataService;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeServiceTest {

    private final EventBus eventBus = new EventBus();
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeService service = new TradeService(new MarketDataService(eventBus), eventBus, tradeRepository);

    @BeforeEach
    void echoBackWhateverIsSaved() {
        when(tradeRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void createTradeSavesAndReturnsATrade() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0851"), new BigDecimal("1000000"));

        Trade trade = service.createTrade(request).block();

        assertThat(trade.id()).isNotBlank();
        assertThat(trade.symbol()).isEqualTo("EUR/USD");
        assertThat(trade.side()).isEqualTo(Side.BUY);
        assertThat(trade.price()).isEqualTo(request.price());
        assertThat(trade.quantity()).isEqualTo(request.quantity());
        assertThat(trade.timestamp()).isNotNull();
    }

    @Test
    void createTradeEmitsOnEventBus() {
        TradeRequest request = new TradeRequest("GBP/USD", Side.SELL, new BigDecimal("1.2650"), new BigDecimal("500000"));

        StepVerifier.create(eventBus.events())
                .then(() -> service.createTrade(request).subscribe())
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(Trade.class);
                    assertThat(((Trade) event).symbol()).isEqualTo("GBP/USD");
                })
                .thenCancel()
                .verify();
    }

    @Test
    void eachTradeGetsAUniqueId() {
        TradeRequest request = new TradeRequest("USD/JPY", Side.BUY, new BigDecimal("149.50"), new BigDecimal("100000"));

        Trade first = service.createTrade(request).block();
        Trade second = service.createTrade(request).block();

        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    void rejectsATradeWithNonPositiveQuantity() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0851"), new BigDecimal("0"));

        Trade trade = service.createTrade(request).block();

        assertThat(trade).isNull();
    }

    @Test
    void rejectsATradeWithNonPositiveQuantityAndEmitsOnEventBus() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.SELL, new BigDecimal("1.0851"), new BigDecimal("-100"));

        StepVerifier.create(eventBus.events())
                .then(() -> service.createTrade(request).subscribe())
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(TradeRejected.class);
                    TradeRejected rejection = (TradeRejected) event;
                    assertThat(rejection.symbol()).isEqualTo("EUR/USD");
                    assertThat(rejection.reason()).isEqualTo("quantity must be greater than zero");
                })
                .thenCancel()
                .verify();
    }

    @Test
    void rejectsATradeForAnUnknownSymbol() {
        TradeRequest request = new TradeRequest("XAU/USD", Side.BUY, new BigDecimal("2000"), new BigDecimal("100"));

        StepVerifier.create(eventBus.events())
                .then(() -> service.createTrade(request).subscribe())
                .assertNext(event -> assertThat(((TradeRejected) event).reason()).isEqualTo("unknown symbol: XAU/USD"))
                .thenCancel()
                .verify();
    }

    @Test
    void historyReturnsPersistedTradesInTimestampOrder() {
        Trade older = new Trade("1", "EUR/USD", Side.BUY, new BigDecimal("1.08"), new BigDecimal("100"), Instant.parse("2026-01-01T00:00:00Z"));
        Trade newer = new Trade("2", "EUR/USD", Side.SELL, new BigDecimal("1.09"), new BigDecimal("200"), Instant.parse("2026-01-01T00:01:00Z"));
        when(tradeRepository.findAllByOrderByTimestampAsc()).thenReturn(Flux.just(older, newer));

        StepVerifier.create(service.history())
                .expectNext(older)
                .expectNext(newer)
                .verifyComplete();
    }
}
