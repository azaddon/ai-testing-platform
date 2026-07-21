package com.aitestplatform.testcase.dto;

import java.util.List;

public class TestCaseDtos {

    public record GenerateRequest(String requirementText, List<String> testTypes, int count) {}

    public record UpdateRequest(String title, String description, List<String> preconditions,
                                 List<StepDto> steps, String type, String priority, List<String> tags) {}

    public record StepDto(String action, String expected) {}

    /** Target app URL to execute an approved test case's UI script against. */
    public record RunRequest(String targetUrl) {}
}
