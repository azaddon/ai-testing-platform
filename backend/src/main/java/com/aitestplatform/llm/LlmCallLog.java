package com.aitestplatform.llm;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "llmCallLog")
public class LlmCallLog {

    @Id
    private String id;
    private String provider;
    private String model;
    private String feature;       // test-case-gen, api-test-gen, failure-analysis, log-summary, screenshot-analysis, locator-gen
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    private boolean success;
    private String errorMessage;
    private Instant createdAt = Instant.now();

    public LlmCallLog() {}

    public LlmCallLog(String provider, String model, String feature, int promptTokens,
                       int completionTokens, long latencyMs, boolean success, String errorMessage) {
        this.provider = provider;
        this.model = model;
        this.feature = feature;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public String getId() { return id; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getFeature() { return feature; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public long getLatencyMs() { return latencyMs; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
