package com.sdp.trading;

import com.sdp.PostgresIntegrationTest;
import com.sdp.RabbitMqIntegrationTest;
import com.sdp.contracts.Side;
import com.sdp.contracts.TradeRequest;

import java.math.BigDecimal;
import java.time.Duration;

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

    @BeforeEach
    void bindTestQueuesToTheFanoutExchanges() {
        tradeCreatedQueue = bindAnonymousQueue("trade-created");
        tradeRejectedQueue = bindAnonymousQueue("trade-rejected");
    }

    private String bindAnonymousQueue(String exchangeName) {
        FanoutExchange exchange = new FanoutExchange(exchangeName);
        String queueName = amqpAdmin.declareQueue(new Queue("", false, true, true));
        amqpAdmin.declareExchange(exchange);
        amqpAdmin.declareBinding(BindingBuilder.bind(new Queue(queueName)).to(exchange));
        return queueName;
    }

    @Test
    void executePersistsTheTradeAndBroadcastsTradeCreated() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("1000000"));

        Trade trade = tradeService.execute(request, "trader1").block(Duration.ofSeconds(5));

        assertThat(tradeRepository.findById(trade.id()).block(Duration.ofSeconds(5))).isNotNull();

        Message message = rabbitTemplate.receive(tradeCreatedQueue, 5000);
        assertThat(message).isNotNull();
        com.sdp.contracts.Trade broadcast = objectMapper.readValue(message.getBody(), com.sdp.contracts.Trade.class);
        assertThat(broadcast.id()).isEqualTo(trade.id());
        assertThat(broadcast.symbol()).isEqualTo("EUR/USD");
        assertThat(broadcast.side()).isEqualTo(Side.BUY);
        assertThat(broadcast.price()).isEqualByComparingTo("1.0850");
    }

    @Test
    void rejectBroadcastsTradeRejectedWithoutPersisting() {
        TradeRequest request = new TradeRequest("GBP/USD", Side.SELL, new BigDecimal("1.2650"), new BigDecimal("0"));

        tradeService.reject(request, "trader1", "quantity must be greater than zero").block(Duration.ofSeconds(5));

        Message message = rabbitTemplate.receive(tradeRejectedQueue, 5000);
        assertThat(message).isNotNull();
        com.sdp.contracts.TradeRejected broadcast = objectMapper.readValue(message.getBody(), com.sdp.contracts.TradeRejected.class);
        assertThat(broadcast.symbol()).isEqualTo("GBP/USD");
        assertThat(broadcast.reason()).isEqualTo("quantity must be greater than zero");
    }
}
