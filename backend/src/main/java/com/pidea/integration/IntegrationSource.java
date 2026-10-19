package com.pidea.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapped to integration.integration_source. Entities are internal: the API exposes
 * {@link SourceResponse}, never this class (so DB columns can change without breaking API clients).
 */
@Entity
@Table(name = "integration_source", schema = "integration")
public class IntegrationSource {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    /** Name of the environment variable / secret holding the credential; never the credential. */
    @Column(name = "secret_ref")
    private String secretRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic locking: a concurrent update of the same row fails instead of silently overwriting. */
    @Version
    private long version;

    protected IntegrationSource() {} // required by JPA

    public IntegrationSource(UUID id, String name, SourceType type, Map<String, Object> config,
                             String secretRef, Instant now) {
        this.id = id;
        this.name = name;
        this.sourceType = type;
        this.status = SourceStatus.ACTIVE;
        this.config = config;
        this.secretRef = secretRef;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateConfig(Map<String, Object> config, String secretRef, Instant now) {
        this.config = config;
        this.secretRef = secretRef;
        this.updatedAt = now;
    }

    public void changeStatus(SourceStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public SourceType getSourceType() { return sourceType; }
    public SourceStatus getStatus() { return status; }
    public Map<String, Object> getConfig() { return config; }
    public String getSecretRef() { return secretRef; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
