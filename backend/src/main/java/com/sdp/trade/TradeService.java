package com.sdp.trade;

import com.sdp.common.Trade;
import com.sdp.eventbus.EventBus;
import com.sdp.market.MarketDataService;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Handles the two-step execution workflow (see ADR 0018): requestTrade
 * validates a CREATE_TRADE request and holds it as a PendingTrade, or
 * rejects it immediately with a reason; confirmTrade persists a previously
 * requested trade and publishes it; cancelTrade discards one. Publishes
 * outcomes to the EventBus for the WebSocket layer to broadcast. Does not
 * generate prices.
 */
@Service
public class TradeService {

    private final MarketDataService marketDataService;
    private final EventBus eventBus;
    private final TradeRepository tradeRepository;
    private final Map<String, PendingTrade> pendingTrades = new ConcurrentHashMap<>();

    public TradeService(MarketDataService marketDataService, EventBus eventBus, TradeRepository tradeRepository) {
        this.marketDataService = marketDataService;
        this.eventBus = eventBus;
        this.tradeRepository = tradeRepository;
    }

    public Optional<PendingTrade> requestTrade(TradeRequest request) {
        Optional<String> rejectionReason = validate(request);
        if (rejectionReason.isPresent()) {
            publishRejection(request, rejectionReason.get());
            return Optional.empty();
        }
        PendingTrade pending = buildPendingTrade(request);
        pendingTrades.put(pending.id(), pending);
        return Optional.of(pending);
    }

    public Mono<Trade> confirmTrade(String id) {
        PendingTrade pending = pendingTrades.remove(id);
        if (pending == null) {
            return Mono.empty();
        }
        return tradeRepository.save(buildTrade(pending))
                .doOnNext(eventBus::publish);
    }

    public Optional<PendingTrade> cancelTrade(String id) {
        return Optional.ofNullable(pendingTrades.remove(id));
    }

    public Flux<Trade> history() {
        return tradeRepository.findAllByOrderByTimestampAsc();
    }

    private PendingTrade buildPendingTrade(TradeRequest request) {
        return new PendingTrade(
                UUID.randomUUID().toString(),
                request.symbol(),
                request.side(),
                request.price(),
                request.quantity(),
                Instant.now());
    }

    private Trade buildTrade(PendingTrade pending) {
        return new Trade(pending.id(), pending.symbol(), pending.side(), pending.price(), pending.quantity(), Instant.now());
    }

    private void publishRejection(TradeRequest request, String reason) {
        eventBus.publish(new TradeRejected(request.symbol(), request.side(), request.price(), request.quantity(), reason));
    }

    private Optional<String> validate(TradeRequest request) {
        if (request.quantity().signum() <= 0) {
            return Optional.of("quantity must be greater than zero");
        }
        if (!marketDataService.symbols().contains(request.symbol())) {
            return Optional.of("unknown symbol: " + request.symbol());
        }
        return Optional.empty();
    }
}
