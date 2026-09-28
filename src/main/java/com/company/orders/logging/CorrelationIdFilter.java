package com.company.orders.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation ID pattern: every request gets a requestId that follows it through
 * every log line in this service and every downstream service it calls.
 *
 * <p>Improvements over the naive version:
 * <ul>
 *   <li>Reuses an incoming X-Request-Id so the ID survives service hops (gateway -> us -> inventory).</li>
 *   <li>Validates the incoming value; a client-controlled header is a log-injection vector.</li>
 *   <li>Echoes the ID in the response so a user can quote it in a support ticket.</li>
 *   <li>Removes only its own keys instead of MDC.clear(), which would also wipe tracing's traceId/spanId.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(MdcKeys.REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = "REQ-" + UUID.randomUUID();
        }
        String userId = sanitize(request.getHeader(MdcKeys.USER_ID_HEADER));

        MDC.put(MdcKeys.REQUEST_ID, requestId);
        if (userId != null) {
            MDC.put(MdcKeys.USER_ID, userId);
        }
        response.setHeader(MdcKeys.REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.REQUEST_ID);
            MDC.remove(MdcKeys.USER_ID);
        }
    }

    private static String sanitize(String value) {
        return value != null && SAFE_ID.matcher(value).matches() ? value : null;
    }
}
