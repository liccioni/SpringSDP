package com.sdp.trading;

import com.sdp.audit.AuditService;
import com.sdp.contracts.PendingTrade;
import com.sdp.contracts.PendingTradeId;
import com.sdp.contracts.SymbolCatalog;
import com.sdp.contracts.TradeCommand;
import com.sdp.contracts.TradeCommandResult;
import com.sdp.contracts.TradeHistoryQuery;
import com.sdp.contracts.TradeRequest;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import tools.jackson.databind.ObjectMapper;

/**
 * Handles the "trade-requests" correlated request/reply pair (see ADR
 * 0022's update, issue #92): CREATE_TRADE validates and holds a
 * PendingTrade (ADR 0018), or rejects immediately; CONFIRM_TRADE persists
 * a previously requested trade; CANCEL_TRADE discards one; GET_TRADE_HISTORY
 * answers with one cursor-paginated, filterable, sortable page of history
 * (TradeHistoryQueryService, issue #130) rather than the full persisted
 * history. Every reply echoes the request's correlationId on
 * "trade-responses" so the Gateway (today, the monolith - see ADR 0022)
 * can route it back to the specific connection that asked.
 *
 * CONFIRM_TRADE now also gets a correlated TRADE_CREATED reply (issue
 * #152, ADR 0028) - the submitting connection's own UI resolution
 * (clearing its pending-trade prompt, showing an execution toast) depends
 * on reliably receiving this regardless of any subscription state. This is
 * in addition to, not instead of, the pre-existing TRADE_CREATED broadcast
 * on the "trade-created" fanout exchange, which the Gateway still relays -
 * but now only to sessions subscribed to the trade blotter (see ADR 0028).
 * CREATE_TRADE's rejection reply now carries the real TradeRejected
 * payload instead of null, and no longer also broadcasts - rejected trades
 * are never persisted, so there's nothing for a blotter subscriber to see.
 */
@Service
public class TradeService {

    private static final Set<String> KNOWN_SYMBOLS = Set.copyOf(SymbolCatalog.ALL_SYMBOLS);
    private static final String TRADE_CREATED_BINDING = "tradeCreated-out-0";
    private static final String TRADE_RESPONSES_BINDING = "tradeResponses-out-0";

    private final TradeRepository tradeRepository;
    private final TradeHistoryQueryService tradeHistoryQueryService;
    private final AuditService auditService;
    private final StreamBridge streamBridge;
    private final ObjectMapper objectMapper;
    private final Map<String, PendingTrade> pendingTrades = new ConcurrentHashMap<>();

    public TradeService(
            TradeRepository tradeRepository,
            TradeHistoryQueryService tradeHistoryQueryService,
            AuditService auditService,
            StreamBridge streamBridge,
            ObjectMapper objectMapper) {
        this.tradeRepository = tradeRepository;
        this.tradeHistoryQueryService = tradeHistoryQueryService;
        this.auditService = auditService;
        this.streamBridge = streamBridge;
        this.objectMapper = objectMapper;
    }

    // Spring Cloud Stream's functional Consumer<T> contract is synchronous
    // (void) - blocking here, on the binder's own message-listener thread
    // (not a WebFlux/Netty event-loop thread), gives proper at-least-once
    // semantics: the container only acks the message once persistence and
    // the reply have actually completed. This is not the blocking-RPC
    // antipattern ADR 0022 rejected for RabbitTemplate.convertSendAndReceive
    // (that was about blocking a *reactive HTTP/WS* thread) - a dedicated
    // listener thread blocking on its own local work is unremarkable.
    @Bean
    public Consumer<TradeCommand> tradeRequestConsumer() {
        return command -> handle(command).block(Duration.ofSeconds(10));
    }

    public Mono<Void> handle(TradeCommand command) {
        return switch (command.type()) {
            case "CREATE_TRADE" -> handleCreateTrade(command);
            case "CONFIRM_TRADE" -> handleConfirmTrade(command);
            case "CANCEL_TRADE" -> handleCancelTrade(command);
            case "GET_TRADE_HISTORY" -> handleGetTradeHistory(command);
            default -> Mono.empty();
        };
    }

    private Mono<Void> handleCreateTrade(TradeCommand command) {
        TradeRequest request = objectMapper.convertValue(command.payload(), TradeRequest.class);
        Optional<String> rejectionReason = validate(request, command.roles());
        if (rejectionReason.isPresent()) {
            return reject(request, command.submittedBy(), rejectionReason.get())
                    .flatMap(rejected -> replyTo(command, "TRADE_REJECTED", rejected));
        }
        PendingTrade pending = new PendingTrade(
                UUID.randomUUID().toString(), request.symbol(), request.side(), request.price(), request.quantity(), Instant.now());
        pendingTrades.put(pending.id(), pending);
        return replyTo(command, "TRADE_PENDING", pending);
    }

    private Mono<Void> handleConfirmTrade(TradeCommand command) {
        PendingTrade pending = pendingTrades.remove(readPendingTradeId(command));
        if (pending == null) {
            return Mono.empty();
        }
        return execute(pending, command.submittedBy())
                .flatMap(trade -> replyTo(command, "TRADE_CREATED", toContract(trade)));
    }

    private Mono<Void> handleCancelTrade(TradeCommand command) {
        PendingTrade pending = pendingTrades.remove(readPendingTradeId(command));
        if (pending == null) {
            return replyTo(command, "NOOP", null);
        }
        return auditService.record(null, command.submittedBy(), "TRADE_CANCELLED", describe(pending))
                .then(replyTo(command, "TRADE_CANCELLED", pending));
    }

    private Mono<Void> handleGetTradeHistory(TradeCommand command) {
        TradeHistoryQuery query = objectMapper.convertValue(command.payload(), TradeHistoryQuery.class);
        return tradeHistoryQueryService.query(query)
                .flatMap(page -> replyTo(command, "TRADE_HISTORY", page));
    }

    private Mono<Trade> execute(PendingTrade pending, String submittedBy) {
        Trade trade = new Trade(pending.id(), pending.symbol(), pending.side(), pending.price(), pending.quantity(), Instant.now());
        return tradeRepository.save(trade)
                .doOnNext(saved -> streamBridge.send(TRADE_CREATED_BINDING, toContract(saved)))
                .flatMap(saved -> auditService.record(null, submittedBy, "TRADE_EXECUTED", describe(saved)).thenReturn(saved));
    }

    private Mono<com.sdp.contracts.TradeRejected> reject(TradeRequest request, String submittedBy, String reason) {
        com.sdp.contracts.TradeRejected rejected = new com.sdp.contracts.TradeRejected(
                request.symbol(), request.side(), request.price(), request.quantity(), reason);
        return auditService.record(null, submittedBy, "TRADE_REJECTED", describe(request) + " - " + reason)
                .thenReturn(rejected);
    }

    private Mono<Void> replyTo(TradeCommand command, String type, Object payload) {
        return Mono.fromRunnable(() -> streamBridge.send(
                TRADE_RESPONSES_BINDING, new TradeCommandResult(command.correlationId(), type, payload)));
    }

    private String readPendingTradeId(TradeCommand command) {
        return objectMapper.convertValue(command.payload(), PendingTradeId.class).id();
    }

    private Optional<String> validate(TradeRequest request, Set<String> roles) {
        if (!roles.contains("trader")) {
            return Optional.of("role does not permit trading");
        }
        if (request.quantity().signum() <= 0) {
            return Optional.of("quantity must be greater than zero");
        }
        if (!KNOWN_SYMBOLS.contains(request.symbol())) {
            return Optional.of("unknown symbol: " + request.symbol());
        }
        return Optional.empty();
    }

    private com.sdp.contracts.Trade toContract(Trade trade) {
        return new com.sdp.contracts.Trade(trade.id(), trade.symbol(), trade.side(), trade.price(), trade.quantity(), trade.timestamp());
    }

    private String describe(PendingTrade pending) {
        return pending.side() + " " + pending.quantity() + " " + pending.symbol() + " @ " + pending.price();
    }

    private String describe(Trade trade) {
        return trade.side() + " " + trade.quantity() + " " + trade.symbol() + " @ " + trade.price();
    }

    private String describe(TradeRequest request) {
        return request.side() + " " + request.quantity() + " " + request.symbol() + " @ " + request.price();
    }
}
