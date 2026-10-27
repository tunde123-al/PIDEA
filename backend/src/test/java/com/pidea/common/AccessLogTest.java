package com.pidea.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.pidea.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/** One access-log line per request, tied to the correlation id, without leaking query strings. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AccessLogTest extends AbstractPostgresTest {
    @Autowired MockMvc mvc;

    @Test
    void requestsAreLoggedWithTheCorrelationIdAndNeverTheQueryString(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/integration-sources?token=super-secret-value")
            .header("X-Correlation-Id", "trace-test-0001").with(user("vw").roles("VIEWER")));

        String line = output.getAll().lines().filter(l -> l.contains("request method=")).findFirst().orElseThrow();
        assertThat(line).contains("[trace-test-0001]", "request method=GET path=/api/v1/integration-sources",
            "status=200", "durationMs=");
        assertThat(output.getAll()).doesNotContain("super-secret-value");
    }

    @Test
    void unsafeClientCorrelationIdsAreReplacedSoLogsCannotBeForged(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/integration-sources").header("X-Correlation-Id", "x\nFORGED ERROR line")
            .with(user("vw").roles("VIEWER")));
        assertThat(output.getAll()).doesNotContain("FORGED ERROR line");
    }

    @Test
    void healthChecksDoNotFloodTheLog(CapturedOutput output) throws Exception {
        mvc.perform(get("/actuator/health"));
        assertThat(output.getAll()).doesNotContain("path=/actuator/health");
    }
}
