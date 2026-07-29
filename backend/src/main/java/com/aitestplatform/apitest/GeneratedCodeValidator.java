package com.aitestplatform.apitest;

import com.aitestplatform.common.ApiTestWorkflowException.UnsafeGeneratedCodeException;

import java.util.List;

public class GeneratedCodeValidator {

    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "System.exit", "Runtime.getRuntime", "ProcessBuilder", "java.io.File");

    /**
     * Validates LLM-generated Rest Assured code before it's persisted or compiled.
     * Throws ApiTestWorkflowException.UnsafeGeneratedCodeException (422) — caught by
     * GlobalExceptionHandler like every other workflow error, so the frontend gets a
     * clean message instead of a stack trace.
     */
    public static void validateGeneratedCode(String scriptId, String generatedCode) {
        if (generatedCode == null || generatedCode.trim().isEmpty()) {
            throw new UnsafeGeneratedCodeException(scriptId, "generated code was empty");
        }
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (generatedCode.contains(keyword)) {
                throw new UnsafeGeneratedCodeException(scriptId, "forbidden pattern detected -> " + keyword);
            }
        }
    }
}
