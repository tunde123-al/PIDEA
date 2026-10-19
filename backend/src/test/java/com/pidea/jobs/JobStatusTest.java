package com.pidea.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobStatusTest {

    @Test
    void validTransitions() {
        assertThat(JobStatus.PENDING.canTransitionTo(JobStatus.RUNNING)).isTrue();
        assertThat(JobStatus.RUNNING.canTransitionTo(JobStatus.RETRY_SCHEDULED)).isTrue();
        assertThat(JobStatus.RETRY_SCHEDULED.canTransitionTo(JobStatus.RUNNING)).isTrue();
        assertThat(JobStatus.RUNNING.canTransitionTo(JobStatus.PARTIALLY_SUCCEEDED)).isTrue();
    }

    @Test
    void invalidTransitionsAreRejected() {
        assertThat(JobStatus.PENDING.canTransitionTo(JobStatus.SUCCEEDED)).isFalse();   // must run first
        assertThat(JobStatus.RUNNING.canTransitionTo(JobStatus.CANCELLED)).isFalse();    // no mid-run cancel
        assertThat(JobStatus.FAILED.canTransitionTo(JobStatus.RUNNING)).isFalse();       // terminal
        assertThat(JobStatus.SUCCEEDED.canTransitionTo(JobStatus.FAILED)).isFalse();
    }

    @Test
    void terminalStatesHaveNoSuccessors() {
        for (JobStatus s : new JobStatus[] {JobStatus.SUCCEEDED, JobStatus.PARTIALLY_SUCCEEDED,
                JobStatus.FAILED, JobStatus.CANCELLED}) {
            assertThat(s.isTerminal()).as(s.name()).isTrue();
        }
        assertThat(JobStatus.PENDING.isTerminal()).isFalse();
    }
}
