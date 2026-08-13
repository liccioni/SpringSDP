package com.sdp.trading;

import com.sdp.PostgresIntegrationTest;
import com.sdp.RabbitMqIntegrationTest;
import com.sdp.contracts.PendingTrade;
import com.sdp.contracts.PendingTradeId;
import com.sdp.contracts.Side;
import com.sdp.contracts.TradeCommand;
import com.sdp.contracts.TradeCommandResult;
import com.sdp.contracts.TradeHistoryPage;
import com.sdp.contracts.TradeHistoryQuery;
import com.sdp.contracts.TradeRequest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

// Declares its own test-owned queue bound to each fanout exchange *before*
// calling the service, rather than relying on the app's own binding
// provisioning timing - a fanout exchange drops a message immediately if no
// queue is bound yet when it's published, so a queue bound only after
// TradeService's first send would silently miss it.
@SpringBootTest
@Tag("integration")
class TradeServiceIT implements PostgresIntegrationTest, RabbitMqIntegrationTest {

    @Autowired
    private TradeService tradeService;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private String tradeCreatedQueue;
    private String tradeRejectedQueue;
    private String tradeResponsesQueue;

    @BeforeEach
    void bindTestQueuesToTheFanoutExchanges() {
        tradeCreatedQueue = bindAnonymousQueue("trade-created");
        tradeRejectedQueue = bindAnonymousQueue("trade-rejected");
        tradeResponsesQueue = bindAnonymousQueue("trade-responses");
    }

    @AfterEach
    void deleteTestQueues() {
        amqpAdmin.deleteQueue(tradeCreatedQueue);
        amqpAdmin.deleteQueue(tradeRejectedQueue);
        amqpAdmin.deleteQueue(tradeResponsesQueue);
    }

    // Not auto-delete: RabbitTemplate.receive(queue, timeout) briefly
    // subscribes-then-cancels internally, and an auto-delete queue is
    // removed the instant its consumer count returns to zero - fine for a
    // single receive per queue (as #91's tests were), but this class
    // receives multiple messages from the same queue per test. Deleted
    // explicitly in @AfterEach instead. Exclusive (not plain
    // non-durable/non-exclusive): this RabbitMQ version rejects declaring
    // "transient_nonexcl_queues" outright ("deprecated... not permitted
    // anymore"); exclusive ties the queue to this test JVM's own shared
    // connection (which stays open for the whole run) rather than to any
    // one receive() call, so it's not deleted between successive receives.
    private String bindAnonymousQueue(String exchangeName) {
        FanoutExchange exchange = new FanoutExchange(exchangeName);
        String queueName = amqpAdmin.declareQueue(new Queue("", false, true, false));
        amqpAdmin.declareExchange(exchange);
        amqpAdmin.declareBinding(BindingBuilder.bind(new Queue(queueName)).to(exchange));
        return queueName;
    }

    private TradeCommandResult receiveResponse() {
        Message message = rabbitTemplate.receive(tradeResponsesQueue, 5000);
        assertThat(message).isNotNull();
        return objectMapper.readValue(message.getBody(), TradeCommandResult.class);
    }

    @Test
    void createTradeThenConfirmPersistsAndBroadcastsTradeCreated() {
        String correlationId = UUID.randomUUID().toString();
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("1000000"));

        tradeService.handle(new TradeCommand(correlationId, "trader1", Set.of("trader"), "CREATE_TRADE", request)).block(Duration.ofSeconds(5));

        TradeCommandResult pendingReply = receiveResponse();
        assertThat(pendingReply.correlationId()).isEqualTo(correlationId);
        assertThat(pendingReply.type()).isEqualTo("TRADE_PENDING");
        PendingTrade pending = objectMapper.convertValue(pendingReply.payload(), PendingTrade.class);

        tradeService.handle(new TradeCommand(UUID.randomUUID().toString(), "trader1", Set.of("trader"), "CONFIRM_TRADE", new PendingTradeId(pending.id())))
                .block(Duration.ofSeconds(5));

        assertThat(tradeRepository.findById(pending.id()).block(Duration.ofSeconds(5))).isNotNull();

        Message message = rabbitTemplate.receive(tradeCreatedQueue, 5000);
        assertThat(message).isNotNull();
        com.sdp.contracts.Trade broadcast = objectMapper.readValue(message.getBody(), com.sdp.contracts.Trade.class);
        assertThat(broadcast.id()).isEqualTo(pending.id());
        assertThat(broadcast.symbol()).isEqualTo("EUR/USD");
    }

    @Test
    void createTradeWithInvalidQuantityRejectsAndBroadcastsWithoutPersisting() {
        String correlationId = UUID.randomUUID().toString();
        TradeRequest request = new TradeRequest("GBP/USD", Side.SELL, new BigDecimal("1.2650"), new BigDecimal("0"));

        tradeService.handle(new TradeCommand(correlationId, "trader1", Set.of("trader"), "CREATE_TRADE", request)).block(Duration.ofSeconds(5));

        TradeCommandResult reply = receiveResponse();
        assertThat(reply.correlationId()).isEqualTo(correlationId);
        assertThat(reply.type()).isEqualTo("TRADE_REJECTED");

        Message message = rabbitTemplate.receive(tradeRejectedQueue, 5000);
        assertThat(message).isNotNull();
        com.sdp.contracts.TradeRejected broadcast = objectMapper.readValue(message.getBody(), com.sdp.contracts.TradeRejected.class);
        assertThat(broadcast.symbol()).isEqualTo("GBP/USD");
        assertThat(broadcast.reason()).isEqualTo("quantity must be greater than zero");
    }

    @Test
    void createTradeFromAViewerRoleRejectsOverTheRealBroker() {
        String correlationId = UUID.randomUUID().toString();
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("1000000"));

        tradeService.handle(new TradeCommand(correlationId, "trader2", Set.of("viewer"), "CREATE_TRADE", request)).block(Duration.ofSeconds(5));

        TradeCommandResult reply = receiveResponse();
        assertThat(reply.correlationId()).isEqualTo(correlationId);
        assertThat(reply.type()).isEqualTo("TRADE_REJECTED");

        Message message = rabbitTemplate.receive(tradeRejectedQueue, 5000);
        assertThat(message).isNotNull();
        com.sdp.contracts.TradeRejected broadcast = objectMapper.readValue(message.getBody(), com.sdp.contracts.TradeRejected.class);
        assertThat(broadcast.reason()).isEqualTo("role does not permit trading");
    }

    @Test
    void createTradeThenCancelRepliesWithTheCancelledPendingTradeWithoutPersisting() {
        TradeRequest request = new TradeRequest("USD/JPY", Side.BUY, new BigDecimal("149.50"), new BigDecimal("250000"));
        tradeService.handle(new TradeCommand(UUID.randomUUID().toString(), "trader1", Set.of("trader"), "CREATE_TRADE", request)).block(Duration.ofSeconds(5));
        PendingTrade pending = objectMapper.convertValue(receiveResponse().payload(), PendingTrade.class);

        String correlationId = UUID.randomUUID().toString();
        tradeService.handle(new TradeCommand(correlationId, "trader1", Set.of("trader"), "CANCEL_TRADE", new PendingTradeId(pending.id())))
                .block(Duration.ofSeconds(5));

        TradeCommandResult reply = receiveResponse();
        assertThat(reply.correlationId()).isEqualTo(correlationId);
        assertThat(reply.type()).isEqualTo("TRADE_CANCELLED");
        assertThat(tradeRepository.findById(pending.id()).block(Duration.ofSeconds(5))).isNull();
    }

    @Test
    void cancelTradeWithAnUnknownIdRepliesNoop() {
        String correlationId = UUID.randomUUID().toString();

        tradeService.handle(new TradeCommand(correlationId, "trader1", Set.of("trader"), "CANCEL_TRADE", new PendingTradeId("unknown")))
                .block(Duration.ofSeconds(5));

        TradeCommandResult reply = receiveResponse();
        assertThat(reply.correlationId()).isEqualTo(correlationId);
        assertThat(reply.type()).isEqualTo("NOOP");
    }

    @Test
    void getTradeHistoryRepliesWithAPageContainingThePersistedTrade() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.SELL, new BigDecimal("1.0900"), new BigDecimal("400000"));
        tradeService.handle(new TradeCommand(UUID.randomUUID().toString(), "trader1", Set.of("trader"), "CREATE_TRADE", request)).block(Duration.ofSeconds(5));
        PendingTrade pending = objectMapper.convertValue(receiveResponse().payload(), PendingTrade.class);
        tradeService.handle(new TradeCommand(UUID.randomUUID().toString(), "trader1", Set.of("trader"), "CONFIRM_TRADE", new PendingTradeId(pending.id())))
                .block(Duration.ofSeconds(5));
        // Drain the TRADE_CREATED broadcast this confirm produced, unrelated to this test.
        rabbitTemplate.receive(tradeCreatedQueue, 5000);

        String correlationId = UUID.randomUUID().toString();
        TradeHistoryQuery query = new TradeHistoryQuery(1000, null, null, null);
        tradeService.handle(new TradeCommand(correlationId, "trader1", Set.of("trader"), "GET_TRADE_HISTORY", query)).block(Duration.ofSeconds(5));

        TradeCommandResult reply = receiveResponse();
        assertThat(reply.correlationId()).isEqualTo(correlationId);
        assertThat(reply.type()).isEqualTo("TRADE_HISTORY");
        TradeHistoryPage page = objectMapper.convertValue(reply.payload(), TradeHistoryPage.class);
        List<com.sdp.contracts.Trade> rows = page.rows();
        // Other tests in this class persist trades against the same shared
        // container/table - assert this trade is present, not an exact list.
        assertThat(rows).anySatisfy(trade -> {
            assertThat(trade.id()).isEqualTo(pending.id());
            assertThat(trade.symbol()).isEqualTo("EUR/USD");
        });
    }
}
