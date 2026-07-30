package com.aitestplatform.apitest;

import com.aitestplatform.common.ApiTestWorkflowException.ExecutionModelNotGeneratedException;
import com.aitestplatform.common.ApiTestWorkflowException.ScenarioNotFoundException;
import com.aitestplatform.domain.execution.api.ApiExecutionModel;
import com.aitestplatform.domain.execution.api.ApiExecutionResult;
import com.aitestplatform.execution.TestRun;
import com.aitestplatform.execution.TestRunRepository;
import com.aitestplatform.failureanalysis.FailureAnalysisService;
import com.aitestplatform.infrastructure.execution.restassured.RestAssuredApiExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Runs an ApiTestScript's execution model directly through RestAssuredApiExecutor — no
 * source generation, no javac, no URLClassLoader. The LLM's output was already mapped into
 * the domain ApiExecutionModel and validated back in ApiTestGenerationService.generateCode();
 * this service's only remaining job is resolving the environment-specific baseUri against
 * the model's endpoint and delegating execution to the executor.
 *
 * (This replaces the old compileAndRun() javac/URLClassLoader implementation entirely — that
 * whole class of fat-jar-classpath bug is eliminated because there's no runtime compilation
 * step anymore.)
 *
 * Also writes a TestRun(type="api") per execution — previously this service only updated the
 * ApiTestScript's own lastRunResult, so API executions were invisible to AnalyticsService and
 * TestRunController.list(), which both read only the testRun collection. UI runs (via
 * TestRunOrchestratorService) always had this; API runs never did, which is why the dashboard
 * showed "ui" rows only. Failed runs also auto-trigger FailureAnalysisService so the frontend's
 * GET /analysis call (fired as soon as it sees status=="failed") has something to find.
 */
@Service
public class ApiTestExecutionService {

    private final ApiTestScriptRepository repository;
    private final RestAssuredApiExecutor executor;
    private final TestRunRepository testRunRepository;
    private final FailureAnalysisService failureAnalysisService;

    public ApiTestExecutionService(ApiTestScriptRepository repository, RestAssuredApiExecutor executor,
                                    TestRunRepository testRunRepository, FailureAnalysisService failureAnalysisService) {
        this.repository = repository;
        this.executor = executor;
        this.testRunRepository = testRunRepository;
        this.failureAnalysisService = failureAnalysisService;
    }

    public ApiTestScript execute(String scriptId, String baseUri) {
        ApiTestScript script = repository.findById(scriptId)
                .orElseThrow(() -> new ScenarioNotFoundException(scriptId));

        if (script.getExecutionModel() == null) {
            throw new ExecutionModelNotGeneratedException(scriptId);
        }

        // Defense-in-depth: re-validate even though generateCode() already checked this,
        // in case a script's model was ever written some other way (direct DB edit, a
        // future "edit model" feature, etc).
        ApiExecutionModelValidator.validate(scriptId, script.getExecutionModel());

        script.setStatus(ScriptStatus.RUNNING);
        repository.save(script);

        TestRun run = new TestRun();
        run.setProjectId(script.getProjectId());
        run.setSuiteId(scriptId);
        run.setType("api");
        run.setStatus("running");
        run.setStartedAt(Instant.now());
        run = testRunRepository.save(run);

        ApiExecutionModel resolved = withBaseUri(script.getExecutionModel(), baseUri);
        ApiExecutionResult result = executor.execute(resolved);

        script.setLastRunResult(new ApiTestScript.LastRunResult(
                result.actualStatus(), result.durationMs(), result.passed(),
                result.errorMessage() != null ? result.errorMessage() : result.responseBody()));
        script.setStatus(result.passed() ? ScriptStatus.PASSED : ScriptStatus.FAILED);
        repository.save(script);

        String runStatus = result.passed() ? "passed" : "failed";
        run.setStatus(runStatus);
        run.setFinishedAt(Instant.now());
        run.setResults(List.of(new TestRun.StepResult(scriptId, runStatus, result.durationMs(), result.errorMessage())));

        if (!result.passed()) {
            analyzeFailure(run.getId(), result);
        }
        testRunRepository.save(run);

        return script;
    }

    /**
     * Best-effort: runs BEFORE the TestRun's own save so the FailureAnalysis document already
     * exists in Mongo by the time the frontend sees status=="failed" over the dashboard/detail
     * poll and fires its GET /analysis call. A failed LLM call here (rate limit, provider
     * outage) must never mask the actual test result, so any exception is swallowed.
     */
    private void analyzeFailure(String runId, ApiExecutionResult result) {
        try {
            String errorMessage = result.errorMessage() != null ? result.errorMessage()
                    : "Request completed (HTTP " + result.actualStatus() + ") but one or more assertions failed.";
            failureAnalysisService.analyze(runId, errorMessage, null, result.responseBody(), null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Runs every script in a project that currently has an execution model; scripts still
     * at SCENARIO_GENERATED (no model yet) are skipped rather than failing the whole batch,
     * since a partially-authored suite is a normal state, not an error.
     */
    public List<ApiTestScript> executeAllRunnable(String projectId, String baseUri) {
        return repository.findByProjectId(projectId).stream()
                .filter(s -> s.getExecutionModel() != null)
                .map(s -> execute(s.getId(), baseUri))
                .toList();
    }

    /**
     * baseUri is an environment concern (which host/port to hit right now), not something
     * the LLM generates as part of the execution model — so it's merged in here, at the
     * application layer, rather than being a field on ApiExecutionModel itself. If the
     * model's endpoint is already an absolute URL, baseUri is left alone.
     */
    private ApiExecutionModel withBaseUri(ApiExecutionModel model, String baseUri) {
        String endpoint = model.endpoint() == null ? "" : model.endpoint();
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return model;
        }
        String base = baseUri == null ? "" : baseUri;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String path = endpoint.startsWith("/") ? endpoint : "/" + endpoint;

        return new ApiExecutionModel(
                model.method(),
                base + path,
                model.headers(),
                model.queryParams(),
                model.pathParams(),
                model.cookies(),
                model.requestBody(),
                model.expectedStatus(),
                model.assertions());
    }
}
