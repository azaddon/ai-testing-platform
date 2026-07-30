# AI-Assisted Testing Platform — Code Scaffold

Companion codebase to `AI-Testing-Platform-Design.md`. Implements the four priority
features end-to-end (AI Test Case Generation, API Test Generator, Failure Analysis +
Log Summarization, Test Execution Dashboard) plus the supporting architecture
(LLM provider abstraction, execution engine, Docker deployment).

## What's actually implemented vs. stubbed

**Implemented (real logic, not stubs):**
- `LlmProvider` abstraction with working `GeminiProvider` (default) and `OpenAiProvider`
  (pluggable) — both call the real Gemini / OpenAI REST APIs.
- AI Test Case Generation: prompt → Gemini → parsed → persisted as draft `TestCase` docs
  → approve/list endpoints.
- API Test Generator: OpenAPI spec → Gemini generates an `ApiExecutionModel` (method,
  endpoint, headers, assertions, as data) → validated → executed directly via
  `RestAssuredApiExecutor` → result capture. No runtime code generation or compilation.
- Test Execution Engine: `PlaywrightUiExecutor` interprets a `UiExecutionModel` (url,
  steps, locators, assertions) directly through Playwright's Java API — same
  no-codegen/no-javac approach as the API side — plus async orchestration and a
  WebSocket channel (`/ws/test-runs/{id}`) for live status.
- Failure Analysis: root-cause/category/confidence/fix via Gemini, plus a map-reduce
  log summarizer that chunks large logs to stay within context limits.
- Dashboard/Analytics: Mongo aggregation for pass rate + time-bucketed trends.
- React frontend: Dashboard, Test Case Generator, API Test Generator, and a per-run
  detail view wired to the endpoints above (with live WebSocket updates on the run page).

**Intentionally light / not wired up** (per the design doc's phasing — build these next):
- UI Locator Generator: `LlmProvider.generateLocators()` exists and has a prompt template,
  but nothing yet calls it to auto-author `UiTestScript.executionModel` from a TestCase's
  natural-language steps. That's the missing link between "AI Test Case Generation" and
  "actually runs in Playwright" — see `UiTestScript.java` for the intended convention.
- Screenshot Analysis: `GeminiProvider.analyzeScreenshot()` works (multimodal call);
  `OpenAiProvider.analyzeScreenshot()` throws `UnsupportedOperationException` (needs a
  vision-capable model wired in if you run with `llm.provider=openai`).
- Automatic Bug Report Creation: not implemented — straightforward to add as a service
  that takes a `FailureAnalysis` and calls `LlmProvider` again with a "draft a bug report"
  prompt, following the same pattern as `FailureAnalysisService`.
- Flakiness scoring: `AnalyticsService.flakinessScore()` is a stub returning `0.0`.

## Running locally

```bash
cp .env.example .env   # add GEMINI_API_KEY (or OPENAI_API_KEY + LLM_PROVIDER=openai)
docker compose up --build
```

- Frontend: http://localhost:3000
- Backend API: http://localhost:8080/api/v1
- MongoDB: localhost:27017

Without Docker: `cd backend && mvn spring-boot:run` (needs a JDK 17+ — Maven has to
compile the project's own sources; neither execution path does runtime code generation
anymore, so a JRE-only setup is no longer the specific problem it used to be, but you
still need a full JDK to build) and `cd frontend && npm install && npm run dev`.

## Known scaffold-level gaps (be aware before treating this as production-ready)

1. **Sandboxing is minimal.** `ApiTestExecutionService` compiles and runs LLM-generated
   Rest Assured code in the same JVM as the API, with only a wall-clock timeout.
   `PlaywrightUiExecutor` doesn't compile anything — it interprets a `UiExecutionModel`
   (url, steps, locators, assertions) directly through Playwright's Java API — but it
   still runs in the same JVM/container as the API, bounded only by
   `execution.sandbox.timeout-seconds`. The design doc calls for moving both into an
   isolated `executor` container with no outbound network access beyond the target under
   test — that container split isn't implemented yet, both run in the `api` container for now.
2. **No auth.** `spring-boot-starter-security` is on the classpath but no `SecurityConfig`
   is wired up — every endpoint is open. Add JWT auth before exposing this beyond localhost.
3. **Large OpenAPI specs aren't chunked.** `ApiTestGenerationService` sends the whole spec
   to the LLM in one call; for large specs, split per-endpoint or per-tag first.
4. **GridFS/S3 for artifacts isn't wired up.** Screenshots and raw logs are passed as
   strings/base64 through request bodies rather than fetched from object storage.
5. **Rate limiting / cost caps** described in the design doc's Security & Cost section
   aren't implemented — `LlmCallLog` records every call's token counts, but nothing
   enforces a per-project budget yet.

## Project layout

```
backend/   Spring Boot (Java 17) — see src/main/java/com/aitestplatform/{llm,testcase,apitest,execution,failureanalysis,dashboard}
frontend/  React + TypeScript (Vite)
docker-compose.yml
```
