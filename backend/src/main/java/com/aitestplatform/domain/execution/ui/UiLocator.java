package com.aitestplatform.domain.execution.ui;

import java.util.List;

/**
 * A resolved element locator. `primaryLocator` and every entry in `fallbackLocators` are
 * Playwright selector-engine strings (e.g. "role=button[name='Submit']", "text=Submit",
 * "css=button.primary") — data that Playwright's own Locator(String) parses natively.
 * Never a Java code snippet: this is what makes UI execution code-free end to end.
 */
public record UiLocator(String ref, String primaryLocator, List<String> fallbackLocators, String rationale) {}
