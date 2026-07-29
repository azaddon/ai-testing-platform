package com.aitestplatform.execution;

import com.aitestplatform.llm.LlmProvider;
import com.aitestplatform.llm.dto.LlmDtos.LocatorGenRequest;
import com.aitestplatform.llm.dto.LlmDtos.LocatorSuggestion;
import com.aitestplatform.testcase.TestCase;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bridges an AI-generated, natural-language TestCase into an executable UiTestScript.
 * This is the piece referenced but left unbuilt in the original UiTestScript scaffolding:
 * each step's target element is resolved via its own LlmProvider.generateLocators() call
 * (one LLM round-trip per step), then the results are assembled into a Playwright method
 * body that PlaywrightExecutionService can compile and run.
 *
 * One call per step, not one batched call for the whole test case: LocatorGenRequest /
 * the underlying prompt are designed around resolving ONE target element per call (they
 * return several *candidate* locators for that one element, most-robust-first) — there's
 * no batch variant that takes N descriptions and returns N per-step suggestions. Doing
 * this per-step costs more LLM calls per test case, but each call is small, and it avoids
 * misreading "alternative locators for one element" as "one locator per step".
 *
 * No live DOM snapshot is captured here (that would require driving a browser before the
 * run itself starts), so locator suggestions are based on the step text alone. This is a
 * best-effort MVP bridge, not a substitute for a real DOM-aware locator step.
 */
@Service
public class UiTestScriptGenerationService {

    private final LlmProvider llmProvider;
    private final UiTestScriptRepository uiTestScriptRepository;

    public UiTestScriptGenerationService(LlmProvider llmProvider, UiTestScriptRepository uiTestScriptRepository) {
        this.llmProvider = llmProvider;
        this.uiTestScriptRepository = uiTestScriptRepository;
    }

    public UiTestScript generate(TestCase testCase, String targetUrl) {
        List<TestCase.Step> steps = testCase.getSteps() == null ? List.of() : testCase.getSteps();

        StringBuilder code = new StringBuilder();

        for (int i = 0; i < steps.size(); i++) {
            TestCase.Step step = steps.get(i);
            String targetDescription = step.action() + " (expected result: " + step.expected() + ")";

            List<LocatorSuggestion> suggestions = llmProvider.generateLocators(
                    new LocatorGenRequest("", targetDescription));

            if (suggestions.isEmpty()) {
                code.append("// Step ").append(i + 1).append(": ").append(step.action())
                        .append(" — no locator suggestion returned, needs manual authoring\n\n");
                continue;
            }

            // Candidates come back most-robust-first; take the top one for this step.
            LocatorSuggestion suggestion = suggestions.get(0);

            code.append("// Step ").append(i + 1).append(": ").append(step.action()).append("\n");
            code.append(buildStepStatement(suggestion, step)).append("\n\n");
        }

        UiTestScript script = new UiTestScript();
        script.setTestCaseId(testCase.getId());
        script.setTargetUrl(targetUrl);
        script.setGeneratedCode(code.toString());
        return uiTestScriptRepository.save(script);
    }

    private String buildStepStatement(LocatorSuggestion suggestion, TestCase.Step step) {
        String locator = suggestion.primaryLocator();
        String action = step.action() == null ? "" : step.action().toLowerCase();

        if (action.contains("type") || action.contains("enter") || action.contains("fill") || action.contains("input")) {
            return locator + ".fill(\"test value\");";
        }
        if (action.contains("verify") || action.contains("check") || action.contains("see")
                || action.contains("displayed") || action.contains("visible")) {
            return "if (!" + locator + ".isVisible()) throw new Exception(\"Expected visible: "
                    + escape(step.expected()) + "\");";
        }
        // default: click/select/press and anything else that's an interaction, not an assertion
        return locator + ".click();";
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
