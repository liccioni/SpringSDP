package com.sdp.common;

import com.sdp.eventbus.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

// A pure EventBus payload type now, not a persisted entity - persistence
// moved to trading-service's own com.sdp.trading.Trade as of #91 (see ADR
// 0022's update); the R2DBC/Persistable machinery this record carried
// before that (@Table, @Id, isNew() always true) became dead weight once
// this service's own TradeRepository was removed in #92, and stayed
// unnoticed until #94 tried to compile this class with no R2DBC dependency
// on the classpath at all.
public record Trade(String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp)
        implements DomainEvent {

    @Override
    public String eventType() {
        return "TRADE_CREATED";
    }
}
