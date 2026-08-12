package com.sdp.trading;

import com.sdp.audit.AuditService;
import com.sdp.contracts.PendingTrade;
import com.sdp.contracts.PendingTradeId;
import com.sdp.contracts.TradeCommand;
import com.sdp.contracts.TradeCommandResult;
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
 * a previously requested trade and broadcasts it; CANCEL_TRADE discards
 * one; GET_TRADE_HISTORY answers with the full persisted history. Every
 * reply (except CONFIRM_TRADE, which needs none - see below) echoes the
 * request's correlationId on "trade-responses" so the Gateway (today, the
 * monolith - see ADR 0022) can route it back to the specific connection
 * that asked. TRADE_CREATED/TRADE_REJECTED still broadcast via #91's
 * fanout exchanges, unrelated to any correlationId.
 *
 * CONFIRM_TRADE gets no reply at all: the wire protocol never replies to
 * it either (docs/protocol.md - "an unknown or already-resolved id is a
 * silent no-op"), and its only real effect, the TRADE_CREATED broadcast,
 * already exists via #91.
 */
@Service
public class TradeService {

    private static final Set<String> KNOWN_SYMBOLS = Set.of("EUR/USD", "GBP/USD", "USD/JPY");
    private static final String TRADE_CREATED_BINDING = "tradeCreated-out-0";
    private static final String TRADE_REJECTED_BINDING = "tradeRejected-out-0";
    private static final String TRADE_RESPONSES_BINDING = "tradeResponses-out-0";

    private final TradeRepository tradeRepository;
    private final AuditService auditService;
    private final StreamBridge streamBridge;
    private final ObjectMapper objectMapper;
    private final Map<String, PendingTrade> pendingTrades = new ConcurrentHashMap<>();

    public TradeService(TradeRepository tradeRepository, AuditService auditService, StreamBridge streamBridge, ObjectMapper objectMapper) {
        this.tradeRepository = tradeRepository;
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
                    .then(replyTo(command, "TRADE_REJECTED", null));
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
        return execute(pending, command.submittedBy()).then();
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
        return tradeRepository.findAllByOrderByTimestampAsc()
                .map(this::toContract)
                .collectList()
                .flatMap(history -> replyTo(command, "TRADE_HISTORY", history));
    }

    private Mono<Trade> execute(PendingTrade pending, String submittedBy) {
        Trade trade = new Trade(pending.id(), pending.symbol(), pending.side(), pending.price(), pending.quantity(), Instant.now());
        return tradeRepository.save(trade)
                .doOnNext(saved -> streamBridge.send(TRADE_CREATED_BINDING, toContract(saved)))
                .flatMap(saved -> auditService.record(null, submittedBy, "TRADE_EXECUTED", describe(saved)).thenReturn(saved));
    }

    private Mono<Void> reject(TradeRequest request, String submittedBy, String reason) {
        streamBridge.send(TRADE_REJECTED_BINDING, new com.sdp.contracts.TradeRejected(
                request.symbol(), request.side(), request.price(), request.quantity(), reason));
        return auditService.record(null, submittedBy, "TRADE_REJECTED", describe(request) + " - " + reason).then();
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
