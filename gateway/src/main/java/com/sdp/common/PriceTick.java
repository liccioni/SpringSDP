package com.sdp.common;

import com.sdp.eventbus.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceTick(String symbol, BigDecimal bid, BigDecimal ask, Instant timestamp) implements DomainEvent {

    @Override
    public String eventType() {
        return "PRICE_TICK";
    }
}
