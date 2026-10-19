package com.pidea.common;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String what, Object id) {
        super(what + " not found: " + id);
    }
}
