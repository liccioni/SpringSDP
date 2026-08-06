package com.sdp.trade;

import com.sdp.common.Side;
import com.sdp.eventbus.DomainEvent;

import java.math.BigDecimal;

public record TradeRejected(String symbol, Side side, BigDecimal price, BigDecimal quantity, String reason) implements DomainEvent {

    @Override
    public String eventType() {
        return "TRADE_REJECTED";
    }
}
