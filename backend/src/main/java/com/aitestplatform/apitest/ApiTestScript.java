package com.aitestplatform.apitest;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "apiTestScript")
public class ApiTestScript {

    @Id
    private String id;
    private String projectId;
    private String endpoint;
    private String method;
    private String scenario;

    /** Spec context captured at scenario-generation time, reused for accurate code generation. */
    private String openApiSpecContext;

    private String generatedCode; // null until "Generate Code" succeeds

    private ScriptStatus status = ScriptStatus.SCENARIO_GENERATED;

    private LastRunResult lastRunResult;
    private Instant createdAt = Instant.now();

    public record LastRunResult(int statusCode, long latencyMs, boolean assertionsPassed, String output) {}

    public ApiTestScript() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }
    public String getOpenApiSpecContext() { return openApiSpecContext; }
    public void setOpenApiSpecContext(String openApiSpecContext) { this.openApiSpecContext = openApiSpecContext; }
    public String getGeneratedCode() { return generatedCode; }
    public void setGeneratedCode(String generatedCode) { this.generatedCode = generatedCode; }
    public ScriptStatus getStatus() { return status; }
    public void setStatus(ScriptStatus status) { this.status = status; }
    public LastRunResult getLastRunResult() { return lastRunResult; }
    public void setLastRunResult(LastRunResult lastRunResult) { this.lastRunResult = lastRunResult; }
    public Instant getCreatedAt() { return createdAt; }
}
