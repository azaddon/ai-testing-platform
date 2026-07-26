package com.aitestplatform.apitest.dto;

import java.util.List;

public class ApiTestDtos {
    public record GenerateRequest(String openApiSpec, List<String> endpointFilters) {}
    public record ExecuteRequest(String baseURI){}
}
