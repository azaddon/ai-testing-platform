package com.aitestplatform.execution;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Creates a TestRun record and hands off execution to TestRunExecutionWorker, which does
 * the actual (async) work. Kept as two beans on purpose: an @Async method only runs
 * asynchronously when called through Spring's proxy, and a method here calling another
 * method on `this` would bypass that proxy and run synchronously on the caller's thread —
 * which previously meant the whole browser run happened inside the HTTP request that
 * started it. API-test runs are triggered separately via ApiTestExecutionService (see
 * ApiTestController) since Rest Assured tests don't need a browser or step-by-step streaming.
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
