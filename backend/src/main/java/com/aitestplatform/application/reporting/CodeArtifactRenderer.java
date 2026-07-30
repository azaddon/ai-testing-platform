package com.aitestplatform.application.reporting;

import com.aitestplatform.domain.execution.api.ApiAssertion;
import com.aitestplatform.domain.execution.api.ApiExecutionModel;
import com.aitestplatform.domain.execution.ui.UiActionType;
import com.aitestplatform.domain.execution.ui.UiExecutionModel;
import com.aitestplatform.domain.execution.ui.UiLocator;
import com.aitestplatform.domain.execution.ui.UiStep;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Renders a human-readable Rest-Assured-style / Playwright-style code string FROM an
 * execution model, purely for display in the UI (e.g. "here's roughly what this test
 * does"). This is deterministic — our own code, not an LLM call — specifically so the
 * displayed "code" can never diverge from what actually executes (RestAssuredApiExecutor /
 * PlaywrightUiExecutor interpret the same model this renders text from).
 *
 * This is the "generated Java code" the platform shows the user. It is NEVER compiled,
 * NEVER passed to javac, and NEVER reflectively invoked — it exists only to be read.
 */
@Component
public class CodeArtifactRenderer {

    public String renderApi(ApiExecutionModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Display artifact only — not executed. Actual execution is data-driven\n");
        sb.append("// via RestAssuredApiExecutor, interpreting the ApiExecutionModel directly.\n");
        sb.append("given()\n");
        for (Map.Entry<String, String> h : safe(model.headers()).entrySet()) {
            sb.append("    .header(\"").append(h.getKey()).append("\", \"").append(h.getValue()).append("\")\n");
        }
        for (Map.Entry<String, String> q : safe(model.queryParams()).entrySet()) {
            sb.append("    .queryParam(\"").append(q.getKey()).append("\", \"").append(q.getValue()).append("\")\n");
        }
        for (Map.Entry<String, String> p : safe(model.pathParams()).entrySet()) {
            sb.append("    .pathParam(\"").append(p.getKey()).append("\", \"").append(p.getValue()).append("\")\n");
        }
        for (Map.Entry<String, String> c : safe(model.cookies()).entrySet()) {
            sb.append("    .cookie(\"").append(c.getKey()).append("\", \"").append(c.getValue()).append("\")\n");
        }
        if (model.requestBody() != null && !model.requestBody().isBlank()) {
            sb.append("    .contentType(JSON)\n");
            sb.append("    .body(").append(quote(model.requestBody())).append(")\n");
        }
        sb.append(".when()\n");
        sb.append("    .").append(model.method().name().toLowerCase()).append("(\"").append(model.endpoint()).append("\")\n");
        sb.append(".then()\n");
        sb.append("    .statusCode(").append(model.expectedStatus()).append(")\n");
        for (ApiAssertion a : safeList(model.assertions())) {
            sb.append("    // assert ").append(a.type()).append(" ")
                    .append(a.path() == null ? "" : a.path() + " ")
                    .append(a.expectedValue()).append("\n");
        }
        return sb.toString();
    }

    public String renderUi(UiExecutionModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Display artifact only — not executed. Actual execution is data-driven\n");
        sb.append("// via PlaywrightUiExecutor, interpreting the UiExecutionModel directly.\n");
        sb.append("page.navigate(\"").append(model.url()).append("\");\n\n");

        Map<String, UiLocator> locatorsByRef = safeList(model.locators()).stream()
                .collect(java.util.stream.Collectors.toMap(UiLocator::ref, l -> l, (a, b) -> a));

        for (UiStep step : safeList(model.steps())) {
            UiLocator loc = locatorsByRef.get(step.locatorRef());
            String selector = loc == null ? "/* unresolved: " + step.locatorRef() + " */" : quote(loc.primaryLocator());
            sb.append("// ").append(step.description() == null ? "" : step.description()).append("\n");
            sb.append("page.locator(").append(selector).append(")").append(renderAction(step.action(), step.value())).append(";\n");
        }
        return sb.toString();
    }

    private String renderAction(UiActionType action, String value) {
        return switch (action) {
            case CLICK -> ".click()";
            case FILL -> ".fill(" + quote(value) + ")";
            case HOVER -> ".hover()";
            case SELECT_OPTION -> ".selectOption(" + quote(value) + ")";
            case PRESS_KEY -> ".press(" + quote(value) + ")";
            case CHECK -> ".check()";
            case UNCHECK -> ".uncheck()";
        };
    }

    private String quote(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\\\"")) + "\"";
    }

    private Map<String, String> safe(Map<String, String> m) {
        return m == null ? Map.of() : m;
    }

    private <T> java.util.List<T> safeList(java.util.List<T> l) {
        return l == null ? java.util.List.of() : l;
    }
}
