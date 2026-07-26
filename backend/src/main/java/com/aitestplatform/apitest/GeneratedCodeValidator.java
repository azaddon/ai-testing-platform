package com.aitestplatform.apitest;
import java.util.List;

public class GeneratedCodeValidator {

    /**
     * Validates the generated code to ensure it is safe and adheres to expected patterns.
     * Throws an exception if the code is invalid.
     */
    public static void validateGeneratedCode(String generatedCode) throws IllegalArgumentException {
        // Basic validation: Check for forbidden keywords or patterns
        if(generatedCode == null || generatedCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Generated code is empty.");
        }
        List<String> forbiddenKeywords = List.of("System.exit", "Runtime.getRuntime", "ProcessBuilder", "java.io.File");
        for(String keyword : forbiddenKeywords) {
            if (generatedCode.contains(keyword)) {
                throw new SecurityException("Security Violation: Forbidden code structure detected -> " + keyword);
            }
        }
    }
}