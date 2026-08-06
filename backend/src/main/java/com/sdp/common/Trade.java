package com.sdp.common;

import com.sdp.eventbus.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

public record Trade(String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp) implements DomainEvent {

    @Override
    public String eventType() {
        return "TRADE_CREATED";
    }
}
