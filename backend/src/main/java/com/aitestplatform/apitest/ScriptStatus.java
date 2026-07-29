package com.aitestplatform.apitest;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle of an ApiTestScript: a scenario is generated first (no code), then code is
 * generated for it (still not run), then it moves through running to a terminal passed/failed.
 */
public enum ScriptStatus {
    SCENARIO_GENERATED("scenario-generated"),
    CODE_GENERATED("code-generated"),
    RUNNING("running"),
    PASSED("passed"),
    FAILED("failed");

    private final String status;

    ScriptStatus(String status) {
        this.status = status;
    }

    /** Ensures the JSON sent to the frontend is "scenario-generated", not "SCENARIO_GENERATED". */
    @JsonValue
    public String getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return status;
    }
}
