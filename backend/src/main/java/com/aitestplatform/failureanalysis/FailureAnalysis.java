package com.aitestplatform.failureanalysis;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "failureAnalysis")
public class FailureAnalysis {

    @Id
    private String id;
    private String testRunId;
    private String rootCause;
    private String category;      // flaky | environment | app-bug | test-bug
    private double confidence;
    private String suggestedFix;
    private String logSummary;
    private String modelUsed;
    private Instant createdAt = Instant.now();

    public FailureAnalysis() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTestRunId() { return testRunId; }
    public void setTestRunId(String testRunId) { this.testRunId = testRunId; }
    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getSuggestedFix() { return suggestedFix; }
    public void setSuggestedFix(String suggestedFix) { this.suggestedFix = suggestedFix; }
    public String getLogSummary() { return logSummary; }
    public void setLogSummary(String logSummary) { this.logSummary = logSummary; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public Instant getCreatedAt() { return createdAt; }
}
