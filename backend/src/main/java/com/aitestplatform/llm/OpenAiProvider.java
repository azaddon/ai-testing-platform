package com.aitestplatform.llm;

import com.aitestplatform.llm.dto.LlmDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Pluggable alternative to GeminiProvider. Activate with llm.provider=openai.
 * Implements the same LlmProvider contract so no calling code changes when switching.
 */
@Component
public class OpenAiProvider implements LlmProvider {

    private final WebClient openAiWebClient;
    private final PromptTemplateLoader prompts;
    private final LlmCallLogRepository callLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String fastModel;
    private final String reasoningModel;

    public OpenAiProvider(WebClient openAiWebClient,
                           PromptTemplateLoader prompts,
                           LlmCallLogRepository callLogRepository,
                           @Value("${llm.openai.api-key}") String apiKey,
                           @Value("${llm.openai.fast-model}") String fastModel,
                           @Value("${llm.openai.reasoning-model}") String reasoningModel) {
        this.openAiWebClient = openAiWebClient;
        this.prompts = prompts;
        this.callLogRepository = callLogRepository;
        this.apiKey = apiKey;
        this.fastModel = fastModel;
        this.reasoningModel = reasoningModel;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public GeneratedTestCases generateTestCases(TestCaseGenRequest request) {
        String prompt = prompts.render("test-case-generation.txt", Map.of(
                "requirementText", request.requirementText(),
                "count", String.valueOf(request.count()),
                "testTypes", String.join(", ", request.testTypes())
        ));
        String raw = chat(fastModel, prompt, 0.3, "test-case-gen");
        try {
            List<GeneratedTestCase> testCases = objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, GeneratedTestCase.class));
            return new GeneratedTestCases(testCases, fastModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse generated test cases: " + raw, e);
        }
    }

    @Override
    public GeneratedApiTestScenarios generateApiTestScenarios(ApiTestGenRequest request) {
        String prompt = prompts.render("api-scenario-generation.txt", Map.of(
                "openApiSpec", request.openApiSpec(),
                "count", String.valueOf(request.count())
        ));
        String raw = chat(reasoningModel, prompt, 0.2, "api-scenario-gen");
        try {
            List<ApiScenario> scenarios = objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ApiScenario.class));
            return new GeneratedApiTestScenarios(scenarios, reasoningModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse generated API scenarios: " + raw, e);
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
        String raw = chat(reasoningModel, prompt, 0.2, "api-code-gen");
        try {
            JsonNode node = objectMapper.readTree(raw);
            return new GeneratedApiTestCode(node.path("generatedCode").asText(), reasoningModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse generated API test code: " + raw, e);
        }
    }

    @Override
    public List<LocatorSuggestion> generateLocators(LocatorGenRequest request) {
        String prompt = prompts.render("locator-generation.txt", Map.of(
                "domSnapshot", request.domSnapshot(),
                "targetDescription", request.targetDescription()
        ));
        String raw = chat(fastModel, prompt, 0.2, "locator-gen");
        try {
            return objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, LocatorSuggestion.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse locator suggestions: " + raw, e);
        }
    }

    @Override
    public FailureAnalysisResult analyzeFailure(FailureContext context) {
        String prompt = prompts.render("failure-analysis.txt", Map.of(
                "errorMessage", context.errorMessage() == null ? "" : context.errorMessage(),
                "stackTrace", context.stackTrace() == null ? "" : context.stackTrace(),
                "recentLogLines", context.recentLogLines() == null ? "" : String.join("\n", context.recentLogLines()),
                "domOrResponseSnapshot", context.domOrResponseSnapshot() == null ? "" : context.domOrResponseSnapshot().toString()
        ));
        String raw = chat(reasoningModel, prompt, 0.2, "failure-analysis");
        try {
            return objectMapper.readValue(raw, FailureAnalysisResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse failure analysis: " + raw, e);
        }
    }

    @Override
    public String summarizeLogChunk(String logChunk) {
        String prompt = prompts.render("log-chunk-summary.txt", Map.of("logChunk", logChunk));
        return chat(fastModel, prompt, 0.2, "log-summary");
    }

    @Override
    public ScreenshotAnalysisResult analyzeScreenshot(ScreenshotContext context) {
        // NOTE: only enable this path with a vision-capable OpenAI model (e.g. gpt-4.1).
        // Kept minimal here; Gemini is the recommended default for screenshot analysis.
        throw new UnsupportedOperationException(
                "Screenshot analysis via OpenAiProvider is not wired up in this scaffold; use GeminiProvider or extend this method with a vision-capable model call.");
    }

    private String chat(String model, String prompt, double temperature, String feature) {
        // NOTE: OpenAI's json_object response_format requires a top-level JSON object, not an
        // array. The shared scenario-generation prompt asks for a top-level array; if you
        // deploy with llm.provider=openai, either wrap that prompt's output in an object for
        // this provider, or use response_format: json_schema with an array-wrapping schema.
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "temperature", temperature,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", "You always respond with valid JSON only, no markdown fences."),
                            Map.of("role", "user", "content", prompt)
                    )
            );

            String raw = openAiWebClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(raw);
            String content = root.path("choices").get(0).path("message").path("content").asText();
            callLogRepository.save(new LlmCallLog(name(), model, feature,
                    root.path("usage").path("prompt_tokens").asInt(0),
                    root.path("usage").path("completion_tokens").asInt(0),
                    System.currentTimeMillis() - start, true, null));
            return content;
        } catch (Exception e) {
            callLogRepository.save(new LlmCallLog(name(), model, feature, 0, 0,
                    System.currentTimeMillis() - start, false, e.getMessage()));
            throw new RuntimeException("OpenAI call failed for feature " + feature, e);
        }
    }
}
