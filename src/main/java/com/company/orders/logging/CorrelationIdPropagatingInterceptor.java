package com.company.orders.logging;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Outbound half of the correlation ID pattern: copies requestId/userId from the MDC onto
 * every outgoing HTTP call, so the next service's {@link CorrelationIdFilter} picks them up.
 * (W3C traceparent headers for the traceId are added separately by Micrometer Tracing.)
 */
public class CorrelationIdPropagatingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        copy(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_ID_HEADER, request);
        copy(MdcKeys.USER_ID, MdcKeys.USER_ID_HEADER, request);
        return execution.execute(request, body);
    }

    private static void copy(String mdcKey, String header, HttpRequest request) {
        String value = MDC.get(mdcKey);
        if (value != null && !request.getHeaders().containsHeader(header)) {
            request.getHeaders().set(header, value);
        }
    }
}
