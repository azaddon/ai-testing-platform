package com.aitestplatform.execution;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Coordinates a TestRun across one or more UI test scripts. Creates the TestRun record, then
 * hands the actual execution off to TestRunExecutionWorker.
 *
 * IMPORTANT: this class must NOT execute the run itself. It used to (an @Async executeAsync()
 * method living right here, called as `executeAsync(...)` from startUiRun() below) — but a
 * method calling another @Async method on itself is Spring's classic self-invocation trap:
 * the call bypasses the proxy that makes @Async actually asynchronous, so it silently ran
 * synchronously on the HTTP request thread instead, AND that old version had no try/catch
 * around the Playwright loop — so any uncaught exception (browser launch failure, target
 * unreachable, etc.) left the TestRun stuck at status="running" forever, exactly like the
 * stuck rows that showed up on the dashboard. TestRunExecutionWorker exists specifically to
 * live in its own bean (so @Async is real) and wraps execution in try/catch/finally (so a
 * failure always reaches a terminal status). This class's only job now is bookkeeping: create
 * the run, delegate, return immediately.
 */
@Service
public class TestRunOrchestratorService {

    private final TestRunRepository testRunRepository;
    private final TestRunExecutionWorker executionWorker;

    public TestRunOrchestratorService(TestRunRepository testRunRepository,
                                       TestRunExecutionWorker executionWorker) {
        this.testRunRepository = testRunRepository;
        this.executionWorker = executionWorker;
    }

    public TestRun startUiRun(String projectId, String suiteId, List<String> uiTestScriptIds) {
        TestRun run = new TestRun();
        run.setProjectId(projectId);
        run.setSuiteId(suiteId);
        run.setType("ui");
        run.setStatus("queued");
        run.setStartedAt(Instant.now());
        run = testRunRepository.save(run);

        executionWorker.execute(run.getId(), uiTestScriptIds);
        return run;
    }
}
