package com.aitestplatform.execution;

import com.aitestplatform.application.reporting.CodeArtifactRenderer;
import com.aitestplatform.domain.execution.ui.ScreenshotSpec;
import com.aitestplatform.domain.execution.ui.UiActionType;
import com.aitestplatform.domain.execution.ui.UiAssertion;
import com.aitestplatform.domain.execution.ui.UiAssertionType;
import com.aitestplatform.domain.execution.ui.UiExecutionModel;
import com.aitestplatform.domain.execution.ui.UiLocator;
import com.aitestplatform.domain.execution.ui.UiStep;
import com.aitestplatform.infrastructure.execution.playwright.PlaywrightDomSnapshotCapturer;
import com.aitestplatform.llm.LlmProvider;
import com.aitestplatform.llm.dto.LlmDtos.LocatorGenRequest;
import com.aitestplatform.llm.dto.LlmDtos.LocatorSuggestion;
import com.aitestplatform.testcase.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * A real, live DOM snapshot IS captured now, via PlaywrightDomSnapshotCapturer — once per
 * test case, before the per-step loop, not once per step. It's reused across every
 * generateLocators() call below rather than re-captured per step: the page's static content
 * doesn't change between those calls, so re-navigating N times would just multiply LLM token
 * cost for zero benefit. If the target page can't be reached in time, capture() degrades to ""
 * and locator generation falls back to the previous text-only guessing rather than failing
 * the whole test-case generation.
 */
@Service
public class UiTestScriptGenerationService {

    private static final Logger log = LoggerFactory.getLogger(UiTestScriptGenerationService.class);

    private final LlmProvider llmProvider;
    private final UiTestScriptRepository uiTestScriptRepository;
    private final CodeArtifactRenderer codeArtifactRenderer;
    private final PlaywrightDomSnapshotCapturer domSnapshotCapturer;

    public UiTestScriptGenerationService(LlmProvider llmProvider,
                                          UiTestScriptRepository uiTestScriptRepository,
                                          CodeArtifactRenderer codeArtifactRenderer,
                                          PlaywrightDomSnapshotCapturer domSnapshotCapturer) {
        this.llmProvider = llmProvider;
        this.uiTestScriptRepository = uiTestScriptRepository;
        this.codeArtifactRenderer = codeArtifactRenderer;
        this.domSnapshotCapturer = domSnapshotCapturer;
    }

    public UiTestScript generate(TestCase testCase, String targetUrl) {
        List<TestCase.Step> steps = testCase.getSteps() == null ? List.of() : testCase.getSteps();

        String domSnapshot = domSnapshotCapturer.capture(targetUrl);

        List<UiLocator> locators = new ArrayList<>();
        List<UiStep> uiSteps = new ArrayList<>();
        List<UiAssertion> uiAssertions = new ArrayList<>();

        for (int i = 0; i < steps.size(); i++) {
            TestCase.Step step = steps.get(i);
            String targetDescription = step.action() + " (expected result: " + step.expected() + ")";
            String locatorRef = "step" + i;

            List<LocatorSuggestion> suggestions = llmProvider.generateLocators(
                    new LocatorGenRequest(domSnapshot, targetDescription));

            if (suggestions.isEmpty()) {
                // No locator suggestion returned — record it as an unresolved assertion
                // rather than silently dropping the step, so it's still visible in the
                // rendered preview and fails loudly (rather than passing silently) if run.
                locators.add(new UiLocator(locatorRef, "text=" + escape(step.action()), List.of(),
                        "no locator suggestion returned; needs manual authoring"));
                uiAssertions.add(new UiAssertion(locatorRef, UiAssertionType.VISIBLE, null));
                continue;
            }

            // Candidates come back most-robust-first; take the top one for this step, but don't
            // trust it blindly — verify it against the captured snapshot first (see
            // validateAgainstSnapshot's javadoc for why this is necessary, not optional).
            LocatorSuggestion suggestion = validateAgainstSnapshot(suggestions.get(0), domSnapshot, step);
            locators.add(new UiLocator(locatorRef, suggestion.primaryLocator(),
                    suggestion.fallbackLocators(), suggestion.rationale()));

            classify(step, locatorRef, i, suggestion.primaryLocator(), uiSteps, uiAssertions);
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
     * now routed to structured data instead of a line of Java, PLUS a check against the
     * resolved primaryLocator's role — not text alone.
     *
     * Text-only classification breaks whenever a test-case step's wording mixes verbs, e.g.
     * "Enter password and click login" contains "enter", so the old code always emitted FILL
     * for it — regardless of which element generateLocators() actually resolved that step to.
     * Seen live: that exact step's locator correctly resolved to the real Login button
     * (role=button[name="Login"]), and Playwright's fill() rejected it outright ("Input of
     * type \"submit\" cannot be filled"). The locator was right; only the action verb was
     * wrong. So the resolved locator string is now the deciding signal whenever it disagrees
     * with the text: a locator that's unambiguously a button/link/checkbox/submit-input can
     * never be FILLed, no matter what verb the step text used.
     */
    private void classify(TestCase.Step step, String locatorRef, int index, String primaryLocator,
                           List<UiStep> uiSteps, List<UiAssertion> uiAssertions) {
        String action = step.action() == null ? "" : step.action().toLowerCase();
        String locator = primaryLocator == null ? "" : primaryLocator.toLowerCase();

        if (action.contains("verify") || action.contains("check") || action.contains("see")
                || action.contains("displayed") || action.contains("visible")) {
            uiAssertions.add(new UiAssertion(locatorRef, UiAssertionType.VISIBLE, null));
            return;
        }

        boolean locatorIsCheckbox = locator.contains("role=checkbox") || locator.contains("type=\"checkbox\"")
                || locator.contains("type='checkbox'");
        boolean locatorIsNonFillable = locatorIsCheckbox
                || locator.contains("role=button") || locator.contains("role=link")
                || locator.contains("role=radio") || locator.contains("type=\"submit\"")
                || locator.contains("type='submit'") || locator.contains("type=\"button\"")
                || locator.contains("type='button'");

        boolean looksLikeFillText = action.contains("type") || action.contains("enter")
                || action.contains("fill") || action.contains("input");

        if (looksLikeFillText && !locatorIsNonFillable) {
            uiSteps.add(new UiStep(index, UiActionType.FILL, locatorRef, "test value", step.action()));
            return;
        }
        if (locatorIsCheckbox) {
            uiSteps.add(new UiStep(index, UiActionType.CHECK, locatorRef, null, step.action()));
            return;
        }
        // default: click/select/press and anything else that's an interaction, not an assertion
        // — also where a fill-sounding step text got overridden because its locator turned out
        // to be a button/link/submit input.
        uiSteps.add(new UiStep(index, UiActionType.CLICK, locatorRef, null, step.action()));
    }

    /**
     * A test-id bracket locator ([data-testid="..."], [data-test="..."], etc.) is an exact
     * factual claim — "an element with this literal attribute value exists on the page" — not
     * a fuzzy guess like role=/text=/css=. The prompt tells the LLM to only emit one when the
     * snapshot actually shows it, but a small/fast model (llama-3.1-8b-instant here) doesn't
     * always follow that: seen live against the-internet.herokuapp.com, it invented
     * [data-test="A/B Testing"] — reusing the link's visible TEXT as a fabricated attribute
     * value — even though that page has no data-test attributes at all. That locator parses
     * fine (no exception, no fallback triggered) and just times out for the full navigation
     * timeout waiting for an element that can never exist.
     *
     * Rather than hope prompt wording alone prevents every case of this, this checks each
     * candidate locator against the literal captured snapshot text: a bracket test-id locator
     * only survives if that exact substring is actually present in domSnapshot. Anything else
     * (role=, text=, css=, xpath=) isn't checked this way since those aren't verifiable exact
     * claims. If every candidate for a step turns out to be fabricated, this falls back to the
     * same text= locator used when the LLM returns no suggestions at all — worse precision, but
     * it fails fast/obviously instead of hanging for a full timeout on a guaranteed dead end.
     */
    private LocatorSuggestion validateAgainstSnapshot(LocatorSuggestion suggestion, String domSnapshot,
                                                        TestCase.Step step) {
        List<String> candidates = new ArrayList<>();
        if (suggestion.primaryLocator() != null) {
            candidates.add(suggestion.primaryLocator());
        }
        if (suggestion.fallbackLocators() != null) {
            candidates.addAll(suggestion.fallbackLocators());
        }

        List<String> grounded = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String candidate : candidates) {
            if (isFabricatedTestIdLocator(candidate, domSnapshot)) {
                rejected.add(candidate);
            } else {
                grounded.add(candidate);
            }
        }

        if (grounded.isEmpty()) {
            String fallbackLocator = "text=" + escape(step.action());
            log.warn("Every locator candidate for step '{}' referenced a test-id attribute "
                    + "value not found in the captured DOM snapshot (fabricated): {} — falling "
                    + "back to '{}'", step.action(), rejected, fallbackLocator);
            return new LocatorSuggestion(fallbackLocator, List.of(),
                    "fallback: the LLM's suggested locator(s) referenced a data-test* value not "
                            + "present in the live DOM snapshot, so they were discarded");
        }

        String rationale = suggestion.rationale();
        if (!rejected.isEmpty()) {
            log.warn("Discarded {} fabricated test-id locator candidate(s) for step '{}': {}",
                    rejected.size(), step.action(), rejected);
            rationale = (rationale == null ? "" : rationale + " ")
                    + "(discarded " + rejected.size() + " unverified test-id candidate(s))";
        }
        return new LocatorSuggestion(grounded.get(0), grounded.subList(1, grounded.size()), rationale);
    }

    private boolean isFabricatedTestIdLocator(String locator, String domSnapshot) {
        if (locator == null) {
            return false;
        }
        String trimmed = locator.trim();
        boolean isTestIdBracket = trimmed.startsWith("[data-testid=") || trimmed.startsWith("[data-test=")
                || trimmed.startsWith("[data-qa=") || trimmed.startsWith("[data-cy=");
        if (!isTestIdBracket) {
            return false; // role=/text=/css=/xpath= aren't exact-value claims checkable this way
        }
        return domSnapshot == null || domSnapshot.isBlank() || !domSnapshot.contains(trimmed);
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
