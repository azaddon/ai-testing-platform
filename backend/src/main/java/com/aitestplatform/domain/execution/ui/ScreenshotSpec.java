package com.aitestplatform.domain.execution.ui;

/** Capture a screenshot after the step at `afterStepIndex` completes (-1 = before any steps run). */
public record ScreenshotSpec(int afterStepIndex, String label) {}
