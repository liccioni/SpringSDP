package com.sdp.market;

import com.sdp.common.PriceTick;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * Generates simulated FX price ticks on a fixed interval. Does not create trades.
 */
@Service
public class MarketDataService {

    private static final Map<String, BigDecimal> BASE_PRICES = Map.of(
            "EUR/USD", new BigDecimal("1.0850"),
            "GBP/USD", new BigDecimal("1.2650"),
            "USD/JPY", new BigDecimal("149.50"));
    private static final BigDecimal SPREAD = new BigDecimal("0.0002");
    private static final BigDecimal MAX_STEP = new BigDecimal("0.0005");
    private static final Duration TICK_INTERVAL = Duration.ofSeconds(1);
    private static final int SCALE = 4;

    private final Map<String, BigDecimal> midPrices = new ConcurrentHashMap<>(BASE_PRICES);

    public Flux<PriceTick> priceTicks() {
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
