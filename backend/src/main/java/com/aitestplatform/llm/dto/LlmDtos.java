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

    /** Scenario only — no execution model yet. This is the output of the "Generate Scenario" step. */
    public record ApiScenario(String endpoint, String method, String scenario) {}

    public record GeneratedApiTestScenarios(List<ApiScenario> scenarios, String modelUsed) {}

    /** Input for the "Generate Code" step — one scenario at a time, plus enough spec
     *  context for the LLM to produce an accurate ApiExecutionModel for that endpoint. */
    public record ApiExecutionModelGenRequest(
            String endpoint,
            String method,
            String scenario,
            String openApiSpecContext
    ) {}

    /**
     * Wire-level shape of what the LLM returns for the API execution model — plain strings
     * (method, assertion type) rather than our domain enums. The application layer maps
     * this into the real domain com.aitestplatform.domain.execution.api.ApiExecutionModel;
     * keeping this DTO separate from the domain model is the Clean Architecture boundary
     * between "what an LLM response looks like" and "what our business logic works with".
     */
    public record GeneratedApiExecutionModel(
            String method,
            String endpoint,
            Map<String, String> headers,
            Map<String, String> queryParams,
            Map<String, String> pathParams,
            Map<String, String> cookies,
            String requestBody,
            int expectedStatus,
            List<GeneratedApiAssertion> assertions,
            String modelUsed
    ) {}

    public record GeneratedApiAssertion(String type, String path, String expectedValue) {}

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
