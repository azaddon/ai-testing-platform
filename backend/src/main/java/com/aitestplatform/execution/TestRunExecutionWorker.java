package com.aitestplatform.execution;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.aitestplatform.apitest.ScriptStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a UI TestRun's steps in the background. This has to live in its own bean, separate
 * from TestRunOrchestratorService: Spring's @Async only takes effect on calls that go through
 * the proxy, and a method calling another @Async method on itself (self-invocation) bypasses
 * that proxy and runs synchronously on the caller's thread. When that method lived on
 * TestRunOrchestratorService, startUiRun() would block the whole HTTP request until every
 * Playwright step finished — easily past nginx's default 60s proxy_read_timeout, surfacing as
 * a 504 to the browser even though the backend was still working.
 */
@Component
public class TestRunExecutionWorker {

    private final TestRunRepository testRunRepository;
    private final UiTestScriptRepository uiTestScriptRepository;
    private final PlaywrightExecutionService playwrightExecutionService;
    private final TestRunWebSocketHandler webSocketHandler;

    public TestRunExecutionWorker(TestRunRepository testRunRepository,
                                   UiTestScriptRepository uiTestScriptRepository,
                                   PlaywrightExecutionService playwrightExecutionService,
                                   TestRunWebSocketHandler webSocketHandler) {
        this.testRunRepository = testRunRepository;
        this.uiTestScriptRepository = uiTestScriptRepository;
        this.playwrightExecutionService = playwrightExecutionService;
        this.webSocketHandler = webSocketHandler;
    }

    @Async
    public void execute(String runId, List<String> uiTestScriptIds) {
        TestRun run = testRunRepository.findById(runId).orElseThrow();
        run.setStatus(ScriptStatus.RUNNING.toString());
        testRunRepository.save(run);
        webSocketHandler.broadcast(runId, run);

        List<TestRun.StepResult> results = new ArrayList<>();
        boolean anyFailed = false;
        try{
        for (String scriptId : uiTestScriptIds) {
            UiTestScript script = uiTestScriptRepository.findById(scriptId).orElse(null);
            if (script == null) continue;

            long start = System.currentTimeMillis();
            var result = playwrightExecutionService.run(script);
            long duration = System.currentTimeMillis() - start;

            String status = result.passed() ? ScriptStatus.PASSED.toString() : ScriptStatus.FAILED.toString();
            anyFailed = anyFailed || !result.passed();
            results.add(new TestRun.StepResult(script.getTestCaseId(), status, duration, result.errorMessage()));

            run.setResults(new ArrayList<>(results));
            testRunRepository.save(run);
            webSocketHandler.broadcast(runId, run);
        }

            run.setStatus(anyFailed ? ScriptStatus.FAILED.toString() : ScriptStatus.PASSED.toString());
        }
        catch (Exception e) {
        e.printStackTrace();

        run.setStatus(ScriptStatus.FAILED.toString());

        results.add(new TestRun.StepResult(
                "SYSTEM",
                ScriptStatus.FAILED.toString(),
                0,
                e.getMessage()));

        run.setResults(results);
    }

        finally{
            run.setFinishedAt(java.time.Instant.now());
            testRunRepository.save(run);
            webSocketHandler.broadcast(runId, run);
        }
        
    }
}
