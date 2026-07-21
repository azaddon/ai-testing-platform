package com.aitestplatform.testcase;

import com.aitestplatform.llm.LlmProvider;
import com.aitestplatform.llm.dto.LlmDtos.GeneratedTestCase;
import com.aitestplatform.llm.dto.LlmDtos.TestCaseGenRequest;
import com.aitestplatform.testcase.dto.TestCaseDtos.GenerateRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TestCaseGenerationService {

    private final LlmProvider llmProvider;
    private final TestCaseRepository testCaseRepository;

    public TestCaseGenerationService(LlmProvider llmProvider, TestCaseRepository testCaseRepository) {
        this.llmProvider = llmProvider;
        this.testCaseRepository = testCaseRepository;
    }

    /** Generates test cases via the active LlmProvider and persists them as drafts. */
    public List<TestCase> generate(String projectId, GenerateRequest request) {
        var llmRequest = new TestCaseGenRequest(
                projectId,
                request.requirementText(),
                request.testTypes() == null || request.testTypes().isEmpty()
                        ? List.of("functional", "edge", "negative") : request.testTypes(),
                request.count() <= 0 ? 5 : request.count()
        );

        var generated = llmProvider.generateTestCases(llmRequest);

        List<TestCase> toSave = generated.testCases().stream()
                .map(gc -> toEntity(projectId, gc, generated.modelUsed()))
                .collect(Collectors.toList());

        return testCaseRepository.saveAll(toSave);
    }

    public List<TestCase> listByProject(String projectId, String status) {
        return status == null
                ? testCaseRepository.findByProjectId(projectId)
                : testCaseRepository.findByProjectIdAndStatus(projectId, status);
    }

    public TestCase getById(String testCaseId) {
        return testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new IllegalArgumentException("Test case not found: " + testCaseId));
    }

    public TestCase approve(String testCaseId) {
        TestCase tc = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new IllegalArgumentException("Test case not found: " + testCaseId));
        tc.setStatus("approved");
        return testCaseRepository.save(tc);
    }

    private TestCase toEntity(String projectId, GeneratedTestCase gc, String model) {
        TestCase tc = new TestCase();
        tc.setProjectId(projectId);
        tc.setTitle(gc.title());
        tc.setDescription(gc.description());
        tc.setPreconditions(gc.preconditions());
        tc.setSteps(gc.steps() == null ? List.of() : gc.steps().stream()
                .map(s -> new TestCase.Step(s.action(), s.expected()))
                .collect(Collectors.toList()));
        tc.setType(gc.type());
        tc.setPriority(gc.priority());
        tc.setSource("ai-generated");
        tc.setGeneratedBy(new TestCase.GeneratedBy(llmProvider.name(), model));
        tc.setStatus("draft");
        return tc;
    }
}
