package com.settlement.web;

import java.io.IOException;
import java.util.UUID;

import com.settlement.config.AppConfig.InstanceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id on every request, honouring an inbound
 * {@code X-Correlation-Id} so a caller can stitch its own traces to ours, and
 * echoes it back on the response.
 *
 * <p>The id is persisted onto the payment and onto the outbox row at capture
 * time, so later settlement attempts, retries and dead-letters — which happen
 * on a background thread minutes later — log the correlation id of the request
 * that created the work.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";
    public static final String INSTANCE_MDC_KEY = "instance_id";

    private static final int MAX_LENGTH = 128;

    private final InstanceId instanceId;

    public CorrelationIdFilter(InstanceId instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER));
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        MDC.put(INSTANCE_MDC_KEY, instanceId.value());
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(INSTANCE_MDC_KEY);
        }
    }

    /** Never let caller-supplied text into logs unbounded or with control characters. */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._:\\-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? UUID.randomUUID().toString() : value;
    }
}
