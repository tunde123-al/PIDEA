package com.pidea.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a client X-Correlation-Id only if it is a short safe token (prevents log injection),
 * otherwise generates one. The id is placed in the logging MDC and echoed on the response.
 *
 * <p>Also writes one access-log line per request (method, path, status, duration) so every request can be found by
 * its correlation id. Only the path is logged, never the query string, headers, body or user name. Health checks
 * and metrics scrapes are logged at DEBUG because they arrive every few seconds.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");
    private static final Logger access = LoggerFactory.getLogger("pidea.access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        res.setHeader(HEADER, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            String path = req.getRequestURI();
            long millis = (System.nanoTime() - started) / 1_000_000;
            boolean noisy = path.startsWith("/actuator/health") || path.equals("/actuator/prometheus");
            if (noisy ? access.isDebugEnabled() : access.isInfoEnabled()) {
                String line = "request method=" + req.getMethod() + " path=" + path + " status=" + res.getStatus()
                    + " durationMs=" + millis;
                if (noisy) access.debug(line); else access.info(line);
            }
            MDC.remove(MDC_KEY);
        }
    }
}
