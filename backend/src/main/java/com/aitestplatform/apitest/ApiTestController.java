package com.aitestplatform.apitest;

import com.aitestplatform.apitest.dto.ApiTestDtos.GenerateRequest;
import com.aitestplatform.apitest.dto.ApiTestDtos.ExecuteRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
//import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ApiTestController {

    private final ApiTestGenerationService generationService;
    private final ApiTestExecutionService executionService;

    public ApiTestController(ApiTestGenerationService generationService, ApiTestExecutionService executionService) {
        this.generationService = generationService;
        this.executionService = executionService;
    }

    @PostMapping("/projects/{projectId}/api-tests/generate")
    public List<ApiTestScript> generate(@PathVariable String projectId, @RequestBody GenerateRequest request) {
        return generationService.generate(projectId, request);
    }

    @GetMapping("/projects/{projectId}/api-tests")
    public List<ApiTestScript> list(@PathVariable String projectId) {
        return generationService.listByProject(projectId);
    }

    @PostMapping("/api-tests/{scriptId}/execute")
    public ApiTestScript execute(@PathVariable String scriptId, @RequestBody ExecuteRequest body) {
        return executionService.execute(scriptId, body.baseURI());
    }
}
