package com.aitestplatform.domain.execution;

/** Result of evaluating a single assertion (API or UI) after execution. Used by reporting. */
public record AssertionOutcome(String description, boolean passed, String actualValue) {}
