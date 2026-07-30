package com.aitestplatform.domain.execution.api;

/**
 * One assertion to evaluate against the API response. `path` is a JSON path (for
 * JSON_PATH_* types), a header name (for HEADER_EQUALS), or unused (for STATUS_CODE /
 * BODY_CONTAINS, where `expectedValue` carries the whole comparison value).
 */
public record ApiAssertion(ApiAssertionType type, String path, String expectedValue) {}
