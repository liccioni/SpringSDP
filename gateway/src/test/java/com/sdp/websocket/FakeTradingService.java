package com.sdp.websocket;

import com.sdp.contracts.PendingTrade;
import com.sdp.contracts.PendingTradeId;
import com.sdp.contracts.Trade;
import com.sdp.contracts.TradeCommand;
import com.sdp.contracts.TradeCommandResult;
import com.sdp.contracts.TradeRejected;
import com.sdp.contracts.TradeRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.time.Duration;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import tools.jackson.databind.ObjectMapper;

/**
 * Stands in for the real Backend/Trading Service in SdpWebSocketHandlerIT
 * (see ADR 0022's update, issue #92) - this test spins up only the
 * monolith's own Spring context, so nothing else consumes "trade-requests"
 * or replies on "trade-responses" without this. Reimplements just enough
 * of trading-service's own TradeService logic (validation, the
 * ADR 0018 pending-trade lifecycle, an in-memory history) to prove the
 * monolith's request/reply *plumbing* is wired correctly end-to-end over a
 * real broker - the business logic itself is independently proven by
 * trading-service's own TradeServiceTest/TradeServiceIT.
 */
class FakeTradingService {

    private static final Set<String> KNOWN_SYMBOLS = Set.of("EUR/USD", "GBP/USD", "USD/JPY");

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final SimpleMessageListenerContainer container;
    private final Map<String, PendingTrade> pendingTrades = new ConcurrentHashMap<>();
    private final List<Trade> history = new CopyOnWriteArrayList<>();
    // Lets SdpWebSocketHandlerIT observe a disconnect-triggered CANCEL_TRADE
    // (issue #79) without polling this.pendingTrades on a sleep loop.
    private final Sinks.Many<String> cancelledIds = Sinks.many().multicast().onBackpressureBuffer();

    FakeTradingService(ConnectionFactory connectionFactory, AmqpAdmin amqpAdmin, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;

        FanoutExchange exchange = new FanoutExchange("trade-requests");
        amqpAdmin.declareExchange(exchange);
        String queueName = amqpAdmin.declareQueue(new Queue("", false, true, false));
        amqpAdmin.declareBinding(BindingBuilder.bind(new Queue(queueName)).to(exchange));

        container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setMessageListener(this::onMessage);
        container.start();
    }

    void stop() {
        container.stop();
    }

    private void onMessage(Message message) {
        TradeCommand command = objectMapper.readValue(message.getBody(), TradeCommand.class);
        switch (command.type()) {
            case "CREATE_TRADE" -> handleCreateTrade(command);
            case "CONFIRM_TRADE" -> handleConfirmTrade(command);
            case "CANCEL_TRADE" -> handleCancelTrade(command);
            case "GET_TRADE_HISTORY" -> handleGetTradeHistory(command);
            default -> { }
        }
    }

    private void handleCreateTrade(TradeCommand command) {
        TradeRequest request = objectMapper.convertValue(command.payload(), TradeRequest.class);
        String rejectionReason = validate(request);
        if (rejectionReason != null) {
            publish("trade-rejected", new TradeRejected(request.symbol(), request.side(), request.price(), request.quantity(), rejectionReason));
            reply(command, "TRADE_REJECTED", null);
            return;
        }
        PendingTrade pending = new PendingTrade(
                UUID.randomUUID().toString(), request.symbol(), request.side(), request.price(), request.quantity(), Instant.now());
        pendingTrades.put(pending.id(), pending);
        reply(command, "TRADE_PENDING", pending);
    }

    private void handleConfirmTrade(TradeCommand command) {
        PendingTrade pending = pendingTrades.remove(readPendingTradeId(command));
        if (pending == null) {
            return;
        }
        Trade trade = new Trade(pending.id(), pending.symbol(), pending.side(), pending.price(), pending.quantity(), Instant.now());
        history.add(trade);
        publish("trade-created", trade);
    }

    private void handleCancelTrade(TradeCommand command) {
        PendingTrade pending = pendingTrades.remove(readPendingTradeId(command));
        if (pending == null) {
            reply(command, "NOOP", null);
            return;
        }
        cancelledIds.tryEmitNext(pending.id());
        reply(command, "TRADE_CANCELLED", pending);
    }

    Mono<Void> awaitCancellation(String id, Duration timeout) {
        return cancelledIds.asFlux().filter(id::equals).next().timeout(timeout).then();
    }

    private void handleGetTradeHistory(TradeCommand command) {
        reply(command, "TRADE_HISTORY", new ArrayList<>(history));
    }

    private String readPendingTradeId(TradeCommand command) {
        return objectMapper.convertValue(command.payload(), PendingTradeId.class).id();
    }

    private String validate(TradeRequest request) {
        if (request.quantity().signum() <= 0) {
            return "quantity must be greater than zero";
        }
        if (!KNOWN_SYMBOLS.contains(request.symbol())) {
            return "unknown symbol: " + request.symbol();
        }
        return null;
    }

    private void reply(TradeCommand command, String type, Object payload) {
        publish("trade-responses", new TradeCommandResult(command.correlationId(), type, payload));
    }

    private void publish(String exchange, Object payload) {
        byte[] body = objectMapper.writeValueAsBytes(payload);
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        rabbitTemplate.send(exchange, "", new Message(body, properties));
    }
}
