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
        String raw = chatForList(fastModel, prompt, 0.3, "test-case-gen");
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
        String raw = chatForList(reasoningModel, prompt, 0.2, "api-scenario-gen");
        try {
            List<ApiScenario> scenarios = objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ApiScenario.class));
            return new GeneratedApiTestScenarios(scenarios, reasoningModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse generated API scenarios: " + raw, e);
        }
    }

    @Override
    public GeneratedApiExecutionModel generateApiExecutionModel(ApiExecutionModelGenRequest request) {
        String prompt = prompts.render("api-execution-model-generation.txt", Map.of(
                "endpoint", request.endpoint(),
                "method", request.method(),
                "scenario", request.scenario(),
                "openApiSpecContext", request.openApiSpecContext() == null ? "" : request.openApiSpecContext()
        ));
        String raw = chat(reasoningModel, prompt, 0.2, "api-execution-model-gen");
        try {
            JsonNode node = objectMapper.readTree(raw);
            List<GeneratedApiAssertion> assertions = objectMapper.convertValue(
                    node.path("assertions"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, GeneratedApiAssertion.class));
            return new GeneratedApiExecutionModel(
                    node.path("method").asText(),
                    node.path("endpoint").asText(),
                    toStringMap(node.path("headers")),
                    toStringMap(node.path("queryParams")),
                    toStringMap(node.path("pathParams")),
                    toStringMap(node.path("cookies")),
                    node.path("requestBody").asText(""),
                    node.path("expectedStatus").asInt(200),
                    assertions == null ? List.of() : assertions,
                    reasoningModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse generated API execution model: " + raw, e);
        }
    }

    private Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        }
        return map;
    }

    @Override
    public List<LocatorSuggestion> generateLocators(LocatorGenRequest request) {
        String prompt = prompts.render("locator-generation.txt", Map.of(
                "domSnapshot", request.domSnapshot(),
                "targetDescription", request.targetDescription()
        ));
        String raw = chatForList(fastModel, prompt, 0.2, "locator-gen");
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
        // Plain prose out, not JSON — unlike every other feature here, this prompt asks for
        // 2-4 sentences of text, so it uses chatPlainText() instead of chat()/chatForList().
        // Forcing response_format=json_object on a free-text prompt (as the old shared chat()
        // path did) doesn't error, it just makes the model wrap the summary in some arbitrary
        // JSON object — which then shows up verbatim as the "log summary" in FailureAnalysis,
        // e.g. {"summary": "..."} instead of readable text.
        String prompt = prompts.render("log-chunk-summary.txt", Map.of("logChunk", logChunk));
        return chatPlainText(fastModel, prompt, 0.2, "log-summary");
    }

    @Override
    public ScreenshotAnalysisResult analyzeScreenshot(ScreenshotContext context) {
        // NOTE: only enable this path with a vision-capable OpenAI model (e.g. gpt-4.1).
        // Kept minimal here; Gemini is the recommended default for screenshot analysis.
        throw new UnsupportedOperationException(
                "Screenshot analysis via OpenAiProvider is not wired up in this scaffold; use GeminiProvider or extend this method with a vision-capable model call.");
    }

    /**
     * Like chat(), but for prompts (shared with GeminiProvider) that ask the model to respond
     * with a top-level JSON ARRAY. OpenAI-compatible response_format={"type":"json_object"}
     * legally forbids a bare array as the top-level response — Gemini has no such restriction,
     * which is why these prompts were written array-first. The system message below asks the
     * model to wrap the array as {"items": [...]} instead, and unwrapListPayload() below
     * extracts it back out so callers can keep parsing a plain JSON array same as before.
     *
     * This was found the hard way on Groq's llama-3.1-8b-instant: given a prompt saying
     * "respond ONLY with a JSON array" plus an API constraint saying "must be an object", it
     * sometimes ignored the {"items": [...]} wrapping instruction too and returned a single
     * bare object (one scenario/test case/locator) instead of an array of them — exactly the
     * "Failed to parse generated API scenarios: { ...one object... }" error this fixes.
     * unwrapListPayload() handles all three shapes (proper {"items":[...]} wrapper, a bare
     * array despite the instructions, or a single ungrouped object) rather than trusting any
     * one model to follow the format instruction perfectly on every call.
     */
    private String chatForList(String model, String prompt, double temperature, String feature) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "temperature", temperature,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content",
                                    "You always respond with valid JSON only, no markdown fences. "
                                            + "Your response MUST be a single JSON object (never a bare array) — "
                                            + "wrap the requested list in an object with exactly one field named "
                                            + "\"items\" whose value is the JSON array, e.g. {\"items\": [ ... ]}."),
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
            return unwrapListPayload(content);
        } catch (Exception e) {
            callLogRepository.save(new LlmCallLog(name(), model, feature, 0, 0,
                    System.currentTimeMillis() - start, false, e.getMessage()));
            throw new RuntimeException("OpenAI call failed for feature " + feature, e);
        }
    }

    private String unwrapListPayload(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root.isArray()) {
                return content;
            }
            if (root.isObject()) {
                if (root.has("items") && root.get("items").isArray()) {
                    return root.get("items").toString();
                }
                // Some other object shape (e.g. wrapped under a different key, or a single
                // bare item instead of a one-item array) — prefer the first array field found
                // over guessing at a key name; otherwise fall back to wrapping the whole
                // object as a single-element array so one still-usable item beats a hard failure.
                var fields = root.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if (entry.getValue().isArray()) {
                        return entry.getValue().toString();
                    }
                }
                return "[" + root + "]";
            }
        } catch (Exception ignored) {
            // Not valid JSON at all — fall through and let the caller's own parse attempt
            // fail with the original content, so the resulting error still shows the real
            // raw model output instead of a secondary, more confusing parse error.
        }
        return content;
    }

    /** No response_format constraint and no "respond with JSON" system message — for prompts
     *  (like log-chunk-summary.txt) whose expected output is prose, not structured data. */
    private String chatPlainText(String model, String prompt, double temperature, String feature) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "temperature", temperature,
                    "messages", List.of(
                            Map.of("role", "system", "content", "You are concise and factual. Respond in plain text only, no markdown."),
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

    private String chat(String model, String prompt, double temperature, String feature) {
        // NOTE: this is only used for calls whose expected shape is a single JSON object
        // (generateApiExecutionModel, analyzeFailure). List-returning calls use chatForList()
        // and plain-text calls use chatPlainText() — see their javadocs for why.
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
