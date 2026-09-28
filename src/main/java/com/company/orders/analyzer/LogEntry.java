package com.company.orders.analyzer;

/** One parsed line of the JSON log file (field names follow LogstashEncoder's defaults). */
public record LogEntry(String timestamp, String level, String requestId, String logger, String message,
                       String stackTrace) {

    public boolean isError() {
        return "ERROR".equals(level);
    }
}
