package com.aitestplatform.infrastructure.execution.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The real fix for locator generation: navigates to the actual target URL and extracts a
 * compact, LLM-friendly listing of its interactive elements — role, accessible name, and
 * data-testid — instead of the "" that UiTestScriptGenerationService used to pass into every
 * LlmProvider.generateLocators() call. Without this, the LLM could only guess a locator from
 * training memory, which can be flat-out wrong for any real app, and was proven stale even for
 * a page as simple as example.com during this project's own debugging (the LLM suggested
 * "More information..." for a link whose real, current text is "Learn more").
 *
 * Deliberately NOT using Playwright's Accessibility.snapshot() API — a small page.evaluate()
 * script instead, so the shape returned is exactly the "role \"name\"" / "[data-testid=...]"
 * format locator-generation.txt's role=...[name='...'] guidance already expects, in one pass,
 * with a hard cap on element count and output size so a large real-world page can't blow up
 * the prompt this gets embedded into.
 */
@Component
public class PlaywrightDomSnapshotCapturer {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightDomSnapshotCapturer.class);
    private static final int MAX_SNAPSHOT_CHARS = 6000;
    private static final int MAX_ELEMENTS = 200;

    private static final String EXTRACT_SCRIPT = """
            () => {
              const nodes = document.querySelectorAll(
                'a, button, input, select, textarea, [role], h1, h2, h3, ' +
                '[data-testid], [data-test], [data-qa], [data-cy], [aria-label]'
              );
              // Playwright's role=... selector engine matches ARIA roles (textbox, button,
              // link, checkbox, heading, ...) — never raw HTML tag names. Falling back to
              // el.tagName.toLowerCase() (e.g. "input") produces a role string ("role=input...")
              // that can never match anything, since "input" isn't a real ARIA role: this is
              // exactly what caused role=input[name='Username'] to time out against saucedemo.
              // This maps each element to its correct IMPLICIT role instead, same as a browser's
              // own accessibility tree would.
              const implicitRole = (el) => {
                const tag = el.tagName.toLowerCase();
                if (tag === 'a') return el.hasAttribute('href') ? 'link' : 'generic';
                if (tag === 'button') return 'button';
                if (tag === 'select') return 'combobox';
                if (tag === 'textarea') return 'textbox';
                if (tag === 'input') {
                  const type = (el.getAttribute('type') || 'text').toLowerCase();
                  if (type === 'submit' || type === 'button' || type === 'reset') return 'button';
                  if (type === 'checkbox') return 'checkbox';
                  if (type === 'radio') return 'radio';
                  return 'textbox';
                }
                if (/^h[1-6]$/.test(tag)) return 'heading';
                return tag;
              };
              // Test-id attribute conventions vary by app (data-testid, data-test, data-qa,
              // data-cy are all common) — the exact attribute name used is preserved in the
              // output so a generated [data-test="..."] locator matches the real attribute
              // instead of guessing "data-testid" for every site.
              const testIdAttrs = ['data-testid', 'data-test', 'data-qa', 'data-cy'];
              const lines = [];
              for (const el of nodes) {
                if (lines.length >= %d) break;
                const role = el.getAttribute('role') || implicitRole(el);
                const rawName = el.getAttribute('aria-label') || el.innerText || el.getAttribute('placeholder')
                    || el.getAttribute('value') || '';
                const name = rawName.trim().replace(/\\s+/g, ' ').slice(0, 80);
                let testIdPart = '';
                for (const attr of testIdAttrs) {
                  const val = el.getAttribute(attr);
                  if (val) { testIdPart = '[' + attr + '="' + val + '"]'; break; }
                }
                if (!name && !testIdPart) continue;
                let line = role;
                if (name) line += ' "' + name + '"';
                if (testIdPart) line += ' ' + testIdPart;
                lines.push(line);
              }
              return lines.join('\\n');
            }
            """.formatted(MAX_ELEMENTS);

    private final int navigationTimeoutMs;
    private final int captureTimeoutSeconds;

    public PlaywrightDomSnapshotCapturer(
            @Value("${execution.playwright.navigation-timeout-ms:30000}") int navigationTimeoutMs,
            @Value("${execution.playwright.snapshot-timeout-seconds:25}") int captureTimeoutSeconds) {
        this.navigationTimeoutMs = navigationTimeoutMs;
        this.captureTimeoutSeconds = captureTimeoutSeconds;
    }

    /**
     * Best-effort: an unreachable URL, a navigation timeout, or any other capture failure logs
     * a warning and returns "" rather than throwing — a DOM-capture problem should degrade
     * locator generation back to today's text-only guessing, not block test-case generation.
     */
    public String capture(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(captureTask(url));
            return future.get(captureTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("DOM snapshot capture failed for '{}' — locator generation falls back to "
                    + "text-only guessing for this run", url, e);
            return "";
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<String> captureTask(String url) {
        return () -> {
            // Same thread-affinity requirement as PlaywrightUiExecutor: Playwright's sync API
            // needs everything from Playwright.create() to browser.close() on one thread.
            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        // --disable-http2: some real sites' WAF/anti-bot layer resets or mangles
                        // HTTP/2 frames for automated/headless traffic (seen live against
                        // makemytrip.com — net::ERR_HTTP2_PROTOCOL_ERROR on the very first
                        // navigate, before any content loads). Forcing HTTP/1.1 sidesteps that
                        // class of failure entirely.
                        .setArgs(List.of("--disable-dev-shm-usage", "--no-sandbox", "--disable-http2")));
                try {
                    Page page = browser.newPage();
                    log.info("Capturing DOM snapshot: navigating to '{}'", url);
                    // LOAD, not COMMIT: PlaywrightUiExecutor only needs COMMIT because it does
                    // its own per-action actionability waits afterward; this needs the page
                    // actually rendered before pulling elements out of it.
                    page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.LOAD)
                            .setTimeout(navigationTimeoutMs));

                    Object result = page.evaluate(EXTRACT_SCRIPT);
                    String snapshot = result == null ? "" : result.toString().trim();
                    if (snapshot.length() > MAX_SNAPSHOT_CHARS) {
                        snapshot = snapshot.substring(0, MAX_SNAPSHOT_CHARS) + "\n... (truncated)";
                    }
                    log.info("DOM snapshot captured for '{}': {} chars (cap {} elements)",
                            url, snapshot.length(), MAX_ELEMENTS);
                    return snapshot;
                } finally {
                    browser.close();
                }
            }
        };
    }
}
