package com.aitestplatform.apitest;

import com.aitestplatform.common.ApiTestWorkflowException.ExecutionModelNotGeneratedException;
import com.aitestplatform.common.ApiTestWorkflowException.ScenarioNotFoundException;
import com.aitestplatform.domain.execution.api.ApiExecutionModel;
import com.aitestplatform.domain.execution.api.ApiExecutionResult;
import com.aitestplatform.infrastructure.execution.restassured.RestAssuredApiExecutor;
import org.springframework.stereotype.Service;

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
 */
@Service
public class ApiTestExecutionService {

    private final ApiTestScriptRepository repository;
    private final RestAssuredApiExecutor executor;

    public ApiTestExecutionService(ApiTestScriptRepository repository, RestAssuredApiExecutor executor) {
        this.repository = repository;
        this.executor = executor;
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

        ApiExecutionModel resolved = withBaseUri(script.getExecutionModel(), baseUri);
        ApiExecutionResult result = executor.execute(resolved);

        script.setLastRunResult(new ApiTestScript.LastRunResult(
                result.actualStatus(), result.durationMs(), result.passed(),
                result.errorMessage() != null ? result.errorMessage() : result.responseBody()));
        script.setStatus(result.passed() ? ScriptStatus.PASSED : ScriptStatus.FAILED);
        return repository.save(script);
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
