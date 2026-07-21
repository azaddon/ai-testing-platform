package com.aitestplatform.execution;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class TestRunController {

    private final TestRunOrchestratorService orchestratorService;
    private final TestRunRepository testRunRepository;
    private final UiTestScriptRepository uiTestScriptRepository;

    public TestRunController(TestRunOrchestratorService orchestratorService,
                              TestRunRepository testRunRepository,
                              UiTestScriptRepository uiTestScriptRepository) {
        this.orchestratorService = orchestratorService;
        this.testRunRepository = testRunRepository;
        this.uiTestScriptRepository = uiTestScriptRepository;
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

    /**
     * Starts a new run scoped to only the test cases that failed in a previous run.
     * Reuses each failed test case's most recently generated UiTestScript rather than
     * regenerating locators, since the script already exists from the original run.
     */
    @PostMapping("/test-runs/{runId}/rerun-failed")
    public TestRun rerunFailed(@PathVariable String runId) {
        TestRun original = testRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Test run not found: " + runId));

        List<String> failedTestCaseIds = original.getResults() == null ? List.of()
                : original.getResults().stream()
                        .filter(r -> "failed".equals(r.status()))
                        .map(TestRun.StepResult::testCaseId)
                        .distinct()
                        .collect(Collectors.toList());

        if (failedTestCaseIds.isEmpty()) {
            throw new IllegalStateException("Test run " + runId + " has no failed steps to rerun");
        }

        List<String> scriptIds = failedTestCaseIds.stream()
                .map(testCaseId -> uiTestScriptRepository.findFirstByTestCaseIdOrderByCreatedAtDesc(testCaseId)
                        .orElseThrow(() -> new IllegalStateException(
                                "No UI script found for test case " + testCaseId + "; it may need to be re-run from scratch")))
                .map(UiTestScript::getId)
                .collect(Collectors.toList());

        return orchestratorService.startUiRun(original.getProjectId(), original.getSuiteId(), scriptIds);
    }
}
