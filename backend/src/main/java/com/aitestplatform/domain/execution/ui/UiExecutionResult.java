package com.aitestplatform.domain.execution.ui;

import com.aitestplatform.domain.execution.AssertionOutcome;
import com.aitestplatform.domain.execution.ExecutionResult;

import java.util.List;
import java.util.Map;

public record UiExecutionResult(
        boolean passed,
        long durationMs,
        List<AssertionOutcome> assertionOutcomes,
        Map<String, String> screenshotsBase64ByLabel,
        String errorMessage
) implements ExecutionResult {

    @Override
    public String summary() {
        return passed ? "UI run passed" : "UI run failed: " + errorMessage;
    }
}
