package com.aitestplatform.domain.execution.api;

import com.aitestplatform.domain.execution.HttpMethod;
import com.aitestplatform.domain.execution.TestExecution;

import java.util.List;
import java.util.Map;

/**
 * Everything needed to execute one API test scenario, as DATA — not as a code string.
 * RestAssuredApiExecutor interprets this directly via Rest Assured's fluent Java API.
 * There is nothing here for an LLM to smuggle arbitrary code through: every field is a
 * plain value (method/endpoint/maps/strings/assertions), so "generating this" can never
 * mean "generating something we then compile and run".
 */
public record ApiExecutionModel(
        HttpMethod method,
        String endpoint,
        Map<String, String> headers,
        Map<String, String> queryParams,
        Map<String, String> pathParams,
        Map<String, String> cookies,
        String requestBody,
        int expectedStatus,
        List<ApiAssertion> assertions
) implements TestExecution {

    @Override
    public String getType() {
        return "api";
    }

    @Override
    public String getTargetSummary() {
        return method + " " + endpoint;
    }
}
