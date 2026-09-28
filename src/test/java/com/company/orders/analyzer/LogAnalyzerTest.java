package com.company.orders.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogAnalyzerTest {

    private final LogAnalyzer analyzer = new LogAnalyzer();

    private static String line(String ts, String level, String requestId, String message) {
        return """
                {"@timestamp":"%s","level":"%s","requestId":"%s","logger_name":"OrderService","message":"%s"}"""
                .formatted(ts, level, requestId, message);
    }

    private final List<String> lines = List.of(
            line("2026-07-19T10:01:04Z", "ERROR", "REQ-123", "Inventory failed for ORD-5678"),
            line("2026-07-19T10:01:02Z", "INFO", "REQ-123", "API received"),
            line("2026-07-19T10:01:05Z", "INFO", "REQ-123", "Rollback completed"),
            line("2026-07-19T10:01:03Z", "INFO", "REQ-123", "Payment started"),
            line("2026-07-19T10:01:03Z", "ERROR", "REQ-999", "Inventory failed for ORD-1111"),
            line("2026-07-19T10:01:06Z", "ERROR", "REQ-777", "Payment gateway timeout after 3000 ms"),
            "{ half-written line during rotation");

    @Test
    void buildsChronologicalTimelineAndFindsRootCause() {
        List<LogEntry> timeline = analyzer.timeline(analyzer.parse(lines), "REQ-123");

        assertThat(timeline).extracting(LogEntry::message).containsExactly(
                "API received", "Payment started", "Inventory failed for ORD-5678", "Rollback completed");
        assertThat(analyzer.rootCause(timeline)).get()
                .extracting(LogEntry::message).isEqualTo("Inventory failed for ORD-5678");
    }

    @Test
    void groupsErrorsWithDifferentIdsUnderOneSignature() {
        Map<String, Integer> counts = analyzer.countErrors(analyzer.parse(lines));

        assertThat(counts).containsEntry("OrderService: Inventory failed for <id>", 2)
                .containsEntry("OrderService: Payment gateway timeout after <id> ms", 1);
    }

    @Test
    void topErrorsReturnsMostFrequentFirstAndRespectsK() {
        Map<String, Integer> counts = Map.of("a", 5, "b", 1, "c", 9, "d", 3);

        assertThat(analyzer.topErrors(counts, 2)).containsExactly(
                new LogAnalyzer.ErrorCount("c", 9), new LogAnalyzer.ErrorCount("a", 5));
        assertThat(analyzer.topErrors(counts, 10)).hasSize(4);
        assertThat(analyzer.topErrors(counts, 0)).isEmpty();
    }
}
