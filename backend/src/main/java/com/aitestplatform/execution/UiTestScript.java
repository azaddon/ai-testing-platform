package com.aitestplatform.execution;

import com.aitestplatform.domain.execution.ui.UiExecutionModel;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Composition, not inheritance: this script HAS-A UiExecutionModel; it doesn't extend one.
 * executionModel (url, steps, locators, assertions, screenshots) is the data
 * PlaywrightUiExecutor actually runs. renderedCode is a deterministically-rendered,
 * human-readable preview built FROM executionModel by CodeArtifactRenderer — a display
 * artifact only, never compiled or executed.
 */
@Document(collection = "uiTestScript")
public class UiTestScript {

    @Id
    private String id;
    private String testCaseId;
    private UiExecutionModel executionModel;
    private String renderedCode;
    private Instant createdAt = Instant.now();

    public UiTestScript() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTestCaseId() { return testCaseId; }
    public void setTestCaseId(String testCaseId) { this.testCaseId = testCaseId; }
    public UiExecutionModel getExecutionModel() { return executionModel; }
    public void setExecutionModel(UiExecutionModel executionModel) { this.executionModel = executionModel; }
    public String getRenderedCode() { return renderedCode; }
    public void setRenderedCode(String renderedCode) { this.renderedCode = renderedCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
