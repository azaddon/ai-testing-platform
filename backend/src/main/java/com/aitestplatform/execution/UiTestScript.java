package com.aitestplatform.execution;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Executable Playwright automation for a TestCase. Authoring this from the natural-language
 * TestCase steps is out of scope for this scaffold (see README "UI Locator Generator" note) —
 * in practice you'd generate this body with an LLM call similar to ApiTestGenerationService,
 * using LlmProvider.generateLocators() to resolve each step's target element.
 *
 * Convention: generatedCode is the body of:
 *   public static void run(Page page) throws Exception { ... }
 */
@Document(collection = "uiTestScript")
public class UiTestScript {

    @Id
    private String id;
    private String testCaseId;
    private String targetUrl;
    private String generatedCode;
    private Instant createdAt = Instant.now();

    public UiTestScript() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTestCaseId() { return testCaseId; }
    public void setTestCaseId(String testCaseId) { this.testCaseId = testCaseId; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    public String getGeneratedCode() { return generatedCode; }
    public void setGeneratedCode(String generatedCode) { this.generatedCode = generatedCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
