package com.aitestplatform.domain.execution;

import java.util.List;

/**
 * Common contract for the outcome of running a TestExecution. Like TestExecution itself,
 * this is intentionally thin — ApiExecutionResult and UiExecutionResult carry very
 * different protocol-specific detail (status code + response body vs. screenshots),
 * composed in rather than force-fit into shared fields.
 */
public interface ExecutionResult {
    boolean passed();
    long durationMs();
    List<AssertionOutcome> assertionOutcomes();
    String summary();
}
