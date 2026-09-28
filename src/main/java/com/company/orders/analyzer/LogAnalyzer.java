package com.company.orders.analyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The article's "how senior engineers read logs" playbook, as code:
 * find ERRORs, pick a requestId, filter, build the timeline, and the first error is the root cause.
 * Plus the DSA bonus: count error signatures with a HashMap and pull the top K with a heap.
 */
public class LogAnalyzer {

    public record ErrorCount(String signature, int count) {
    }

    // Dynamic IDs make every message unique; normalizing them lets identical failures group together
    private static final Pattern IDS = Pattern.compile("\\b(?:REQ|ORD|PAY|USR)-[\\w-]+|\\b\\d+\\b");

    private final JsonMapper mapper = JsonMapper.builder().build();

    public List<LogEntry> parse(List<String> jsonLines) {
        List<LogEntry> entries = new ArrayList<>(jsonLines.size());
        for (String line : jsonLines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                entries.add(new LogEntry(
                        node.path("@timestamp").asString(null),
                        node.path("level").asString(null),
                        node.path("requestId").asString(null),
                        node.path("logger_name").asString(null),
                        node.path("message").asString(""),
                        node.path("stack_trace").asString(null)));
            } catch (RuntimeException malformed) {
                // A half-written last line during rotation is normal; skip rather than fail the whole analysis
            }
        }
        return entries;
    }

    /** Steps 3-4: filter by requestId and order chronologically. */
    public List<LogEntry> timeline(List<LogEntry> entries, String requestId) {
        return entries.stream()
                .filter(e -> requestId.equals(e.requestId()))
                .sorted(Comparator.comparing(LogEntry::timestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** Step 5: the first error usually reveals the problem; everything after is collateral damage. */
    public Optional<LogEntry> rootCause(List<LogEntry> timeline) {
        return timeline.stream().filter(LogEntry::isError).findFirst();
    }

    public String signature(LogEntry entry) {
        return entry.logger() + ": " + IDS.matcher(entry.message()).replaceAll("<id>");
    }

    /** O(n) counting pass: HashMap insert/lookup is O(1) on average. */
    public Map<String, Integer> countErrors(List<LogEntry> entries) {
        Map<String, Integer> counts = new HashMap<>();
        for (LogEntry entry : entries) {
            if (entry.isError()) {
                counts.merge(signature(entry), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Returns the {@code k} most frequent error signatures, most frequent first.
     * Ties may be returned in any order.
     */
    public List<ErrorCount> topErrors(Map<String, Integer> counts, int k) {
        if (k <= 0) {
            return List.of();
        }
        // Min-heap capped at k: the root is the weakest of the current top k, so evicting it is O(log k).
        // Total O(n log k) time and O(k) memory, instead of O(n log n) / O(n) for a heap of everything.
        PriorityQueue<ErrorCount> heap = new PriorityQueue<>(Comparator.comparingInt(ErrorCount::count));
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            heap.offer(new ErrorCount(entry.getKey(), entry.getValue()));
            if (heap.size() > k) {
                heap.poll();
            }
        }
        // Draining a min-heap yields ascending order; reverse for most-frequent-first
        List<ErrorCount> top = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            top.add(heap.poll());
        }
        return top.reversed();
    }
}
