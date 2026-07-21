package com.aitestplatform.execution;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "testRun")
public class TestRun {

    @Id
    private String id;
    private String projectId;
    private String suiteId;
    private String type;      // ui | api
    private String status = "queued"; // queued | running | passed | failed | flaky
    private Instant startedAt;
    private Instant finishedAt;
    private List<StepResult> results;
    private Artifacts artifacts;

    public record StepResult(String testCaseId, String status, long durationMs, String errorMessage) {}
    public record Artifacts(List<String> screenshotRefs, String logsRef, String harRef) {}

    public TestRun() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getSuiteId() { return suiteId; }
    public void setSuiteId(String suiteId) { this.suiteId = suiteId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public List<StepResult> getResults() { return results; }
    public void setResults(List<StepResult> results) { this.results = results; }
    public Artifacts getArtifacts() { return artifacts; }
    public void setArtifacts(Artifacts artifacts) { this.artifacts = artifacts; }
}
