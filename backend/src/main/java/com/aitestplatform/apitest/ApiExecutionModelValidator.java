package com.aitestplatform.apitest;

import com.aitestplatform.common.ApiTestWorkflowException.InvalidExecutionModelException;
import com.aitestplatform.domain.execution.api.ApiExecutionModel;

/**
 * Structural validation of an ApiExecutionModel before it's persisted or executed.
 *
 * This replaces GeneratedCodeValidator's role from the old "compile LLM-authored Java"
 * design. There is no code to scan for forbidden keywords anymore — the LLM only ever
 * produces data (method/endpoint/headers/... field values), and that data is interpreted
 * directly by RestAssuredApiExecutor, never compiled or reflectively invoked. So the only
 * thing left to validate is that the data itself is well-formed enough to run.
 *
 * (GeneratedCodeValidator.java is left in place, unused, as harmless leftover — it can be
 * removed by hand once the old code-generation path is fully retired.)
 */
public final class ApiExecutionModelValidator {

    private ApiExecutionModelValidator() {}

    /**
     * Throws ApiTestWorkflowException.InvalidExecutionModelException (422) — caught by
     * GlobalExceptionHandler like every other workflow error, so the frontend gets a
     * clean message instead of a stack trace.
     */
    public static void validate(String scriptId, ApiExecutionModel model) {
        if (model == null) {
            throw new InvalidExecutionModelException(scriptId, "execution model was null");
        }
        if (model.method() == null) {
            throw new InvalidExecutionModelException(scriptId, "method is required");
        }
        if (model.endpoint() == null || model.endpoint().isBlank()) {
            throw new InvalidExecutionModelException(scriptId, "endpoint is required");
        }
        if (model.expectedStatus() < 100 || model.expectedStatus() > 599) {
            throw new InvalidExecutionModelException(scriptId,
                    "expectedStatus must be a valid HTTP status code, got " + model.expectedStatus());
        }
    }
}
