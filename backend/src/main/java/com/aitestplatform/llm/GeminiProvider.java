package com.aitestplatform.llm;

import com.aitestplatform.llm.dto.LlmDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Default LlmProvider implementation, backed by the Gemini API.
 * Uses gemini-3.5-flash for high-volume/low-latency tasks and gemini-3-pro for
 * tasks that need deeper reasoning. Falls back to gemini-2.5-pro/-flash via config
 * if 3.x is unavailable in a given account/region.
 */
@Component
@Primary
public class GeminiProvider implements LlmProvider {

    private final WebClient geminiWebClient;
    private final PromptTemplateLoader prompts;
    private final LlmCallLogRepository callLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String fastModel;
    private final String reasoningModel;
    private final String visionModel;

    public GeminiProvider(WebClient geminiWebClient,
                           PromptTemplateLoader prompts,
                           LlmCallLogRepository callLogRepository,
                           @Value("${llm.gemini.api-key}") String apiKey,
                           @Value("${llm.gemini.fast-model}") String fastModel,
                           @Value("${llm.gemini.reasoning-model}") String reasoningModel,
                           @Value("${llm.gemini.vision-model}") String visionModel) {
        this.geminiWebClient = geminiWebClient;
        this.prompts = prompts;
        this.callLogRepository = callLogRepository;
        this.apiKey = apiKey;
        this.fastModel = fastModel;
        this.reasoningModel = reasoningModel;
        this.visionModel = visionModel;
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public GeneratedTestCases generateTestCases(TestCaseGenRequest request) {
        String prompt = prompts.render("test-case-generation.txt", Map.of(
                "requirementText", request.requirementText(),
                "count", String.valueOf(request.count()),
                "testTypes", String.join(", ", request.testTypes())
        ));
        String raw = callTextModel(fastModel, prompt, 0.3, "test-case-gen");
        try {
            List<GeneratedTestCase> testCases = objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, GeneratedTestCase.class));
            return new GeneratedTestCases(testCases, fastModel);
        } catch (Exception e) {
            throw new LlmResponseParseException("Failed to parse generated test cases", raw, e);
        }
    }

    @Override
    public GeneratedApiTestScenarios generateApiTestScenarios(ApiTestGenRequest request) {
        String prompt = prompts.render("api-scenario-generation.txt", Map.of(
                "openApiSpec", request.openApiSpec(),
                "count", String.valueOf(request.count())
        ));
        String raw = callTextModel(reasoningModel, prompt, 0.2, "api-scenario-gen");
        try {
            List<ApiScenario> scenarios = objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ApiScenario.class));
            return new GeneratedApiTestScenarios(scenarios, reasoningModel);
        } catch (Exception e) {
            throw new LlmResponseParseException("Failed to parse generated API scenarios", raw, e);
        }
    }

    @Override
    public GeneratedApiTestCode generateApiTestCode(ApiTestCodeGenRequest request) {
        String prompt = prompts.render("api-code-generation.txt", Map.of(
                "endpoint", request.endpoint(),
                "method", request.method(),
                "scenario", request.scenario(),
                "openApiSpecContext", request.openApiSpecContext() == null ? "" : request.openApiSpecContext()
        ));
        String raw = callTextModel(reasoningModel, prompt, 0.2, "api-code-gen");
        try {
            JsonNode node = objectMapper.readTree(raw);
            return new GeneratedApiTestCode(node.path("generatedCode").asText(), reasoningModel);
        } catch (Exception e) {
            throw new LlmResponseParseException("Failed to parse generated API test code", raw, e);
        }
    }

    @Override
    public List<LocatorSuggestion> generateLocators(LocatorGenRequest request) {
        String prompt = prompts.render("locator-generation.txt", Map.of(
                "domSnapshot", request.domSnapshot(),
                "targetDescription", request.targetDescription()
        ));
        String raw = callTextModel(fastModel, prompt, 0.2, "locator-gen");
        try {
            return objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, LocatorSuggestion.class));
        } catch (Exception e) {
            throw new LlmResponseParseException("Failed to parse locator suggestions", raw, e);
        }
    }

    @Override
    public FailureAnalysisResult analyzeFailure(FailureContext context) {
        String prompt = prompts.render("failure-analysis.txt", Map.of(
                "errorMessage", nullToEmpty(context.errorMessage()),
                "stackTrace", nullToEmpty(context.stackTrace()),
                "recentLogLines", context.recentLogLines() == null ? "" : String.join("\n", context.recentLogLines()),
                "domOrResponseSnapshot", context.domOrResponseSnapshot() == null ? "" : context.domOrResponseSnapshot().toString()
        ));
        String raw = callTextModel(reasoningModel, prompt, 0.2, "failure-analysis");
        try {
            return objectMapper.readValue(raw, FailureAnalysisResult.class);
        } catch (Exception e) {
            throw new LlmResponseParseException("Failed to parse failure analysis", raw, e);
        }
    }

    @Override
    public String summarizeLogChunk(String logChunk) {
        String prompt = prompts.render("log-chunk-summary.txt", Map.of("logChunk", logChunk));
        return callTextModel(fastModel, prompt, 0.2, "log-summary");
    }

    @Override
    public ScreenshotAnalysisResult analyzeScreenshot(ScreenshotContext context) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(
                                    Map.of("text", "Compare these two screenshots (baseline first, then actual) for the context: "
                                            + context.contextDescription()
                                            + ". Respond ONLY with JSON: {\"regressionDetected\": boolean, \"description\": string, \"diffConfidence\": 0.0-1.0}"),
                                    Map.of("inlineData", Map.of("mimeType", "image/png", "data", context.baselineImageBase64())),
                                    Map.of("inlineData", Map.of("mimeType", "image/png", "data", context.actualImageBase64()))
                            )
                    )),
                    "generationConfig", Map.of("temperature", 0.1, "responseMimeType", "application/json")
            );

            String raw = geminiWebClient.post()
                    .uri("/models/{model}:generateContent?key={key}", visionModel, apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String text = extractText(raw);
            ScreenshotAnalysisResult result = objectMapper.readValue(text, ScreenshotAnalysisResult.class);
            logCall(visionModel, "screenshot-analysis", start, true, null);
            return result;
        } catch (Exception e) {
            logCall(visionModel, "screenshot-analysis", start, false, e.getMessage());
            throw new LlmCallException("Gemini screenshot analysis failed", e);
        }
    }

    // ---- internal helpers ----

    private String callTextModel(String model, String prompt, double temperature, String feature) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of("temperature", temperature, "responseMimeType", "application/json")
            );

            String raw = geminiWebClient.post()
                    .uri("/models/{model}:generateContent?key={key}", model, apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String text = extractText(raw);
            logCall(model, feature, start, true, null);
            return text;
        } catch (Exception e) {
            logCall(model, feature, start, false, e.getMessage());
            throw new LlmCallException("Gemini call failed for feature " + feature, e);
        }
    }

    private String extractText(String rawResponseJson) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponseJson);
        return root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
    }

    private void logCall(String model, String feature, long start, boolean success, String error) {
        long latency = System.currentTimeMillis() - start;
        callLogRepository.save(new LlmCallLog(name(), model, feature, 0, 0, latency, success, error));
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static class LlmResponseParseException extends RuntimeException {
        LlmResponseParseException(String message, String raw, Throwable cause) {
            super(message + " | raw=" + raw, cause);
        }
    }

    static class LlmCallException extends RuntimeException {
        LlmCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
