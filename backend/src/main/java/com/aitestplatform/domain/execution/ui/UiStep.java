package com.aitestplatform.domain.execution.ui;

/**
 * One interaction step. `locatorRef` refers to a UiLocator by its `ref` id (composition:
 * a step doesn't embed its own locator, it points at one in the model's locators list, so
 * the same locator can be reused/re-resolved without duplicating it per step).
 */
public record UiStep(int index, UiActionType action, String locatorRef, String value, String description) {}
