package com.aitestplatform.llm;

import com.aitestplatform.llm.dto.LlmDtos.*;

import java.util.List;

/**
 * Vendor-agnostic interface for all LLM-backed features. Business logic depends only on
 * this interface; GeminiProvider (default) and OpenAiProvider are interchangeable
 * implementations selected via the `llm.provider` config property.
 */
public interface LlmProvider {

    String name();

    GeneratedTestCases generateTestCases(TestCaseGenRequest request);

    /** Step 1 of API test authoring: scenarios only, no Rest Assured code yet. */
    GeneratedApiTestScenarios generateApiTestScenarios(ApiTestGenRequest request);

    /** Step 2: generate Rest Assured code for exactly one previously-generated scenario. */
    GeneratedApiTestCode generateApiTestCode(ApiTestCodeGenRequest request);

    List<LocatorSuggestion> generateLocators(LocatorGenRequest request);

    FailureAnalysisResult analyzeFailure(FailureContext context);

    String summarizeLogChunk(String logChunk);

    ScreenshotAnalysisResult analyzeScreenshot(ScreenshotContext context);
}
