package com.aitestplatform.apitest;

import com.aitestplatform.apitest.dto.ApiTestDtos.GenerateRequest;
import com.aitestplatform.llm.LlmProvider;
import com.aitestplatform.llm.dto.LlmDtos.ApiTestGenRequest;
import com.aitestplatform.llm.dto.LlmDtos.GeneratedApiTest;
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
     * Generates Rest Assured test scenarios from an OpenAPI spec. The spec is passed through
     * largely as-is to the LLM (a production version should chunk large specs per-endpoint to
     * stay within context limits — see README for the chunking note).
     */
    public List<ApiTestScript> generate(String projectId, GenerateRequest request) {
        var llmRequest = new ApiTestGenRequest(projectId, request.openApiSpec(), request.endpointFilters());
        var generated = llmProvider.generateApiTests(llmRequest);

        List<ApiTestScript> toSave = generated.tests().stream()
                .map(test -> {
                    var entity = this.toEntity(test);
                    entity.setProjectId(projectId);
                    return entity;
                }).toList();

        return repository.saveAll(toSave);
    }

    public List<ApiTestScript> listByProject(String projectId) {
        return repository.findByProjectId(projectId);
    }

    private ApiTestScript toEntity(GeneratedApiTest gt) {
        ApiTestScript script = new ApiTestScript();
        script.setEndpoint(gt.endpoint());
        script.setMethod(gt.method());
        script.setScenario(gt.scenario());
        script.setGeneratedCode(gt.generatedCode());
        script.setStatus(ScriptStatus.GENERATED);
        return script;
    }
}
