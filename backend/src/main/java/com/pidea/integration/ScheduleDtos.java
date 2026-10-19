package com.pidea.integration;

import com.pidea.jobs.EntityType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class ScheduleDtos {
    private ScheduleDtos() {}

    public record CreateScheduleRequest(@NotNull UUID sourceId, @NotNull EntityType entityType,
                                        @Min(60) @Max(2_592_000) int intervalSeconds) {}

    public record UpdateScheduleRequest(@NotNull Boolean enabled) {}

    public record ScheduleResponse(UUID id, UUID sourceId, EntityType entityType, int intervalSeconds,
                                   boolean enabled, Instant nextRunAt, Instant createdAt) {}
}
