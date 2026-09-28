package com.company.orders.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reads this service's own JSON log file, the same file Filebeat ships to ELK. */
@RestController
@RequestMapping("/api/logs")
public class LogAnalyzerController {

    private final LogAnalyzer analyzer = new LogAnalyzer();
    private final Path logFile;

    public LogAnalyzerController(@Value("${app.logging.json-file}") String logFile) {
        this.logFile = Path.of(logFile);
    }

    @GetMapping("/errors/top")
    public List<LogAnalyzer.ErrorCount> topErrors(@RequestParam(defaultValue = "5") int k) throws IOException {
        return analyzer.topErrors(analyzer.countErrors(load()), k);
    }

    @GetMapping("/timeline/{requestId}")
    public Map<String, Object> timeline(@PathVariable String requestId) throws IOException {
        List<LogEntry> timeline = analyzer.timeline(load(), requestId);
        return Map.of(
                "requestId", requestId,
                "events", timeline,
                "rootCause", analyzer.rootCause(timeline).map(LogEntry::message).orElse("none"));
    }

    private List<LogEntry> load() throws IOException {
        return Files.exists(logFile) ? analyzer.parse(Files.readAllLines(logFile)) : List.of();
    }
}
