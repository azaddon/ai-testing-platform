package com.aitestplatform.execution;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.aitestplatform.apitest.ScriptStatus;
import com.aitestplatform.domain.execution.ui.UiExecutionResult;
import com.aitestplatform.failureanalysis.FailureAnalysisService;
import com.aitestplatform.infrastructure.execution.playwright.PlaywrightUiExecutor;

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
 *
 * Delegates to PlaywrightUiExecutor, which interprets each script's UiExecutionModel
 * directly — no source generation, no javac, no reflective invocation.
 */
@Component
public class TestRunExecutionWorker {

    private final TestRunRepository testRunRepository;
    private final UiTestScriptRepository uiTestScriptRepository;
    private final PlaywrightUiExecutor playwrightUiExecutor;
    private final TestRunWebSocketHandler webSocketHandler;
    private final FailureAnalysisService failureAnalysisService;

    public TestRunExecutionWorker(TestRunRepository testRunRepository,
                                   UiTestScriptRepository uiTestScriptRepository,
                                   PlaywrightUiExecutor playwrightUiExecutor,
                                   TestRunWebSocketHandler webSocketHandler,
                                   FailureAnalysisService failureAnalysisService) {
        this.testRunRepository = testRunRepository;
        this.uiTestScriptRepository = uiTestScriptRepository;
        this.playwrightUiExecutor = playwrightUiExecutor;
        this.webSocketHandler = webSocketHandler;
        this.failureAnalysisService = failureAnalysisService;
    }

    @Async
    public void execute(String runId, List<String> uiTestScriptIds) {
        TestRun run = testRunRepository.findById(runId).orElseThrow();
        run.setStatus(ScriptStatus.RUNNING.toString());
        testRunRepository.save(run);
        webSocketHandler.broadcast(runId, run);

        List<TestRun.StepResult> results = new ArrayList<>();
        boolean anyFailed = false;
        try {
            for (String scriptId : uiTestScriptIds) {
                UiTestScript script = uiTestScriptRepository.findById(scriptId).orElse(null);
                if (script == null || script.getExecutionModel() == null) continue;

                UiExecutionResult result = playwrightUiExecutor.execute(script.getExecutionModel());

                String status = result.passed() ? ScriptStatus.PASSED.toString() : ScriptStatus.FAILED.toString();
                anyFailed = anyFailed || !result.passed();
                results.add(new TestRun.StepResult(
                        script.getTestCaseId(), status, result.durationMs(), result.errorMessage()));

                run.setResults(new ArrayList<>(results));
                testRunRepository.save(run);
                webSocketHandler.broadcast(runId, run);
            }

            run.setStatus(anyFailed ? ScriptStatus.FAILED.toString() : ScriptStatus.PASSED.toString());
        } catch (Exception e) {
            e.printStackTrace();

            run.setStatus(ScriptStatus.FAILED.toString());

            results.add(new TestRun.StepResult(
                    "SYSTEM",
                    ScriptStatus.FAILED.toString(),
                    0,
                    e.getMessage()));

            run.setResults(results);
        } finally {
            // Auto-trigger LLM failure analysis BEFORE the final save/broadcast below, so the
            // FailureAnalysis document already exists in Mongo by the time the frontend sees
            // status=="failed" and fires its GET /analysis call (see RunDetail.tsx) — without
            // this, that endpoint 404s and the "AI Failure Analysis" panel silently never
            // appears, even though the analysis feature itself works fine when called directly.
            if (ScriptStatus.FAILED.toString().equals(run.getStatus())) {
                analyzeFailure(runId, results);
            }
            run.setFinishedAt(java.time.Instant.now());
            testRunRepository.save(run);
            webSocketHandler.broadcast(runId, run);
        }
    }

    /**
     * Best-effort: a failed LLM call here (rate limit, provider outage) must never mask the
     * actual test result, so any exception is swallowed and logged rather than propagated.
     */
    private void analyzeFailure(String runId, List<TestRun.StepResult> results) {
        try {
            String errorMessage = results.stream()
                    .filter(r -> ScriptStatus.FAILED.toString().equals(r.status()))
                    .map(r -> r.testCaseId() + ": " + (r.errorMessage() == null ? "no error message captured" : r.errorMessage()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            failureAnalysisService.analyze(runId, errorMessage, null, errorMessage, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
