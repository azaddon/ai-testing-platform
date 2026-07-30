package com.aitestplatform.apitest;

import com.aitestplatform.application.reporting.CodeArtifactRenderer;
import com.aitestplatform.apitest.dto.ApiTestDtos.GenerateRequest;
import com.aitestplatform.common.ApiTestWorkflowException.ExecutionModelAlreadyGeneratedException;
import com.aitestplatform.common.ApiTestWorkflowException.ScenarioNotFoundException;
import com.aitestplatform.domain.execution.HttpMethod;
import com.aitestplatform.domain.execution.api.ApiAssertion;
import com.aitestplatform.domain.execution.api.ApiAssertionType;
import com.aitestplatform.domain.execution.api.ApiExecutionModel;
import com.aitestplatform.llm.LlmProvider;
import com.aitestplatform.llm.dto.LlmDtos.ApiExecutionModelGenRequest;
import com.aitestplatform.llm.dto.LlmDtos.ApiScenario;
import com.aitestplatform.llm.dto.LlmDtos.ApiTestGenRequest;
import com.aitestplatform.llm.dto.LlmDtos.GeneratedApiAssertion;
import com.aitestplatform.llm.dto.LlmDtos.GeneratedApiExecutionModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ApiTestGenerationService {

    private final LlmProvider llmProvider;
    private final ApiTestScriptRepository repository;
    private final CodeArtifactRenderer codeArtifactRenderer;

    public ApiTestGenerationService(LlmProvider llmProvider, ApiTestScriptRepository repository,
                                     CodeArtifactRenderer codeArtifactRenderer) {
        this.llmProvider = llmProvider;
        this.repository = repository;
        this.codeArtifactRenderer = codeArtifactRenderer;
    }

    /**
     * Step 1: "Generate Scenario". Produces scenarios only (endpoint/method/description),
     * no execution model. Each scenario is persisted with status=SCENARIO_GENERATED and
     * executionModel=null.
     */
    public List<ApiTestScript> generateScenarios(String projectId, GenerateRequest request) {
        int count = request.count() <= 0 ? 10 : request.count();
        var llmRequest = new ApiTestGenRequest(projectId, request.openApiSpec(), request.endpointFilters(), count);
        var generated = llmProvider.generateApiTestScenarios(llmRequest);

        List<ApiTestScript> toSave = generated.scenarios().stream()
                .map(scenario -> toEntity(projectId, scenario, request.openApiSpec()))
                .collect(Collectors.toList());

        return repository.saveAll(toSave);
    }

    /**
     * Step 2: "Generate Code". Only valid when a scenario exists and no execution model has
     * been generated for it yet — this refuses to silently overwrite an existing one. The
     * LLM only ever produces structured data (GeneratedApiExecutionModel); that data is
     * mapped into the domain ApiExecutionModel, run through ApiExecutionModelValidator, and
     * only then persisted. The "code" shown in the UI (renderedCode) is a deterministic,
     * never-executed rendering of that same model — see CodeArtifactRenderer.
     */
    public ApiTestScript generateCode(String scriptId) {
        ApiTestScript script = repository.findById(scriptId)
                .orElseThrow(() -> new ScenarioNotFoundException(scriptId));

        if (script.getExecutionModel() != null) {
            throw new ExecutionModelAlreadyGeneratedException(scriptId);
        }

        var llmRequest = new ApiExecutionModelGenRequest(
                script.getEndpoint(), script.getMethod(), script.getScenario(), script.getOpenApiSpecContext());
        GeneratedApiExecutionModel generated = llmProvider.generateApiExecutionModel(llmRequest);

        ApiExecutionModel model = toDomainModel(generated);
        ApiExecutionModelValidator.validate(scriptId, model);

        script.setExecutionModel(model);
        script.setRenderedCode(codeArtifactRenderer.renderApi(model));
        script.setStatus(ScriptStatus.CODE_GENERATED);
        return repository.save(script);
    }

    public List<ApiTestScript> listByProject(String projectId) {
        return repository.findByProjectId(projectId);
    }

    private ApiTestScript toEntity(String projectId, ApiScenario scenario, String openApiSpec) {
        ApiTestScript script = new ApiTestScript();
        script.setProjectId(projectId);
        script.setEndpoint(scenario.endpoint());
        script.setMethod(scenario.method());
        script.setScenario(scenario.scenario());
        script.setOpenApiSpecContext(openApiSpec);
        script.setExecutionModel(null);
        script.setRenderedCode(null);
        script.setStatus(ScriptStatus.SCENARIO_GENERATED);
        return script;
    }

    /** Maps the LLM's wire-level DTO (plain strings) onto the domain model (enums). Unknown
     *  or malformed method/assertion-type strings are handled leniently rather than
     *  crashing the whole generation — HttpMethod falls back to GET, and any assertion with
     *  an unrecognized type is dropped rather than failing the entire batch. */
    private ApiExecutionModel toDomainModel(GeneratedApiExecutionModel generated) {
        HttpMethod method = parseHttpMethod(generated.method());
        List<ApiAssertion> assertions = generated.assertions() == null ? List.of()
                : generated.assertions().stream()
                        .map(this::toDomainAssertion)
                        .filter(a -> a != null)
                        .collect(Collectors.toList());

        return new ApiExecutionModel(
                method,
                generated.endpoint(),
                generated.headers(),
                generated.queryParams(),
                generated.pathParams(),
                generated.cookies(),
                generated.requestBody(),
                generated.expectedStatus(),
                assertions);
    }

    private HttpMethod parseHttpMethod(String raw) {
        if (raw == null) return HttpMethod.GET;
        try {
            return HttpMethod.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return HttpMethod.GET;
        }
    }

    private ApiAssertion toDomainAssertion(GeneratedApiAssertion generated) {
        if (generated == null || generated.type() == null) return null;
        try {
            ApiAssertionType type = ApiAssertionType.valueOf(generated.type().trim().toUpperCase());
            return new ApiAssertion(type, generated.path(), generated.expectedValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
