package com.aitestplatform.apitest;

import com.aitestplatform.apitest.dto.ApiTestDtos.ExecuteRequest;
import com.aitestplatform.apitest.dto.ApiTestDtos.GenerateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ApiTestController {

    private final ApiTestGenerationService generationService;
    private final ApiTestExecutionService executionService;

    public ApiTestController(ApiTestGenerationService generationService, ApiTestExecutionService executionService) {
        this.generationService = generationService;
        this.executionService = executionService;
    }

    /** Step 1: "Generate Scenario" button — scenarios only, no code. */
    @PostMapping("/projects/{projectId}/api-tests/generate-scenarios")
    public List<ApiTestScript> generateScenarios(@PathVariable String projectId,
                                                  @Valid @RequestBody GenerateRequest request) {
        return generationService.generateScenarios(projectId, request);
    }

    /**
     * Step 2: "Generate Code" button for one scenario.
     * 404 if the scenario id doesn't exist; 409 if code was already generated for it;
     * 422 if the LLM's output fails GeneratedCodeValidator's safety check.
     */
    @PostMapping("/api-tests/{scriptId}/generate-code")
    public ApiTestScript generateCode(@PathVariable String scriptId) {
        return generationService.generateCode(scriptId);
    }

    @GetMapping("/projects/{projectId}/api-tests")
    public List<ApiTestScript> list(@PathVariable String projectId) {
        return generationService.listByProject(projectId);
    }

    /** Step 3: "Run" button for one scenario. 400 if no code has been generated yet. */
    @PostMapping("/api-tests/{scriptId}/execute")
    public ApiTestScript execute(@PathVariable String scriptId, @Valid @RequestBody ExecuteRequest request) {
        return executionService.execute(scriptId, request.baseUri());
    }

    /** Convenience: run every scenario in the project that currently has generated code. */
    @PostMapping("/projects/{projectId}/api-tests/execute-all")
    public List<ApiTestScript> executeAll(@PathVariable String projectId, @Valid @RequestBody ExecuteRequest request) {
        return executionService.executeAllRunnable(projectId, request.baseUri());
    }
}
