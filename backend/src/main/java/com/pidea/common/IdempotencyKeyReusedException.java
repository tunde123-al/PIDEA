package com.pidea.common;

/** Same Idempotency-Key sent with a different request body. Mapped to HTTP 422. */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException() {
        super("Idempotency-Key was already used with a different request");
    }
}
