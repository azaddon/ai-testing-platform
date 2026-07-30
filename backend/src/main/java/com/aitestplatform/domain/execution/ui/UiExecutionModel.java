package com.aitestplatform.domain.execution.ui;

import com.aitestplatform.domain.execution.TestExecution;

import java.util.List;

/**
 * Everything needed to execute one UI test scenario, as DATA. PlaywrightUiExecutor
 * interprets this directly: navigates to `url`, then for each UiStep resolves its
 * locatorRef against `locators` and dispatches on UiActionType to the matching
 * Playwright Locator method. No Java code is compiled or reflectively invoked.
 */
public record UiExecutionModel(
        String url,
        List<UiStep> steps,
        List<UiLocator> locators,
        List<UiAssertion> assertions,
        List<ScreenshotSpec> screenshots
) implements TestExecution {

    @Override
    public String getType() {
        return "ui";
    }

    @Override
    public String getTargetSummary() {
        return url;
    }
}
