package com.sdp.marketdata;

import com.sdp.contracts.PriceTick;
import com.sdp.contracts.SymbolCatalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * Generates simulated FX price ticks on a fixed interval and publishes each
 * one to the "priceTicks-out-0" binding (a RabbitMQ fanout exchange, see
 * ADR 0022) via StreamBridge - the single source of truth for ticks as of
 * #90, replacing the monolith's own in-process generator.
 */
@Service
public class MarketDataService {

    // Seed mid-price per symbol in SymbolCatalog.ALL_SYMBOLS (issue #159) -
    // a MarketDataServiceTest assertion keeps this map's keys in sync with
    // the catalog, since a seed price is this service's own concern and
    // isn't itself part of the shared catalog.
    private static final Map<String, BigDecimal> BASE_PRICES = Map.ofEntries(
            Map.entry("EUR/USD", new BigDecimal("1.0850")),
            Map.entry("GBP/USD", new BigDecimal("1.2650")),
            Map.entry("USD/JPY", new BigDecimal("149.50")),
            Map.entry("USD/CHF", new BigDecimal("0.8850")),
            Map.entry("AUD/USD", new BigDecimal("0.6550")),
            Map.entry("USD/CAD", new BigDecimal("1.3650")),
            Map.entry("NZD/USD", new BigDecimal("0.6050")),
            Map.entry("EUR/GBP", new BigDecimal("0.8580")),
            Map.entry("EUR/JPY", new BigDecimal("162.20")),
            Map.entry("GBP/JPY", new BigDecimal("189.10")),
            Map.entry("EUR/CHF", new BigDecimal("0.9600")),
            Map.entry("AUD/JPY", new BigDecimal("97.90")),
            Map.entry("USD/CNH", new BigDecimal("7.2500")),
            Map.entry("USD/MXN", new BigDecimal("18.20")));

    static {
        if (!BASE_PRICES.keySet().equals(Set.copyOf(SymbolCatalog.ALL_SYMBOLS))) {
            throw new IllegalStateException(
                    "BASE_PRICES must have exactly one seed price per SymbolCatalog.ALL_SYMBOLS entry");
        }
    }

    private static final BigDecimal SPREAD = new BigDecimal("0.0002");
    private static final BigDecimal MAX_STEP = new BigDecimal("0.0005");
    private static final Duration TICK_INTERVAL = Duration.ofSeconds(1);
    private static final int SCALE = 4;
    private static final String BINDING_NAME = "priceTicks-out-0";

    private final Map<String, BigDecimal> midPrices = new ConcurrentHashMap<>(BASE_PRICES);
    private final StreamBridge streamBridge;

    public MarketDataService(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    // Started on ApplicationReadyEvent, not from the constructor or
    // @PostConstruct, for two reasons: a plain `new
    // MarketDataService(streamBridge)` in a unit test stays side-effect-free
    // (tests can still call priceTicks() directly under virtual time), and -
    // found live, not by inspection - StreamBridge's underlying messaging
    // channel isn't marked "running" yet during @PostConstruct/bean
    // initialization. Flux.interval's first emission (at TICK_INTERVAL, well
    // before Spring Boot's own full startup consistently completes) hit that
    // window and was silently dropped (Reactor's default onErrorDropped)
    // every time in live docker-compose verification. ApplicationReadyEvent
    // fires only once the whole context, including messaging infra, is
    // actually up.
    @EventListener(ApplicationReadyEvent.class)
    void startPublishing() {
        priceTicks().subscribe(tick -> streamBridge.send(BINDING_NAME, tick));
    }

    Flux<PriceTick> priceTicks() {
        return Flux.interval(TICK_INTERVAL)
                .flatMap(tick -> Flux.fromIterable(midPrices.keySet()))
                .map(this::nextTick);
    }

    private PriceTick nextTick(String symbol) {
        BigDecimal mid = midPrices.compute(symbol, (s, current) -> randomWalk(current));
        BigDecimal halfSpread = SPREAD.divide(BigDecimal.valueOf(2));
        BigDecimal bid = mid.subtract(halfSpread).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal ask = mid.add(halfSpread).setScale(SCALE, RoundingMode.HALF_UP);
        return new PriceTick(symbol, bid, ask, Instant.now());
    }

    private BigDecimal randomWalk(BigDecimal current) {
        double step = ThreadLocalRandom.current().nextDouble(-1, 1) * MAX_STEP.doubleValue();
        return current.add(BigDecimal.valueOf(step)).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
