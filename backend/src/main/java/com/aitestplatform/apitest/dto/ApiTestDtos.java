package com.aitestplatform.apitest.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public class ApiTestDtos {
    public record GenerateRequest(@NotBlank String openApiSpec, @NotEmpty List<String> endpointFilters) {}
    public record ExecuteRequest( @NotBlank String baseURI){}
}
