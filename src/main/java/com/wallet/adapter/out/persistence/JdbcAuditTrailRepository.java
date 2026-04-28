package com.wallet.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.application.port.out.AuditTrailRepository;
import com.wallet.domain.audit.FinancialAuditEvent;
import com.wallet.infrastructure.id.UuidV7Generator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditTrailRepository implements AuditTrailRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditTrailRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(FinancialAuditEvent event) {
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(event.details());
        } catch (JsonProcessingException jsonProcessingException) {
            throw new IllegalStateException(jsonProcessingException);
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("id", UuidV7Generator.next());
        params.addValue("correlationId", event.correlationId());
        params.addValue("subjectUserId", event.subjectUserId());
        params.addValue("commandType", event.commandType());
        params.addValue("outcome", event.outcome());
        params.addValue("ledgerEntryId", event.ledgerEntryId());
        params.addValue("idempotencyKey", event.idempotencyKey());
        params.addValue("details", detailsJson);
        jdbc.update(
            """
            INSERT INTO financial_audit_events (id, correlation_id, subject_user_id, command_type, outcome, ledger_entry_id, idempotency_key, details)
            VALUES (:id, :correlationId, :subjectUserId, :commandType, :outcome, :ledgerEntryId, :idempotencyKey, CAST(:details AS jsonb))
            """,
            params
        );
    }
}
