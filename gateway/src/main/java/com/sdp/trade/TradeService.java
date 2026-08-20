package com.sdp.trade;

import com.sdp.common.Side;
import com.sdp.common.Trade;
import com.sdp.eventbus.EventBus;
import com.sdp.session.Session;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import tools.jackson.databind.ObjectMapper;

/**
 * The monolith's (see ADR 0022's update, issue #92 - "Gateway" in the
 * roadmap's own language) side of the "trade-requests"/"trade-responses"
 * correlated request/reply pair: forwards CREATE_TRADE/CONFIRM_TRADE/
 * CANCEL_TRADE/GET_TRADE_HISTORY to the Backend/Trading Service and routes
 * each reply back by correlationId. Validation, the PendingTrade lifecycle
 * (ADR 0018), and persistence all moved to trading-service's own
 * TradeService - this class now holds no trading-domain logic of its own,
 * only the request/reply plumbing SdpWebSocketHandler already depended on
 * (its own call sites are completely unchanged: same method signatures,
 * same semantics).
 *
 * Also the monolith's temporary consumer of TRADE_CREATED/TRADE_REJECTED
 * broadcasts from the Backend/Trading Service's RabbitMQ fanout exchanges
 * (issue #91) - relays each onto the same EventBus, so
 * SdpWebSocketHandler's existing broadcast-to-all-sessions delivery stays
 * unchanged.
 */
@Service
public class TradeService {

    private static final String TRADE_REQUESTS_BINDING = "tradeRequests-out-0";
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(10);

    private final EventBus eventBus;
    private final StreamBridge streamBridge;
    private final ObjectMapper objectMapper;
    private final Map<String, Sinks.One<com.sdp.contracts.TradeCommandResult>> pendingReplies = new ConcurrentHashMap<>();

    public TradeService(EventBus eventBus, StreamBridge streamBridge, ObjectMapper objectMapper) {
        this.eventBus = eventBus;
        this.streamBridge = streamBridge;
        this.objectMapper = objectMapper;
    }

    public Mono<PendingTrade> requestTrade(TradeRequest request, Session session) {
        return send("CREATE_TRADE", request, session.username(), session.roles())
                .flatMap(result -> "TRADE_PENDING".equals(result.type())
                        ? Mono.just(objectMapper.convertValue(result.payload(), PendingTrade.class))
                        : Mono.empty());
    }

    public Mono<Trade> confirmTrade(String id, Session session) {
        // Fire-and-forget: the wire protocol never replies to CONFIRM_TRADE
        // either (an unknown/already-resolved id is a silent no-op), and its
        // only real effect - the TRADE_CREATED broadcast - already exists
        // via #91's fanout exchange, independent of any correlationId.
        streamBridge.send(TRADE_REQUESTS_BINDING, new com.sdp.contracts.TradeCommand(
                UUID.randomUUID().toString(), session.username(), session.roles(), "CONFIRM_TRADE", new com.sdp.contracts.PendingTradeId(id)));
        return Mono.empty();
    }

    public Mono<PendingTrade> cancelTrade(String id, Session session) {
        return send("CANCEL_TRADE", new com.sdp.contracts.PendingTradeId(id), session.username(), session.roles())
                .flatMap(result -> "TRADE_CANCELLED".equals(result.type())
                        ? Mono.just(objectMapper.convertValue(result.payload(), PendingTrade.class))
                        : Mono.empty());
    }

    // The client-supplied correlationId (issue #131) is reused as-is for the
    // RabbitMQ-level TradeCommand.correlationId(), collapsing what would
    // otherwise be two separate ids into one for this flow - falls back to
    // a fresh one only when the client didn't send one.
    public Mono<com.sdp.contracts.TradeHistoryPage> history(com.sdp.contracts.TradeHistoryQuery query, String correlationId) {
        String effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        return send("GET_TRADE_HISTORY", query, null, Set.of(), effectiveCorrelationId)
                .map(result -> objectMapper.convertValue(result.payload(), com.sdp.contracts.TradeHistoryPage.class));
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

    @Bean
    public Consumer<com.sdp.contracts.TradeCommandResult> tradeResponseConsumer() {
        return result -> {
            Sinks.One<com.sdp.contracts.TradeCommandResult> sink = pendingReplies.remove(result.correlationId());
            if (sink != null) {
                sink.tryEmitValue(result);
            }
        };
    }

    private Mono<com.sdp.contracts.TradeCommandResult> send(String type, Object payload, String submittedBy, Set<String> roles) {
        return send(type, payload, submittedBy, roles, UUID.randomUUID().toString());
    }

    private Mono<com.sdp.contracts.TradeCommandResult> send(String type, Object payload, String submittedBy, Set<String> roles, String correlationId) {
        Sinks.One<com.sdp.contracts.TradeCommandResult> sink = Sinks.one();
        pendingReplies.put(correlationId, sink);
        streamBridge.send(TRADE_REQUESTS_BINDING, new com.sdp.contracts.TradeCommand(correlationId, submittedBy, roles, type, payload));
        return sink.asMono()
                .timeout(REPLY_TIMEOUT)
                .doFinally(signal -> pendingReplies.remove(correlationId));
    }
}
