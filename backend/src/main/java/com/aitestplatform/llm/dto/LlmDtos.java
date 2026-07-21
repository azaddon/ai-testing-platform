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
            List<String> endpointFilters
    ) {}

    public record GeneratedApiTest(
            String endpoint,
            String method,
            String scenario,       // happy-path, missing-field, boundary, auth-failure
            String generatedCode   // full Rest Assured Java method body
    ) {}

    public record GeneratedApiTests(List<GeneratedApiTest> tests, String modelUsed) {}

    /**
     * targetDescriptions is a batch: one description per element the caller needs a locator
     * for. The response is one LocatorSuggestion per element, in the same order — this lets
     * a whole test case's steps be resolved in a single LLM call instead of one call per step.
     */
    public record LocatorGenRequest(String domSnapshot, List<String> targetDescriptions) {}

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
