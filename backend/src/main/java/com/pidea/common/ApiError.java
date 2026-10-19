package com.pidea.common;

import java.time.Instant;
import java.util.List;

/** The single error shape returned by every endpoint. */
public record ApiError(Instant timestamp, int status, String code, String message,
                       String correlationId, List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {}
}
