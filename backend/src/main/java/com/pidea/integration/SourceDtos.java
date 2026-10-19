package com.pidea.integration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API contracts for integration sources, kept separate from the JPA entity. */
public final class SourceDtos {
    private SourceDtos() {}

    @Schema(description = "Register a new integration source")
    public record CreateSourceRequest(
        @Schema(example = "legacy-crm-customers")
        @NotBlank @Size(min = 3, max = 100)
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]*[a-z0-9]$", message = "lowercase letters, digits and hyphens only")
        String name,
        @NotNull SourceType type,
        @Schema(description = "Non-secret settings. Keys that look like credentials are rejected.",
                example = "{\"baseUrl\": \"https://crm.example.com\"}")
        Map<String, Object> config,
        @Schema(description = "NAME of the env var holding the credential, e.g. CRM_API_KEY. Never the secret itself.",
                example = "CRM_API_KEY")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,99}$", message = "must be an UPPER_SNAKE_CASE variable name")
        String secretRef) {}

    public record UpdateSourceRequest(
        Map<String, Object> config,
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,99}$", message = "must be an UPPER_SNAKE_CASE variable name")
        String secretRef) {}

    public record UpdateStatusRequest(@NotNull SourceStatus status) {}

    public record SourceResponse(UUID id, String name, SourceType type, SourceStatus status,
                                 Map<String, Object> config,
                                 @Schema(description = "Whether a credential reference is configured; the reference itself is not exposed")
                                 boolean secretConfigured,
                                 Instant createdAt, Instant updatedAt) {
        static SourceResponse from(IntegrationSource s) {
            return new SourceResponse(s.getId(), s.getName(), s.getSourceType(), s.getStatus(), s.getConfig(),
                s.getSecretRef() != null, s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record SourceHealthResponse(UUID sourceId, SourceStatus status, UUID lastJobId, String lastJobStatus,
                                       Instant lastJobSubmittedAt, Instant lastJobCompletedAt,
                                       long failedJobsLast24h, long completedJobsLast24h,
                                       long recordsAcceptedLast24h, long recordsRejectedLast24h) {}
}
