package com.aitestplatform.llm;

import com.aitestplatform.llm.dto.LlmDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public GeneratedApiTests generateApiTests(ApiTestGenRequest request) {
        String prompt = prompts.render("api-test-generation.txt", Map.of("openApiSpec", request.openApiSpec()));
        String raw = chat(reasoningModel, prompt, 0.2, "api-test-gen");
        try {
            List<GeneratedApiTest> tests = objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, GeneratedApiTest.class));
            return new GeneratedApiTests(tests, reasoningModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse generated API tests: " + raw, e);
        }
    }

    @Override
    public List<LocatorSuggestion> generateLocators(LocatorGenRequest request) {
        String prompt = prompts.render("locator-generation.txt", Map.of(
                "domSnapshot", request.domSnapshot(),
                "targetDescriptions", numberTargets(request.targetDescriptions())
        ));
        String raw = chat(fastModel, prompt, 0.2, "locator-gen");
        try {
            List<LocatorSuggestion> suggestions = objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, LocatorSuggestion.class));
            if (suggestions.size() != request.targetDescriptions().size()) {
                throw new IllegalStateException("Expected " + request.targetDescriptions().size()
                        + " locator suggestions, got " + suggestions.size());
            }
            return suggestions;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse locator suggestions: " + raw, e);
        }
    }

    private String numberTargets(List<String> targetDescriptions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targetDescriptions.size(); i++) {
            sb.append(i + 1).append(". ").append(targetDescriptions.get(i)).append("\n");
        }
        return sb.toString();
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
        // array. The shared prompt templates ask for a top-level array for list-returning
        // features (test cases, API tests, locators). If you deploy with llm.provider=openai,
        // either switch those prompts to return a wrapped object for this provider, or use
        // response_format: json_schema with an explicit array-wrapping schema.
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
                    .retryWhen(retrySpec())
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

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 500, 502, 503, 504);
    private static final int MAX_RETRIES = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(10);

    /**
     * Retries transient upstream failures (rate limiting, momentary outages) before giving
     * up. When the upstream sends a Retry-After header (common on 429s), that's honored
     * instead of the fixed exponential schedule — a real rate-limit window is usually longer
     * than 500ms-5s, so ignoring the header just burns through retries without ever waiting
     * long enough to succeed. Retry-After is still capped (MAX_RETRY_AFTER) so one request
     * can't block past nginx's proxy timeout. Non-retryable errors (4xx other than 429, parse
     * failures, etc.) pass straight through untouched.
     */
    private Retry retrySpec() {
        return Retry.from(signals -> signals.flatMap(signal -> {
            Throwable failure = signal.failure();
            if (signal.totalRetries() >= MAX_RETRIES || !isRetryable(failure)) {
                return Mono.error(failure);
            }
            return Mono.delay(nextDelay(failure, signal.totalRetries()));
        }));
    }

    private Duration nextDelay(Throwable failure, long attempt) {
        if (failure instanceof WebClientResponseException wcre) {
            Duration retryAfter = parseRetryAfter(wcre);
            if (retryAfter != null) {
                return retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
            }
        }
        Duration backoff = BASE_BACKOFF.multipliedBy((long) Math.pow(2, attempt));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    private Duration parseRetryAfter(WebClientResponseException wcre) {
        String header = wcre.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(header.trim())));
        } catch (NumberFormatException notASecondsValue) {
            try {
                ZonedDateTime target = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME);
                long seconds = Duration.between(ZonedDateTime.now(target.getZone()), target).getSeconds();
                return Duration.ofSeconds(Math.max(0, seconds));
            } catch (Exception notADateEither) {
                return null;
            }
        }
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException wcre) {
            return RETRYABLE_STATUS_CODES.contains(wcre.getStatusCode().value());
        }
        return false;
    }
}
