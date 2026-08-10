package com.sdp.audit;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

// Persistable.isNew() always true: same reason as Trade (see docs/testing.md) -
// id is assigned app-side (UUID) before save(), not DB-generated.
@Table("audit_events")
public record AuditEvent(@Id String id, String sessionId, String username, String eventType, String detail, Instant timestamp)
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
