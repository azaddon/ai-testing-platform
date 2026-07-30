package com.aitestplatform.common;

import org.springframework.http.HttpStatus;

/**
 * Base type for the API Test Generator's scenario -> execution-model -> run state-machine
 * errors. Each subtype carries the HTTP status GlobalExceptionHandler should respond with,
 * so the frontend gets a clean JSON error instead of a stack trace.
 */
public class ApiTestWorkflowException extends RuntimeException {

    private final HttpStatus status;

    public ApiTestWorkflowException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** No ApiTestScript exists for the given id — scenario must be generated first. */
    public static class ScenarioNotFoundException extends ApiTestWorkflowException {
        public ScenarioNotFoundException(String scriptId) {
            super("No scenario found for id '" + scriptId + "'. Generate a scenario first.", HttpStatus.NOT_FOUND);
        }
    }

    /** An execution model was already generated for this scenario; refuse to silently overwrite it. */
    public static class ExecutionModelAlreadyGeneratedException extends ApiTestWorkflowException {
        public ExecutionModelAlreadyGeneratedException(String scriptId) {
            super("An execution model has already been generated for scenario '" + scriptId + "'. "
                    + "Generate a new scenario if you want a different one.", HttpStatus.CONFLICT);
        }
    }

    /** Run was requested but no execution model exists yet for this scenario. */
    public static class ExecutionModelNotGeneratedException extends ApiTestWorkflowException {
        public ExecutionModelNotGeneratedException(String scriptId) {
            super("An execution model is not present for scenario '" + scriptId + "'. Please generate code first.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /** LLM-generated execution model failed ApiExecutionModelValidator's structural check
     *  (missing method/endpoint, out-of-range status code, etc). Never persisted or run. */
    public static class InvalidExecutionModelException extends ApiTestWorkflowException {
        public InvalidExecutionModelException(String scriptId, String reason) {
            super("Generated execution model for scenario '" + scriptId + "' failed validation: " + reason,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
