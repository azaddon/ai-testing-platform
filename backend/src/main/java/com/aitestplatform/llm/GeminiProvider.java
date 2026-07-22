package com.aitestplatform.llm;

import com.aitestplatform.llm.dto.LlmDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public GeneratedApiTests generateApiTests(ApiTestGenRequest request) {
        String prompt = prompts.render("api-test-generation.txt", Map.of(
                "openApiSpec", request.openApiSpec()
        ));
        String raw = callTextModel(reasoningModel, prompt, 0.2, "api-test-gen");
        try {
            List<GeneratedApiTest> tests = objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, GeneratedApiTest.class));
            return new GeneratedApiTests(tests, reasoningModel);
        } catch (Exception e) {
            throw new LlmResponseParseException("Failed to parse generated API tests", raw, e);
        }
    }

    @Override
    public List<LocatorSuggestion> generateLocators(LocatorGenRequest request) {
        String prompt = prompts.render("locator-generation.txt", Map.of(
                "domSnapshot", request.domSnapshot(),
                "targetDescriptions", numberTargets(request.targetDescriptions())
        ));
        String raw = callTextModel(fastModel, prompt, 0.2, "locator-gen");
        try {
            List<LocatorSuggestion> suggestions = objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, LocatorSuggestion.class));
            if (suggestions.size() != request.targetDescriptions().size()) {
                throw new IllegalStateException("Expected " + request.targetDescriptions().size()
                        + " locator suggestions, got " + suggestions.size());
            }
            return suggestions;
        } catch (Exception e) {
            throw new LlmResponseParseException("Failed to parse locator suggestions", raw, e);
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
                    .retryWhen(retrySpec())
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
                    .retryWhen(retrySpec())
                    .block();

            String text = extractText(raw);
            logCall(model, feature, start, true, null);
            return text;
        } catch (Exception e) {
            logCall(model, feature, start, false, e.getMessage());
            throw new LlmCallException("Gemini call failed for feature " + feature, e);
        }
    }

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 500, 502, 503, 504);
    private static final int MAX_RETRIES = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(10);

    /**
     * Retries transient upstream failures (rate limiting, momentary outages) before giving
     * up. When Gemini sends a Retry-After header (common on 429s), that's honored instead of
     * the fixed exponential schedule — a real rate-limit window is usually longer than
     * 500ms-5s, so ignoring the header just burns through retries without ever waiting long
     * enough to succeed. Retry-After is still capped (MAX_RETRY_AFTER) so one request can't
     * block past nginx's proxy timeout. Non-retryable errors (4xx other than 429, parse
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
