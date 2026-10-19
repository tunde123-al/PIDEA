package com.pidea.jobs;

import java.util.EnumSet;
import java.util.Set;

/**
 * Job lifecycle. The worker (Python) performs the same transitions with atomic SQL updates;
 * this enum is the documented source of truth and is unit-tested.
 *
 * PENDING -> RUNNING | CANCELLED
 * RUNNING -> SUCCEEDED | PARTIALLY_SUCCEEDED | FAILED | RETRY_SCHEDULED
 * RETRY_SCHEDULED -> RUNNING | CANCELLED
 * SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, CANCELLED are terminal.
 */
public enum JobStatus {
    PENDING, RUNNING, SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, RETRY_SCHEDULED, CANCELLED;

    public Set<JobStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, RETRY_SCHEDULED);
            case RETRY_SCHEDULED -> EnumSet.of(RUNNING, CANCELLED);
            default -> EnumSet.noneOf(JobStatus.class);
        };
    }

    public boolean canTransitionTo(JobStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
