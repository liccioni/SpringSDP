package com.sdp.trade;

import com.sdp.common.Trade;
import com.sdp.eventbus.EventBus;
import com.sdp.market.MarketDataService;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Handles CREATE_TRADE requests: validates them, then either persists a
 * Trade via TradeRepository, or rejects it with a reason. Publishes both
 * outcomes to the EventBus for the WebSocket layer to broadcast. Does not
 * generate prices.
 */
@Service
public class TradeService {

    private final MarketDataService marketDataService;
    private final EventBus eventBus;
    private final TradeRepository tradeRepository;

    public TradeService(MarketDataService marketDataService, EventBus eventBus, TradeRepository tradeRepository) {
        this.marketDataService = marketDataService;
        this.eventBus = eventBus;
        this.tradeRepository = tradeRepository;
    }

    public Mono<Trade> createTrade(TradeRequest request) {
        Optional<String> rejectionReason = validate(request);
        if (rejectionReason.isPresent()) {
            publishRejection(request, rejectionReason.get());
            return Mono.empty();
        }
        return tradeRepository.save(buildTrade(request))
                .doOnNext(eventBus::publish);
    }

    public Flux<Trade> history() {
        return tradeRepository.findAllByOrderByTimestampAsc();
    }

    private Trade buildTrade(TradeRequest request) {
        return new Trade(
                UUID.randomUUID().toString(),
                request.symbol(),
                request.side(),
                request.price(),
                request.quantity(),
                Instant.now());
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
