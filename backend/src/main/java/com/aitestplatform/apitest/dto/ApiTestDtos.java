package com.aitestplatform.apitest.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

public class ApiTestDtos {
    public record GenerateRequest(String openApiSpec, List<String> endpointFilters) {}
    public record ExecuteRequest( @NotBlank String baseURI){}
}
