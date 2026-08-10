package com.sdp.audit;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface AuditEventRepository extends ReactiveCrudRepository<AuditEvent, String> {
}
