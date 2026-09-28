package com.company.orders.order;

import com.company.orders.logging.CorrelationIdPropagatingInterceptor;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the (simulated) inventory microservice over real HTTP so you can watch
 * requestId and traceId cross a service boundary in the logs.
 */
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestClient restClient;
    private final Environment environment;
    private final String configuredBaseUrl;

    // Using the auto-configured builder (not RestClient.create()) is what enables trace propagation
    public InventoryClient(RestClient.Builder builder, Environment environment,
                           @Value("${inventory.base-url:}") String configuredBaseUrl) {
        this.restClient = builder.requestInterceptor(new CorrelationIdPropagatingInterceptor()).build();
        this.environment = environment;
        this.configuredBaseUrl = configuredBaseUrl;
    }

    public void reserve(String orderId, String productId, int quantity) {
        log.debug("Calling inventory service productId={} quantity={}", productId, quantity);
        try {
            restClient.post()
                    .uri(baseUrl() + "/internal/inventory/reservations")
                    .body(Map.of("orderId", orderId, "productId", productId, "quantity", quantity))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Inventory status=RESERVED");
        } catch (RestClientResponseException ex) {
            throw new InventoryException(
                    "Inventory rejected reservation: HTTP " + ex.getStatusCode().value(), ex);
        }
    }

    // Both "services" live in one JVM for the demo, so default to our own port
    private String baseUrl() {
        if (!configuredBaseUrl.isBlank()) {
            return configuredBaseUrl;
        }
        return "http://localhost:" + environment.getProperty("local.server.port", "8080");
    }
}
