package com.aitestplatform.testcase;

import com.aitestplatform.execution.TestRun;
import com.aitestplatform.execution.TestRunOrchestratorService;
import com.aitestplatform.execution.UiTestScript;
import com.aitestplatform.execution.UiTestScriptGenerationService;
import com.aitestplatform.testcase.dto.TestCaseDtos.GenerateRequest;
import com.aitestplatform.testcase.dto.TestCaseDtos.RunRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/test-cases")
public class TestCaseController {

    private final TestCaseGenerationService service;
    private final UiTestScriptGenerationService uiTestScriptGenerationService;
    private final TestRunOrchestratorService testRunOrchestratorService;

    public TestCaseController(TestCaseGenerationService service,
                               UiTestScriptGenerationService uiTestScriptGenerationService,
                               TestRunOrchestratorService testRunOrchestratorService) {
        this.service = service;
        this.uiTestScriptGenerationService = uiTestScriptGenerationService;
        this.testRunOrchestratorService = testRunOrchestratorService;
    }

    @PostMapping("/generate")
    public List<TestCase> generate(@PathVariable String projectId, @RequestBody GenerateRequest request) {
        return service.generate(projectId, request);
    }

    @GetMapping
    public List<TestCase> list(@PathVariable String projectId,
                                @RequestParam(required = false) String status) {
        return service.listByProject(projectId, status);
    }

    @PutMapping("/{testCaseId}/approve")
    public TestCase approve(@PathVariable String projectId, @PathVariable String testCaseId) {
        return service.approve(testCaseId);
    }

    /**
     * Bridges an approved, AI-generated TestCase to an actual execution: generates a
     * UiTestScript (resolving each step's locator via the LLM) and starts a TestRun
     * against it. This is what was missing between "Generate test cases" and the
     * dashboard ever showing a run.
     */
    @PostMapping("/{testCaseId}/run")
    public TestRun run(@PathVariable String projectId, @PathVariable String testCaseId,
                        @RequestBody RunRequest request) {
        TestCase testCase = service.getById(testCaseId);
        UiTestScript script = uiTestScriptGenerationService.generate(testCase, request.targetUrl());
        return testRunOrchestratorService.startUiRun(projectId, null, List.of(script.getId()));
    }
}
