package com.pidea.common;

/** Business-rule validation failure (as opposed to bean-validation of field syntax). */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
