package com.aitestplatform.infrastructure.execution.restassured;

import com.aitestplatform.application.execution.TestExecutor;
import com.aitestplatform.domain.execution.AssertionOutcome;
import com.aitestplatform.domain.execution.api.ApiAssertion;
import com.aitestplatform.domain.execution.api.ApiExecutionModel;
import com.aitestplatform.domain.execution.api.ApiExecutionResult;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.http.Method;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Interprets an ApiExecutionModel directly through Rest Assured's fluent Java API — no
 * source generation, no javac, no reflection. The LLM's job ends at producing the
 * ApiExecutionModel's field values; running the test is entirely this class's job, using
 * a library API that only ever does what its own methods do.
 */
@Component
public class RestAssuredApiExecutor implements TestExecutor<ApiExecutionModel, ApiExecutionResult> {

    @Override
    public ApiExecutionResult execute(ApiExecutionModel model) {
        long start = System.currentTimeMillis();
        try {
            RequestSpecification spec = RestAssured.given();

            if (notEmpty(model.headers())) spec = spec.headers(model.headers());
            if (notEmpty(model.queryParams())) spec = spec.queryParams(model.queryParams());
            if (notEmpty(model.pathParams())) spec = spec.pathParams(model.pathParams());
            if (notEmpty(model.cookies())) spec = spec.cookies(model.cookies());
            if (model.requestBody() != null && !model.requestBody().isBlank()) {
                spec = spec.contentType(ContentType.JSON).body(model.requestBody());
            }

            Response response = spec.when().request(Method.valueOf(model.method().name()), model.endpoint());
            long duration = System.currentTimeMillis() - start;

            List<AssertionOutcome> outcomes = new ArrayList<>();
            outcomes.add(checkStatus(response, model.expectedStatus()));
            for (ApiAssertion assertion : model.assertions()) {
                outcomes.add(evaluate(response, assertion));
            }

            boolean passed = outcomes.stream().allMatch(AssertionOutcome::passed);
            return new ApiExecutionResult(passed, duration, response.getStatusCode(),
                    safeBody(response), outcomes, null);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new ApiExecutionResult(false, duration, -1, null, List.of(),
                    "Execution error: " + e.getMessage());
        }
    }

    private AssertionOutcome checkStatus(Response response, int expectedStatus) {
        boolean passed = response.getStatusCode() == expectedStatus;
        return new AssertionOutcome(
                "status code equals " + expectedStatus, passed, String.valueOf(response.getStatusCode()));
    }

    private AssertionOutcome evaluate(Response response, ApiAssertion assertion) {
        try {
            return switch (assertion.type()) {
                case STATUS_CODE -> {
                    int expected = Integer.parseInt(assertion.expectedValue());
                    yield new AssertionOutcome("status code equals " + expected,
                            response.getStatusCode() == expected, String.valueOf(response.getStatusCode()));
                }
                case JSON_PATH_EQUALS -> {
                    Object actual = response.jsonPath().get(assertion.path());
                    String actualStr = actual == null ? null : actual.toString();
                    boolean passed = actualStr != null && actualStr.equals(assertion.expectedValue());
                    yield new AssertionOutcome(
                            assertion.path() + " equals " + assertion.expectedValue(), passed, actualStr);
                }
                case JSON_PATH_EXISTS -> {
                    Object actual = response.jsonPath().get(assertion.path());
                    yield new AssertionOutcome(assertion.path() + " exists", actual != null,
                            actual == null ? "null" : actual.toString());
                }
                case HEADER_EQUALS -> {
                    String actual = response.getHeader(assertion.path());
                    boolean passed = actual != null && actual.equals(assertion.expectedValue());
                    yield new AssertionOutcome(
                            "header " + assertion.path() + " equals " + assertion.expectedValue(), passed, actual);
                }
                case BODY_CONTAINS -> {
                    String body = safeBody(response);
                    boolean passed = body != null && body.contains(assertion.expectedValue());
                    yield new AssertionOutcome("body contains " + assertion.expectedValue(), passed, null);
                }
            };
        } catch (Exception e) {
            return new AssertionOutcome(assertion.type() + " " + assertion.path(), false, "error: " + e.getMessage());
        }
    }

    private String safeBody(Response response) {
        try {
            return response.getBody().asString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean notEmpty(Map<String, String> map) {
        return map != null && !map.isEmpty();
    }
}
