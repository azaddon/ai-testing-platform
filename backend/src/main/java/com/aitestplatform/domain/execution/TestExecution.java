package com.aitestplatform.domain.execution;

/**
 * Common contract every protocol-specific execution model implements. Deliberately thin:
 * API and UI executions don't share enough real behavior to justify forcing a shared base
 * class (that's exactly the inheritance trap this design avoids) — each protocol composes
 * its own value objects (ApiExecutionModel has-a ApiRequestSpec + List<ApiAssertion>;
 * UiExecutionModel has-a List<UiStep> + List<UiLocator>, etc.) rather than extending a
 * common "ExecutionModel" superclass with fields that don't fit both.
 *
 * This interface exists purely so the execution/reporting/persistence layers can work with
 * "some kind of test execution" polymorphically, and so a third protocol (gRPC, GraphQL,
 * whatever) can be added later without touching API or UI code at all.
 */
public interface TestExecution {

    /** "api" | "ui" | future protocol identifiers. Used for polymorphic dispatch/reporting. */
    String getType();

    /** Short human-readable identity for logs/reports, e.g. "POST /users" or the target URL. */
    String getTargetSummary();
}
