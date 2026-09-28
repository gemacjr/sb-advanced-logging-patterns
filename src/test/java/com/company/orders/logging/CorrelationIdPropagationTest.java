package com.company.orders.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdPropagationTest {

    @LocalServerPort
    int port;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    private ResponseEntity<Map> placeOrder(String requestId, String productId) {
        return client().post().uri("/api/orders")
                .header("X-Request-Id", requestId)
                .header("X-User-Id", "USR-11")
                .body(Map.of("productId", productId, "quantity", 1, "amount", 49.99,
                        "cardNumber", "4111111111111111"))
                .retrieve()
                .toEntity(Map.class);
    }

    @Test
    void incomingRequestIdIsEchoedAndPropagatedToDownstreamService(CapturedOutput output) {
        ResponseEntity<Map> response = placeOrder("REQ-test-123", "SKU-LAPTOP");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("REQ-test-123");

        List<String> inventoryLines = output.getOut().lines()
                .filter(l -> l.contains("Reserving inventory")).toList();
        // The inventory "service" only knows the requestId because the interceptor forwarded the header
        assertThat(inventoryLines).anySatisfy(l -> assertThat(l).contains("[REQ-test-123]").contains("[USR-11]"));
    }

    @Test
    void traceIdSurvivesTheHttpHopToTheInventoryService(CapturedOutput output) {
        placeOrder("REQ-trace-1", "SKU-LAPTOP");

        // Pattern prints "[requestId] [userId] [traceId]"; the third bracket group is the traceId
        Pattern traceId = Pattern.compile("\\[REQ-trace-1] \\[USR-11] \\[([0-9a-f]{32})]");
        Set<String> traceIds = output.getOut().lines()
                .map(traceId::matcher).filter(Matcher::find).map(m -> m.group(1))
                .collect(Collectors.toSet());
        assertThat(traceIds).as("one trace across order-service and inventory").hasSize(1);
    }

    @Test
    void inventoryFailureRollsBackPaymentAndLogsTheWholeStoryUnderOneRequestId(CapturedOutput output) {
        ResponseEntity<Map> response = placeOrder("REQ-rollback-1", "SKU-RARE");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("status", "FAILED_ROLLED_BACK");

        List<String> story = output.getOut().lines().filter(l -> l.contains("[REQ-rollback-1]")).toList();
        assertThat(story).anyMatch(l -> l.contains("Payment status=SUCCESS"))
                .anyMatch(l -> l.contains("Inventory status=FAILED"))
                .anyMatch(l -> l.contains("Rollback completed=true"));
    }

    @Test
    void unsafeRequestIdIsReplacedToPreventLogInjection() {
        ResponseEntity<Map> response = placeOrder("evil id; DROP with spaces", "SKU-LAPTOP");

        assertThat(response.getHeaders().getFirst("X-Request-Id")).startsWith("REQ-").doesNotContain(" ");
    }

    @Test
    void cardNumberNeverAppearsInLogs(CapturedOutput output) {
        placeOrder("REQ-pci-1", "SKU-LAPTOP");

        assertThat(output.getAll()).doesNotContain("4111111111111111").contains("****1111");
    }
}
