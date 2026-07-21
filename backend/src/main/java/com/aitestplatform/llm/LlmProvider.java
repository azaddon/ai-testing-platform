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

    GeneratedApiTests generateApiTests(ApiTestGenRequest request);

    List<LocatorSuggestion> generateLocators(LocatorGenRequest request);

    FailureAnalysisResult analyzeFailure(FailureContext context);

    String summarizeLogChunk(String logChunk);

    ScreenshotAnalysisResult analyzeScreenshot(ScreenshotContext context);
}
