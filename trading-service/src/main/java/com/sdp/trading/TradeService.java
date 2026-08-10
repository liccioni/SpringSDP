package com.sdp.trading;

import com.sdp.audit.AuditService;
import com.sdp.contracts.TradeRequest;

import java.time.Instant;
import java.util.UUID;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Outbound half of trade execution (see ADR 0022's update): persists a
 * confirmed trade or records a rejection, then broadcasts the outcome onto
 * RabbitMQ fanout exchanges - the same TRADE_CREATED/TRADE_REJECTED
 * semantics the monolith's own TradeService/EventBus already provide, just
 * over a real broker (ADR 0010/0012's original bet, cashed in here).
 *
 * Deliberately has no two-step pending-trade workflow yet (ADR 0018) - the
 * inbound CREATE_TRADE/CONFIRM_TRADE/CANCEL_TRADE request/reply that would
 * drive one lands in #92. Until then, execute()/reject() take a
 * already-decided outcome directly; #92 wires the real trigger into (or in
 * front of) these methods.
 */
@Service
public class TradeService {

    private static final String TRADE_CREATED_BINDING = "tradeCreated-out-0";
    private static final String TRADE_REJECTED_BINDING = "tradeRejected-out-0";

    private final TradeRepository tradeRepository;
    private final AuditService auditService;
    private final StreamBridge streamBridge;

    public TradeService(TradeRepository tradeRepository, AuditService auditService, StreamBridge streamBridge) {
        this.tradeRepository = tradeRepository;
        this.auditService = auditService;
        this.streamBridge = streamBridge;
    }

    public Mono<Trade> execute(TradeRequest request, String submittedBy) {
        Trade trade = new Trade(
                UUID.randomUUID().toString(), request.symbol(), request.side(), request.price(), request.quantity(), Instant.now());
        return tradeRepository.save(trade)
                .doOnNext(saved -> streamBridge.send(TRADE_CREATED_BINDING, toContract(saved)))
                .flatMap(saved -> auditService.record(null, submittedBy, "TRADE_EXECUTED", describe(saved)).thenReturn(saved));
    }

    public Mono<Void> reject(TradeRequest request, String submittedBy, String reason) {
        streamBridge.send(TRADE_REJECTED_BINDING, new com.sdp.contracts.TradeRejected(
                request.symbol(), request.side(), request.price(), request.quantity(), reason));
        return auditService.record(null, submittedBy, "TRADE_REJECTED", describe(request) + " - " + reason).then();
    }

    private com.sdp.contracts.Trade toContract(Trade trade) {
        return new com.sdp.contracts.Trade(trade.id(), trade.symbol(), trade.side(), trade.price(), trade.quantity(), trade.timestamp());
    }

    private String describe(Trade trade) {
        return trade.side() + " " + trade.quantity() + " " + trade.symbol() + " @ " + trade.price();
    }

    private String describe(TradeRequest request) {
        return request.side() + " " + request.quantity() + " " + request.symbol() + " @ " + request.price();
    }
}
