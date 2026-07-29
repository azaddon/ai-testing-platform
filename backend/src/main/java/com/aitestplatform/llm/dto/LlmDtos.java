package com.aitestplatform.llm.dto;

import java.util.List;
import java.util.Map;

public class LlmDtos {

    public record TestCaseGenRequest(
            String projectId,
            String requirementText,
            List<String> testTypes,   // functional, edge, negative, regression
            int count
    ) {}

    public record GeneratedTestCase(
            String title,
            String description,
            List<String> preconditions,
            List<Step> steps,
            String type,
            String priority
    ) {
        public record Step(String action, String expected) {}
    }

    public record GeneratedTestCases(List<GeneratedTestCase> testCases, String modelUsed) {}

    public record ApiTestGenRequest(
            String projectId,
            String openApiSpec,    // raw spec content (yaml/json)
            List<String> endpointFilters,
            int count               // upper bound on total scenarios generated
    ) {}

    /** Scenario only — no code yet. This is the output of the "Generate Scenario" step. */
    public record ApiScenario(String endpoint, String method, String scenario) {}

    public record GeneratedApiTestScenarios(List<ApiScenario> scenarios, String modelUsed) {}

    /** Input for the "Generate Code" step — one scenario at a time, plus enough spec
     *  context for the LLM to produce accurate Rest Assured code for that endpoint. */
    public record ApiTestCodeGenRequest(
            String endpoint,
            String method,
            String scenario,
            String openApiSpecContext
    ) {}

    public record GeneratedApiTestCode(String generatedCode, String modelUsed) {}

    public record LocatorGenRequest(String domSnapshot, String targetDescription) {}

    public record LocatorSuggestion(String primaryLocator, List<String> fallbackLocators, String rationale) {}

    public record FailureContext(
            String testRunId,
            String errorMessage,
            String stackTrace,
            List<String> recentLogLines,
            Map<String, Object> domOrResponseSnapshot
    ) {}

    public record FailureAnalysisResult(
            String rootCause,
            String category,        // flaky | environment | app-bug | test-bug
            double confidence,
            String suggestedFix
    ) {}

    public record ScreenshotContext(String baselineImageBase64, String actualImageBase64, String contextDescription) {}

    public record ScreenshotAnalysisResult(boolean regressionDetected, String description, double diffConfidence) {}
}
