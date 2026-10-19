package com.pidea.common;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Turns exceptions into {@link ApiError}. Never leaks stack traces, SQL or internals to clients. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        List<ApiError.FieldViolation> v = e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ApiError.FieldViolation(f.getField(), f.getDefaultMessage())).toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", v);
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> domainValidation(ValidationException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage(), List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request", List.of());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), List.of());
    }

    @ExceptionHandler({ConflictException.class, OptimisticLockingFailureException.class})
    ResponseEntity<ApiError> conflict(Exception e) {
        String msg = e instanceof ConflictException ? e.getMessage() : "The resource was modified concurrently; retry";
        return build(HttpStatus.CONFLICT, "CONFLICT", msg, List.of());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ApiError> keyReused(IdempotencyKeyReusedException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", e.getMessage(), List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Upload exceeds the size limit", List.of());
    }

    /** Re-thrown so Spring Security's handler produces the 403 consistently. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus s, String code, String msg, List<ApiError.FieldViolation> v) {
        return ResponseEntity.status(s).body(
            new ApiError(Instant.now(clock), s.value(), code, msg, MDC.get(CorrelationIdFilter.MDC_KEY), v));
    }
}
