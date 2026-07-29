package com.aitestplatform.common;

import org.springframework.http.HttpStatus;

/**
 * Base type for the API Test Generator's scenario -> code -> run state-machine errors.
 * Each subtype carries the HTTP status GlobalExceptionHandler should respond with, so the
 * frontend gets a clean JSON error instead of a stack trace.
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

    /** Code was already generated for this scenario; refuse to silently overwrite it. */
    public static class CodeAlreadyGeneratedException extends ApiTestWorkflowException {
        public CodeAlreadyGeneratedException(String scriptId) {
            super("Code has already been generated for scenario '" + scriptId + "'. "
                    + "Generate a new scenario if you want different code.", HttpStatus.CONFLICT);
        }
    }

    /** Run was requested but no code exists yet for this scenario. */
    public static class CodeNotGeneratedException extends ApiTestWorkflowException {
        public CodeNotGeneratedException(String scriptId) {
            super("Code is not present for scenario '" + scriptId + "'. Please generate code first.", HttpStatus.BAD_REQUEST);
        }
    }

    /** LLM-generated code failed GeneratedCodeValidator's safety check (empty output or a
     *  forbidden pattern like System.exit/ProcessBuilder/file I/O). Never persisted or run. */
    public static class UnsafeGeneratedCodeException extends ApiTestWorkflowException {
        public UnsafeGeneratedCodeException(String scriptId, String reason) {
            super("Generated code for scenario '" + scriptId + "' failed safety validation: " + reason,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
