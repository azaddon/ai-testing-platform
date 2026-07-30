package com.aitestplatform.application.execution;

import com.aitestplatform.domain.execution.ExecutionResult;
import com.aitestplatform.domain.execution.TestExecution;

/**
 * Port (in the Clean Architecture sense): the application layer depends only on this
 * interface, never on Rest Assured or Playwright directly. RestAssuredApiExecutor and
 * PlaywrightUiExecutor are the infrastructure adapters that implement it. Adding a third
 * protocol later means adding a new TestExecution + ExecutionResult pair and one adapter
 * here — nothing above this layer changes.
 */
public interface TestExecutor<T extends TestExecution, R extends ExecutionResult> {
    R execute(T model);
}
