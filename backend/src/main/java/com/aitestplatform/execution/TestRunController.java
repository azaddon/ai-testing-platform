package com.aitestplatform.execution;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TestRunController {

    private final TestRunOrchestratorService orchestratorService;
    private final TestRunRepository testRunRepository;

    public TestRunController(TestRunOrchestratorService orchestratorService, TestRunRepository testRunRepository) {
        this.orchestratorService = orchestratorService;
        this.testRunRepository = testRunRepository;
    }

    public record StartRunRequest(String suiteId, List<String> uiTestScriptIds) {}

    @PostMapping("/projects/{projectId}/test-runs/ui")
    public TestRun startUiRun(@PathVariable String projectId, @RequestBody StartRunRequest request) {
        return orchestratorService.startUiRun(projectId, request.suiteId(), request.uiTestScriptIds());
    }

    @GetMapping("/test-runs/{runId}")
    public TestRun get(@PathVariable String runId) {
        return testRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Test run not found: " + runId));
    }

    @GetMapping("/projects/{projectId}/test-runs")
    public List<TestRun> list(@PathVariable String projectId) {
        return testRunRepository.findByProjectIdOrderByStartedAtDesc(projectId);
    }
}
