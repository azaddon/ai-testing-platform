package com.aitestplatform.apitest.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class ApiTestDtos {
    public record GenerateRequest(@NotBlank String openApiSpec, List<String> endpointFilters, int count) {}
    public record ExecuteRequest(@NotBlank String baseUri) {}
}
