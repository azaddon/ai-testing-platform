package com.aitestplatform.domain.execution.api;

import com.aitestplatform.domain.execution.AssertionOutcome;
import com.aitestplatform.domain.execution.ExecutionResult;

import java.util.List;

public record ApiExecutionResult(
        boolean passed,
        long durationMs,
        int actualStatus,
        String responseBody,
        List<AssertionOutcome> assertionOutcomes,
        String errorMessage
) implements ExecutionResult {

    @Override
    public String summary() {
        return "HTTP " + actualStatus + (passed ? " (passed)" : " (failed)");
    }
}
