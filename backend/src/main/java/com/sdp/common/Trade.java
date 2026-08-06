package com.sdp.common;

import com.sdp.eventbus.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

// Persistable.isNew() always true: id is assigned app-side (UUID) before
// save(), not DB-generated, so Spring Data's default null-id "is new" check
// would otherwise see a non-null id and issue an UPDATE instead of an INSERT
// - matching zero rows, silently persisting nothing. Trades are append-only
// and never updated, so "always new" is correct, not just a workaround.
@Table("trades")
public record Trade(@Id String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp)
        implements DomainEvent, Persistable<String> {

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    @Override
    public String eventType() {
        return "TRADE_CREATED";
    }
}
