package com.aitestplatform.execution;

import com.aitestplatform.application.reporting.CodeArtifactRenderer;
import com.aitestplatform.domain.execution.ui.ScreenshotSpec;
import com.aitestplatform.domain.execution.ui.UiActionType;
import com.aitestplatform.domain.execution.ui.UiAssertion;
import com.aitestplatform.domain.execution.ui.UiAssertionType;
import com.aitestplatform.domain.execution.ui.UiExecutionModel;
import com.aitestplatform.domain.execution.ui.UiLocator;
import com.aitestplatform.domain.execution.ui.UiStep;
import com.aitestplatform.llm.LlmProvider;
import com.aitestplatform.llm.dto.LlmDtos.LocatorGenRequest;
import com.aitestplatform.llm.dto.LlmDtos.LocatorSuggestion;
import com.aitestplatform.testcase.TestCase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges an AI-generated, natural-language TestCase into a UiExecutionModel — structured
 * data, never Java source. Each step's target element is resolved via its own
 * LlmProvider.generateLocators() call (one LLM round-trip per step), then the results are
 * assembled into UiStep/UiLocator/UiAssertion domain objects that PlaywrightUiExecutor
 * interprets directly. The renderedCode field persisted alongside the model is a read-only
 * preview built by CodeArtifactRenderer — never compiled, never executed.
 *
 * One call per step, not one batched call for the whole test case: LocatorGenRequest / the
 * underlying prompt are designed around resolving ONE target element per call (they return
 * several *candidate* locators for that one element, most-robust-first) — there's no batch
 * variant that takes N descriptions and returns N per-step suggestions. Doing this per-step
 * costs more LLM calls per test case, but each call is small, and it avoids misreading
 * "alternative locators for one element" as "one locator per step".
 *
 * No live DOM snapshot is captured here (that would require driving a browser before the
 * run itself starts), so locator suggestions are based on the step text alone. This is a
 * best-effort MVP bridge, not a substitute for a real DOM-aware locator step.
 */
@Service
public class UiTestScriptGenerationService {

    private final LlmProvider llmProvider;
    private final UiTestScriptRepository uiTestScriptRepository;
    private final CodeArtifactRenderer codeArtifactRenderer;

    public UiTestScriptGenerationService(LlmProvider llmProvider,
                                          UiTestScriptRepository uiTestScriptRepository,
                                          CodeArtifactRenderer codeArtifactRenderer) {
        this.llmProvider = llmProvider;
        this.uiTestScriptRepository = uiTestScriptRepository;
        this.codeArtifactRenderer = codeArtifactRenderer;
    }

    public UiTestScript generate(TestCase testCase, String targetUrl) {
        List<TestCase.Step> steps = testCase.getSteps() == null ? List.of() : testCase.getSteps();

        List<UiLocator> locators = new ArrayList<>();
        List<UiStep> uiSteps = new ArrayList<>();
        List<UiAssertion> uiAssertions = new ArrayList<>();

        for (int i = 0; i < steps.size(); i++) {
            TestCase.Step step = steps.get(i);
            String targetDescription = step.action() + " (expected result: " + step.expected() + ")";
            String locatorRef = "step" + i;

            List<LocatorSuggestion> suggestions = llmProvider.generateLocators(
                    new LocatorGenRequest("", targetDescription));

            if (suggestions.isEmpty()) {
                // No locator suggestion returned — record it as an unresolved assertion
                // rather than silently dropping the step, so it's still visible in the
                // rendered preview and fails loudly (rather than passing silently) if run.
                locators.add(new UiLocator(locatorRef, "text=" + escape(step.action()), List.of(),
                        "no locator suggestion returned; needs manual authoring"));
                uiAssertions.add(new UiAssertion(locatorRef, UiAssertionType.VISIBLE, null));
                continue;
            }

            // Candidates come back most-robust-first; take the top one for this step.
            LocatorSuggestion suggestion = suggestions.get(0);
            locators.add(new UiLocator(locatorRef, suggestion.primaryLocator(),
                    suggestion.fallbackLocators(), suggestion.rationale()));

            classify(step, locatorRef, i, uiSteps, uiAssertions);
        }

        int lastStepIndex = uiSteps.isEmpty() ? -1 : uiSteps.get(uiSteps.size() - 1).index();
        List<ScreenshotSpec> screenshots = List.of(new ScreenshotSpec(lastStepIndex, "final"));

        UiExecutionModel model = new UiExecutionModel(targetUrl, uiSteps, locators, uiAssertions, screenshots);

        UiTestScript script = new UiTestScript();
        script.setTestCaseId(testCase.getId());
        script.setExecutionModel(model);
        script.setRenderedCode(codeArtifactRenderer.renderUi(model));
        return uiTestScriptRepository.save(script);
    }

    /**
     * Same keyword heuristic the old code-string generator used to decide the step's shape,
     * now routed to structured data instead of a line of Java: fill/type/enter/input steps
     * become a UiStep(FILL); verify/check/see/displayed/visible steps become a UiAssertion
     * (evaluated after all steps run, per the UiExecutionModel's top-level assertions list);
     * everything else defaults to a UiStep(CLICK).
     */
    private void classify(TestCase.Step step, String locatorRef, int index,
                           List<UiStep> uiSteps, List<UiAssertion> uiAssertions) {
        String action = step.action() == null ? "" : step.action().toLowerCase();

        if (action.contains("type") || action.contains("enter") || action.contains("fill") || action.contains("input")) {
            uiSteps.add(new UiStep(index, UiActionType.FILL, locatorRef, "test value", step.action()));
            return;
        }
        if (action.contains("verify") || action.contains("check") || action.contains("see")
                || action.contains("displayed") || action.contains("visible")) {
            uiAssertions.add(new UiAssertion(locatorRef, UiAssertionType.VISIBLE, null));
            return;
        }
        // default: click/select/press and anything else that's an interaction, not an assertion
        uiSteps.add(new UiStep(index, UiActionType.CLICK, locatorRef, null, step.action()));
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
