package com.sdp.trade;

import com.sdp.audit.AuditService;
import com.sdp.common.Side;
import com.sdp.common.Trade;
import com.sdp.eventbus.EventBus;
import com.sdp.market.MarketDataService;
import com.sdp.session.Session;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Handles the two-step execution workflow (see ADR 0018): requestTrade
 * validates a CREATE_TRADE request and holds it as a PendingTrade, or
 * rejects it immediately with a reason; confirmTrade persists a previously
 * requested trade and publishes it; cancelTrade discards one. Publishes
 * outcomes to the EventBus for the WebSocket layer to broadcast, and
 * records each terminal outcome as an audit event (see ADR 0019). Does not
 * generate prices.
 *
 * Also the monolith's temporary consumer of TRADE_CREATED/TRADE_REJECTED
 * broadcasts from the Backend/Trading Service's RabbitMQ fanout exchanges
 * (see ADR 0022's update, issue #91) - relays each onto the same EventBus,
 * so SdpWebSocketHandler's existing broadcast-to-all-sessions delivery is
 * unchanged. Nothing calls the Trading Service for a real trade yet
 * (that's #92); this consumer exists so the outbound path is genuinely
 * live-testable now via a manually-triggered publish.
 */
@Service
public class TradeService {

    private final MarketDataService marketDataService;
    private final EventBus eventBus;
    private final TradeRepository tradeRepository;
    private final AuditService auditService;
    private final Map<String, PendingTrade> pendingTrades = new ConcurrentHashMap<>();

    public TradeService(MarketDataService marketDataService, EventBus eventBus, TradeRepository tradeRepository, AuditService auditService) {
        this.marketDataService = marketDataService;
        this.eventBus = eventBus;
        this.tradeRepository = tradeRepository;
        this.auditService = auditService;
    }

    public Mono<PendingTrade> requestTrade(TradeRequest request, Session session) {
        Optional<String> rejectionReason = validate(request);
        if (rejectionReason.isPresent()) {
            publishRejection(request, rejectionReason.get());
            return auditService.record(session.id(), session.username(), "TRADE_REJECTED", describe(request) + " - " + rejectionReason.get())
                    .then(Mono.empty());
        }
        PendingTrade pending = buildPendingTrade(request);
        pendingTrades.put(pending.id(), pending);
        return Mono.just(pending);
    }

    public Mono<Trade> confirmTrade(String id, Session session) {
        PendingTrade pending = pendingTrades.remove(id);
        if (pending == null) {
            return Mono.empty();
        }
        return tradeRepository.save(buildTrade(pending))
                .doOnNext(eventBus::publish)
                .flatMap(trade -> auditService.record(session.id(), session.username(), "TRADE_EXECUTED", describe(trade)).thenReturn(trade));
    }

    public Mono<PendingTrade> cancelTrade(String id, Session session) {
        PendingTrade pending = pendingTrades.remove(id);
        if (pending == null) {
            return Mono.empty();
        }
        return auditService.record(session.id(), session.username(), "TRADE_CANCELLED", describe(pending))
                .thenReturn(pending);
    }

    public Flux<Trade> history() {
        return tradeRepository.findAllByOrderByTimestampAsc();
    }

    @Bean
    public Consumer<com.sdp.contracts.Trade> tradeCreatedConsumer() {
        return trade -> eventBus.publish(new Trade(
                trade.id(), trade.symbol(), Side.valueOf(trade.side().name()), trade.price(), trade.quantity(), trade.timestamp()));
    }

    @Bean
    public Consumer<com.sdp.contracts.TradeRejected> tradeRejectedConsumer() {
        return rejected -> eventBus.publish(new TradeRejected(
                rejected.symbol(), Side.valueOf(rejected.side().name()), rejected.price(), rejected.quantity(), rejected.reason()));
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

    private String describe(TradeRequest request) {
        return request.side() + " " + request.quantity() + " " + request.symbol() + " @ " + request.price();
    }

    private String describe(PendingTrade pending) {
        return pending.side() + " " + pending.quantity() + " " + pending.symbol() + " @ " + pending.price();
    }

    private String describe(Trade trade) {
        return trade.side() + " " + trade.quantity() + " " + trade.symbol() + " @ " + trade.price();
    }
}
