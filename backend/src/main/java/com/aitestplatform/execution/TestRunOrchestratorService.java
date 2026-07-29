package com.aitestplatform.execution;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates a TestRun across one or more UI test scripts, updating Mongo and pushing
 * live progress over the WebSocket channel as each step completes. API-test runs are
 * triggered separately via ApiTestExecutionService (see ApiTestController) since Rest
 * Assured tests don't need a browser or step-by-step streaming.
 */
@Service
public class TestRunOrchestratorService {

    private final TestRunRepository testRunRepository;
    private final UiTestScriptRepository uiTestScriptRepository;
    private final PlaywrightExecutionService playwrightExecutionService;
    private final TestRunWebSocketHandler webSocketHandler;

    public TestRunOrchestratorService(TestRunRepository testRunRepository,
                                       UiTestScriptRepository uiTestScriptRepository,
                                       PlaywrightExecutionService playwrightExecutionService,
                                       TestRunWebSocketHandler webSocketHandler) {
        this.testRunRepository = testRunRepository;
        this.uiTestScriptRepository = uiTestScriptRepository;
        this.playwrightExecutionService = playwrightExecutionService;
        this.webSocketHandler = webSocketHandler;
    }

    public TestRun startUiRun(String projectId, String suiteId, List<String> uiTestScriptIds) {
        TestRun run = new TestRun();
        run.setProjectId(projectId);
        run.setSuiteId(suiteId);
        run.setType("ui");
        run.setStatus("queued");
        run.setStartedAt(Instant.now());
        run = testRunRepository.save(run);

        executeAsync(run.getId(), uiTestScriptIds);
        return run;
    }

    @Async
    public void executeAsync(String runId, List<String> uiTestScriptIds) {
        TestRun run = testRunRepository.findById(runId).orElseThrow();
        run.setStatus("running");
        testRunRepository.save(run);
        webSocketHandler.broadcast(runId, run);

        List<TestRun.StepResult> results = new ArrayList<>();
        boolean anyFailed = false;

        for (String scriptId : uiTestScriptIds) {
            UiTestScript script = uiTestScriptRepository.findById(scriptId).orElse(null);
            if (script == null) continue;

            long start = System.currentTimeMillis();
            var result = playwrightExecutionService.run(script);
            long duration = System.currentTimeMillis() - start;

            String status = result.passed() ? "passed" : "failed";
            anyFailed = anyFailed || !result.passed();
            results.add(new TestRun.StepResult(script.getTestCaseId(), status, duration, result.errorMessage()));

            run.setResults(new ArrayList<>(results));
            webSocketHandler.broadcast(runId, run);
        }

        run.setStatus(anyFailed ? "failed" : "passed");
        run.setFinishedAt(Instant.now());
        run.setResults(results);
        testRunRepository.save(run);
        webSocketHandler.broadcast(runId, run);
    }
}
