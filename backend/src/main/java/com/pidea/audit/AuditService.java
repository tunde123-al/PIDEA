package com.pidea.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pidea.common.CorrelationIdFilter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Writes audit events inside the caller's transaction, so a change and its audit row commit together. */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void record(String action, String entityType, Object entityId, Map<String, ?> details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = auth != null ? auth.getName() : "system";
        try {
            jdbc.update("""
                INSERT INTO integration.audit_event
                  (id, actor, action, entity_type, entity_id, details, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
                UUID.randomUUID(), actor, action, entityType, entityId == null ? null : entityId.toString(),
                mapper.writeValueAsString(details == null ? Map.of() : details),
                MDC.get(CorrelationIdFilter.MDC_KEY), Timestamp.from(Instant.now(clock)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise audit details", e);
        }
    }
}
