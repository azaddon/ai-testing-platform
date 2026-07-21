package com.aitestplatform.failureanalysis;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/test-runs/{runId}")
public class FailureAnalysisController {

    private final FailureAnalysisService service;

    public FailureAnalysisController(FailureAnalysisService service) {
        this.service = service;
    }

    public record AnalyzeRequest(String errorMessage, String stackTrace, String rawLog,
                                  Map<String, Object> domOrResponseSnapshot) {}

    @PostMapping("/analyze")
    public FailureAnalysis analyze(@PathVariable String runId, @RequestBody AnalyzeRequest request) {
        return service.analyze(runId, request.errorMessage(), request.stackTrace(),
                request.rawLog(), request.domOrResponseSnapshot());
    }

    @GetMapping("/analysis")
    public FailureAnalysis get(@PathVariable String runId) {
        return service.get(runId);
    }

    @GetMapping("/log-summary")
    public Map<String, String> logSummary(@PathVariable String runId) {
        return Map.of("logSummary", service.get(runId).getLogSummary());
    }
}
