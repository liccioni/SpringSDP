package com.sdp.trading;

import com.sdp.contracts.Side;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

// Persistable.isNew() always true - same reasoning as the monolith's
// com.sdp.common.Trade (see docs/testing.md): id is assigned app-side (a
// UUID) before save(), not DB-generated.
@Table("trades")
public record Trade(@Id String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp)
        implements Persistable<String> {

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
