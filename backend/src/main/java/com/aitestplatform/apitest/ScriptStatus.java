package com.aitestplatform.apitest;

public enum ScriptStatus {
    GENERATED("generated"),
    RUNNING("running"),
    PASSED("passed"),
    FAILED("failed");

    private final String status;

    ScriptStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return status;
    }
    
}
