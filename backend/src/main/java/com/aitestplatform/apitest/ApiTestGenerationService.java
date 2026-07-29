package com.aitestplatform.apitest;

import com.aitestplatform.apitest.dto.ApiTestDtos.GenerateRequest;
import com.aitestplatform.common.ApiTestWorkflowException.CodeAlreadyGeneratedException;
import com.aitestplatform.common.ApiTestWorkflowException.ScenarioNotFoundException;
import com.aitestplatform.llm.LlmProvider;
import com.aitestplatform.llm.dto.LlmDtos.ApiScenario;
import com.aitestplatform.llm.dto.LlmDtos.ApiTestCodeGenRequest;
import com.aitestplatform.llm.dto.LlmDtos.ApiTestGenRequest;
import com.aitestplatform.llm.dto.LlmDtos.GeneratedApiTestCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ApiTestGenerationService {

    private final LlmProvider llmProvider;
    private final ApiTestScriptRepository repository;

    public ApiTestGenerationService(LlmProvider llmProvider, ApiTestScriptRepository repository) {
        this.llmProvider = llmProvider;
        this.repository = repository;
    }

    /**
     * Step 1: "Generate Scenario". Produces scenarios only (endpoint/method/description),
     * no Rest Assured code. Each scenario is persisted with status=SCENARIO_GENERATED and
     * generatedCode=null.
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
     * Step 2: "Generate Code". Only valid when a scenario exists and no code has been
     * generated for it yet — this refuses to silently overwrite existing generated code.
     * The LLM's output is run through GeneratedCodeValidator before it's ever persisted;
     * unsafe or empty code never reaches Mongo or the "Run" step.
     */
    public ApiTestScript generateCode(String scriptId) {
        ApiTestScript script = repository.findById(scriptId)
                .orElseThrow(() -> new ScenarioNotFoundException(scriptId));

        if (script.getGeneratedCode() != null && !script.getGeneratedCode().isBlank()) {
            throw new CodeAlreadyGeneratedException(scriptId);
        }

        var llmRequest = new ApiTestCodeGenRequest(
                script.getEndpoint(), script.getMethod(), script.getScenario(), script.getOpenApiSpecContext());
        GeneratedApiTestCode generated = llmProvider.generateApiTestCode(llmRequest);

        GeneratedCodeValidator.validateGeneratedCode(scriptId, generated.generatedCode());

        script.setGeneratedCode(generated.generatedCode());
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
        script.setGeneratedCode(null);
        script.setStatus(ScriptStatus.SCENARIO_GENERATED);
        return script;
    }
}
