package com.aitestplatform.infrastructure.execution.playwright;

import com.aitestplatform.application.execution.TestExecutor;
import com.aitestplatform.domain.execution.AssertionOutcome;
import com.aitestplatform.domain.execution.ui.*;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Interprets a UiExecutionModel directly through Playwright's Java API — no source
 * generation, no javac, no reflection. Locators are Playwright selector-engine strings
 * (e.g. "role=button[name='Submit']"), which page.locator(String) parses natively; that's
 * what lets locator resolution stay pure data instead of a code snippet. Each UiStep is
 * dispatched by its UiActionType to the matching Locator method — the LLM never gets to
 * hand us anything more executable than "click this ref" or "fill this ref with this value".
 */
@Component
public class PlaywrightUiExecutor implements TestExecutor<UiExecutionModel, UiExecutionResult> {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightUiExecutor.class);

    private final int navigationTimeoutMs;
    private final int timeoutSeconds;

    public PlaywrightUiExecutor(@Value("${execution.playwright.navigation-timeout-ms:30000}") int navigationTimeoutMs,
                                 @Value("${execution.sandbox.timeout-seconds:60}") int timeoutSeconds) {
        this.navigationTimeoutMs = navigationTimeoutMs;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public UiExecutionResult execute(UiExecutionModel model) {
        log.info("UI execution starting: url='{}', steps={}, assertions={}, sandboxTimeoutSeconds={}",
                model.url(), model.steps() == null ? 0 : model.steps().size(),
                model.assertions() == null ? 0 : model.assertions().size(), timeoutSeconds);

        // Playwright's sync API is thread-affined: Playwright.create() through
        // browser.close() must all run on the same thread that opened the driver
        // connection. Running the whole thing as one task on a dedicated thread lets us
        // also enforce execution.sandbox.timeout-seconds as a hard ceiling on the entire
        // run (navigation + every step + every assertion) — independent of Playwright's
        // own per-action timeouts — so a run that gets stuck can't tie up this @Async
        // worker thread (see TestRunExecutionWorker) forever.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<UiExecutionResult> future = executor.submit(runTask(model));
            UiExecutionResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("UI execution finished: url='{}', passed={}, durationMs={}",
                    model.url(), result.passed(), result.durationMs());
            return result;
        } catch (TimeoutException e) {
            log.error("UI execution exceeded the {}s sandbox timeout: url='{}'", timeoutSeconds, model.url());
            return new UiExecutionResult(false, timeoutSeconds * 1000L, List.of(), Map.of(),
                    "UI run exceeded the " + timeoutSeconds + "s execution timeout");
        } catch (Exception e) {
            log.error("UI execution threw before producing a result: url='{}'", model.url(), e);
            return new UiExecutionResult(false, 0, List.of(), Map.of(),
                    "Execution error: " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<UiExecutionResult> runTask(UiExecutionModel model) {
        return () -> {
            long start = System.currentTimeMillis();
            Map<String, String> screenshots = new HashMap<>();
            List<AssertionOutcome> outcomes = new ArrayList<>();

            try (Playwright playwright = Playwright.create()) {
                log.debug("Launching headless Chromium");
                // --disable-dev-shm-usage: Docker's default /dev/shm is only 64MB, which
                // Chromium uses for rendering/compositing shared memory. Too small and
                // Chromium doesn't crash outright — it just never produces stable painted
                // frames, so every locator.click()'s actionability wait (visible + stable +
                // receives-events) sits at its own timeout forever regardless of which
                // element is targeted. This flag makes Chromium use /tmp instead.
                // --no-sandbox: Chromium's OS-level sandbox needs kernel namespace
                // permissions this container isn't granted (no --cap-add=SYS_ADMIN /
                // seccomp=unconfined in docker-compose.yml); without this flag, sandbox
                // initialization can silently misbehave in exactly the same way. Both are
                // the standard pairing for running Chromium headless inside Docker/CI.
                // --disable-http2: some real sites' WAF/anti-bot layer resets or mangles HTTP/2
                // frames for automated/headless traffic (seen live against makemytrip.com —
                // net::ERR_HTTP2_PROTOCOL_ERROR on the very first navigate, before any content
                // loads). Forcing HTTP/1.1 sidesteps that class of failure entirely.
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of("--disable-dev-shm-usage", "--no-sandbox", "--disable-http2")));
                try {
                    Page page = browser.newPage();

                    long navStart = System.currentTimeMillis();
                    log.info("Navigating to '{}' (waitUntil=COMMIT, timeout={}ms)", model.url(), navigationTimeoutMs);
                    page.navigate(model.url(), new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.COMMIT)
                            .setTimeout(navigationTimeoutMs));
                    log.info("Navigation to '{}' returned after {}ms (COMMIT only — page may still be loading)",
                            model.url(), System.currentTimeMillis() - navStart);
                    logPostNavigateDiagnostic(page, model.url());

                    Map<String, UiLocator> locatorsByRef = model.locators() == null ? Map.of()
                            : model.locators().stream().collect(java.util.stream.Collectors.toMap(UiLocator::ref, l -> l));

                    captureIfScheduled(model, -1, page, screenshots);

                    for (UiStep step : model.steps() == null ? List.<UiStep>of() : model.steps()) {
                        UiLocator resolved = locatorsByRef.get(step.locatorRef());
                        long stepStart = System.currentTimeMillis();
                        log.info("Step {}: action={} locatorRef={} selector='{}'", step.index(), step.action(),
                                step.locatorRef(), resolved == null ? "<missing>" : resolved.primaryLocator());
                        runStep(page, step, locatorsByRef);
                        log.info("Step {} completed in {}ms", step.index(), System.currentTimeMillis() - stepStart);
                        captureIfScheduled(model, step.index(), page, screenshots);
                    }

                    for (UiAssertion assertion : model.assertions() == null ? List.<UiAssertion>of() : model.assertions()) {
                        outcomes.add(evaluateAssertion(page, assertion, locatorsByRef));
                    }

                    boolean passed = outcomes.stream().allMatch(AssertionOutcome::passed);
                    long duration = System.currentTimeMillis() - start;
                    return new UiExecutionResult(passed, duration, outcomes, screenshots, null);

                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - start;
                    // Full stack trace here (not just e.getMessage(), which is all the stored
                    // UiExecutionResult carries) — this is the detail that was previously lost:
                    // this exception never propagates past this catch, so without logging it
                    // here it never reaches anywhere printStackTrace() would have caught it.
                    log.error("UI step/assertion failed after {}ms: url='{}'", duration, model.url(), e);
                    return new UiExecutionResult(false, duration, outcomes, screenshots, friendlyMessage(e, model.url()));
                } finally {
                    browser.close();
                }
            }
        };
    }

    /**
     * Diagnostic only — logs what actually rendered right after navigate, before any step
     * touches the page. Exists to answer one question when a click later hangs waiting for
     * a locator that should genuinely be there: did the browser actually land on the real
     * page, or on something else entirely (a bot-check/JS-challenge interstitial, a login
     * wall, a "just a moment..." page, etc.)? Those all still return HTTP 200 and satisfy
     * waitUntil=COMMIT, so navigate() itself never reveals the mismatch — only looking at
     * the actual title/HTML does. Best-effort: never allowed to affect the real run.
     */
    private void logPostNavigateDiagnostic(Page page, String url) {
        try {
            String title = page.title();
            String html = page.content();
            if (html != null && html.length() > 800) {
                html = html.substring(0, 800) + "... (truncated)";
            }
            log.info("Post-navigate check for '{}': title='{}', html-snippet={}", url, title, html);
        } catch (Exception e) {
            log.warn("Post-navigate diagnostic check itself failed for '{}'", url, e);
        }
    }

    /**
     * Playwright's raw navigation-timeout exception is a wall of internal stack frames
     * that doesn't tell a user anything actionable. If it looks like a "couldn't reach
     * the target URL" failure, replace it with a plain-language message instead — the
     * full original text is still appended for anyone who needs to dig deeper.
     */
    private String friendlyMessage(Exception e, String targetUrl) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        if (raw.contains("Timeout") && raw.contains("navigating to")) {
            return "Could not reach " + targetUrl + " within the timeout. Check that the app under "
                    + "test is actually running and reachable from inside the API container — "
                    + "e.g. from Docker, use the compose service name (http://frontend:80) or "
                    + "http://host.docker.internal:<port> only if something is actually listening "
                    + "on that port on the host. Raw error: " + raw;
        }
        return raw;
    }

    private void runStep(Page page, UiStep step, Map<String, UiLocator> locatorsByRef) {
        Locator locator = resolveLocator(page, step.locatorRef(), locatorsByRef);
        switch (step.action()) {
            case CLICK -> locator.click();
            case FILL -> fillWithClickFallback(locator, step.value());
            case HOVER -> locator.hover();
            case SELECT_OPTION -> locator.selectOption(step.value());
            case PRESS_KEY -> locator.press(step.value());
            case CHECK -> locator.check();
            case UNCHECK -> locator.uncheck();
        }
    }

    /**
     * Self-healing safety net for the FILL/CLICK mismatch class: UiTestScriptGenerationService's
     * classify() now checks the resolved locator's role before emitting FILL, but that's a
     * generation-time, string-based check — it can't see what the element actually is at
     * runtime. This is the runtime backstop: if fill() is attempted on something that turns out
     * not to be fillable (a button, a submit input, a link — exactly what broke saucedemo's
     * login step: "Input of type \"submit\" cannot be filled"), fall back to click() instead of
     * failing the whole run outright. Only triggers on that specific, unambiguous error
     * signature — anything else (a real timeout, a missing element) still fails normally.
     */
    private void fillWithClickFallback(Locator locator, String value) {
        try {
            locator.fill(value);
        } catch (PlaywrightException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("cannot be filled")) {
                log.warn("fill() not valid for this element, falling back to click(): {}", msg);
                locator.click();
                return;
            }
            throw e;
        }
    }

    private AssertionOutcome evaluateAssertion(Page page, UiAssertion assertion, Map<String, UiLocator> locatorsByRef) {
        try {
            Locator locator = resolveLocator(page, assertion.locatorRef(), locatorsByRef);
            return switch (assertion.type()) {
                case VISIBLE -> new AssertionOutcome(assertion.locatorRef() + " is visible",
                        locator.isVisible(), String.valueOf(locator.isVisible()));
                case HIDDEN -> new AssertionOutcome(assertion.locatorRef() + " is hidden",
                        locator.isHidden(), String.valueOf(locator.isHidden()));
                case ENABLED -> new AssertionOutcome(assertion.locatorRef() + " is enabled",
                        locator.isEnabled(), String.valueOf(locator.isEnabled()));
                case CHECKED -> new AssertionOutcome(assertion.locatorRef() + " is checked",
                        locator.isChecked(), String.valueOf(locator.isChecked()));
                case TEXT_EQUALS -> {
                    String actual = locator.textContent();
                    boolean passed = actual != null && actual.trim().equals(assertion.expectedValue());
                    yield new AssertionOutcome(assertion.locatorRef() + " text equals " + assertion.expectedValue(),
                            passed, actual);
                }
                case TEXT_CONTAINS -> {
                    String actual = locator.textContent();
                    boolean passed = actual != null && actual.contains(assertion.expectedValue());
                    yield new AssertionOutcome(
                            assertion.locatorRef() + " text contains " + assertion.expectedValue(), passed, actual);
                }
            };
        } catch (Exception e) {
            return new AssertionOutcome(assertion.type() + " on " + assertion.locatorRef(), false,
                    "error: " + e.getMessage());
        }
    }

    private Locator resolveLocator(Page page, String ref, Map<String, UiLocator> locatorsByRef) {
        UiLocator uiLocator = locatorsByRef.get(ref);
        if (uiLocator == null) {
            throw new IllegalStateException("No locator found for ref '" + ref + "'");
        }
        try {
            return page.locator(uiLocator.primaryLocator());
        } catch (Exception primaryFailed) {
            for (String fallback : uiLocator.fallbackLocators() == null ? List.<String>of() : uiLocator.fallbackLocators()) {
                try {
                    return page.locator(fallback);
                } catch (Exception ignored) {
                    // try the next fallback
                }
            }
            throw primaryFailed instanceof RuntimeException re ? re : new IllegalStateException(primaryFailed);
        }
    }

    private void captureIfScheduled(UiExecutionModel model, int afterStepIndex, Page page, Map<String, String> screenshots) {
        for (ScreenshotSpec spec : model.screenshots() == null ? List.<ScreenshotSpec>of() : model.screenshots()) {
            if (spec.afterStepIndex() == afterStepIndex) {
                try {
                    byte[] bytes = page.screenshot();
                    screenshots.put(spec.label(), Base64.getEncoder().encodeToString(bytes));
                } catch (Exception ignored) {
                    // best-effort capture; a missing screenshot shouldn't fail the run
                }
            }
        }
    }
}
