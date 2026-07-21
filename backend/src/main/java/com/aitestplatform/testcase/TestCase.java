package com.aitestplatform.testcase;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "testCase")
public class TestCase {

    @Id
    private String id;
    private String projectId;
    private String title;
    private String description;
    private List<String> preconditions;
    private List<Step> steps;
    private String type;          // functional | edge | negative | regression
    private String priority;      // P1 | P2 | P3
    private List<String> tags;
    private String source;        // manual | ai-generated
    private GeneratedBy generatedBy;
    private String status = "draft"; // draft | approved | archived
    private Instant createdAt = Instant.now();

    public record Step(String action, String expected) {}
    public record GeneratedBy(String provider, String model) {}

    public TestCase() {}

    // getters/setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getPreconditions() { return preconditions; }
    public void setPreconditions(List<String> preconditions) { this.preconditions = preconditions; }
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = steps; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public GeneratedBy getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(GeneratedBy generatedBy) { this.generatedBy = generatedBy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
