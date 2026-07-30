import { useEffect, useState } from "react";
import { api } from "../api/client";

interface ApiExecutionModel {
  method: string;
  endpoint: string;
  headers: Record<string, string>;
  queryParams: Record<string, string>;
  pathParams: Record<string, string>;
  cookies: Record<string, string>;
  requestBody: string;
  expectedStatus: number;
  assertions: { type: string; path: string | null; expectedValue: string | null }[];
}

interface ApiTestScript {
  id: string;
  endpoint: string;
  method: string;
  scenario: string;
  // The data that actually runs (interpreted directly by RestAssuredApiExecutor — never
  // compiled). Null until "Generate Code" succeeds.
  executionModel: ApiExecutionModel | null;
  // A read-only, deterministically-rendered preview of executionModel, for display only.
  // This is never compiled or executed — see renderedCode below.
  renderedCode: string | null;
  status: string; // scenario-generated | code-generated | running | passed | failed
  lastRunResult?: { statusCode: number; latencyMs: number; assertionsPassed: boolean; output: string };
}

export default function ApiTestGenerator({ projectId }: { projectId: string }) {
  const [openApiSpec, setOpenApiSpec] = useState("");
  const [count, setCount] = useState(10);
  const [baseUri, setBaseUri] = useState("http://localhost:8080");
  const [scripts, setScripts] = useState<ApiTestScript[]>([]);
  const [loadingScenarios, setLoadingScenarios] = useState(false);
  const [loadingExisting, setLoadingExisting] = useState(true);
  const [scenarioError, setScenarioError] = useState<string | null>(null);
  // per-row transient state: which row is busy, and its last error message
  const [rowBusy, setRowBusy] = useState<Record<string, boolean>>({});
  const [rowError, setRowError] = useState<Record<string, string | null>>({});

  // Load previously generated scenarios/code for this project on mount (and whenever the
  // project changes) — without this, a refresh shows an empty list even though everything
  // is still in Mongo, since the generate/code/run handlers only ever set local state.
  useEffect(() => {
    let cancelled = false;
    setLoadingExisting(true);
    api
      .listApiTests(projectId)
      .then((existing) => {
        if (!cancelled) setScripts(existing as ApiTestScript[]);
      })
      .catch((e: any) => {
        if (!cancelled) setScenarioError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoadingExisting(false);
      });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  async function handleGenerateScenarios() {
    setLoadingScenarios(true);
    setScenarioError(null);
    try {
      const generated = (await api.generateApiTestScenarios(projectId, {
        openApiSpec,
        endpointFilters: [],
        count
      })) as ApiTestScript[];
      // Append to whatever's already loaded rather than replacing it, so generating
      // against a second spec builds up the suite instead of hiding earlier scenarios.
      setScripts((prev) => [...prev, ...generated]);
    } catch (e: any) {
      setScenarioError(e.message);
    } finally {
      setLoadingScenarios(false);
    }
  }

  async function handleGenerateCode(scriptId: string) {
    setRowBusy((prev) => ({ ...prev, [scriptId]: true }));
    setRowError((prev) => ({ ...prev, [scriptId]: null }));
    try {
      const updated = (await api.generateApiTestCode(scriptId)) as ApiTestScript;
      setScripts((prev) => prev.map((s) => (s.id === scriptId ? updated : s)));
    } catch (e: any) {
      // e.g. 409 "Code has already been generated for scenario '...'. Generate a new
      // scenario if you want different code." or 404 if the scenario somehow disappeared.
      setRowError((prev) => ({ ...prev, [scriptId]: e.message }));
    } finally {
      setRowBusy((prev) => ({ ...prev, [scriptId]: false }));
    }
  }

  async function handleRun(scriptId: string) {
    setRowBusy((prev) => ({ ...prev, [scriptId]: true }));
    setRowError((prev) => ({ ...prev, [scriptId]: null }));
    try {
      const updated = (await api.executeApiTest(scriptId, baseUri)) as ApiTestScript;
      setScripts((prev) => prev.map((s) => (s.id === scriptId ? updated : s)));
    } catch (e: any) {
      // e.g. 400 "Code is not present for scenario '...'. Please generate code first."
      setRowError((prev) => ({ ...prev, [scriptId]: e.message }));
    } finally {
      setRowBusy((prev) => ({ ...prev, [scriptId]: false }));
    }
  }

  return (
    <div>
      <h2>API Test Generator (Rest Assured)</h2>

      <textarea
        placeholder="Paste your OpenAPI/Swagger spec (YAML or JSON)..."
        value={openApiSpec}
        onChange={(e) => setOpenApiSpec(e.target.value)}
        rows={8}
        style={{ width: "100%", padding: 8, marginBottom: 12, fontFamily: "monospace" }}
      />

      <div style={{ display: "flex", gap: 16, marginBottom: 16, alignItems: "center" }}>
        <label>
          Base URI:{" "}
          <input value={baseUri} onChange={(e) => setBaseUri(e.target.value)} style={{ width: 260 }} />
        </label>
        <label>
          Count (max scenarios):{" "}
          <input
            type="number"
            value={count}
            min={1}
            max={50}
            onChange={(e) => setCount(Number(e.target.value))}
            style={{ width: 70 }}
          />
        </label>
        <button onClick={handleGenerateScenarios} disabled={loadingScenarios || !openApiSpec}>
          {loadingScenarios ? "Generating scenarios..." : "1. Generate Scenario"}
        </button>
      </div>

      {scenarioError && <p style={{ color: "crimson" }}>{scenarioError}</p>}
      {loadingExisting && <p style={{ color: "#888" }}>Loading existing scenarios...</p>}

      {scripts.map((s) => {
        const hasCode = !!s.executionModel;
        const busy = !!rowBusy[s.id];
        const error = rowError[s.id];

        return (
          <div key={s.id} style={{ border: "1px solid #ddd", borderRadius: 8, padding: 16, marginBottom: 12 }}>
            <div style={{ display: "flex", justifyContent: "space-between" }}>
              <strong>
                {s.method} {s.endpoint} — {s.scenario}
              </strong>
              <span style={{ fontSize: 12, color: "#888" }}>{s.status}</span>
            </div>

            {hasCode ? (
              <>
                <p style={{ color: "#888", fontSize: 12, marginBottom: 4 }}>
                  Preview only — this is rendered from the test's data and isn't compiled or run.
                  The actual run executes the underlying request data directly.
                </p>
                <pre style={{ background: "#f6f8fa", padding: 12, overflowX: "auto", fontSize: 12 }}>
                  {s.renderedCode}
                </pre>
              </>
            ) : (
              <p style={{ color: "#888", fontSize: 13 }}>No code generated yet for this scenario.</p>
            )}

            <div style={{ display: "flex", gap: 8 }}>
              <button onClick={() => handleGenerateCode(s.id)} disabled={busy || hasCode}>
                {busy ? "Working..." : "2. Generate Code"}
              </button>
              <button onClick={() => handleRun(s.id)} disabled={busy || !hasCode}>
                {busy ? "Working..." : "3. Run"}
              </button>
            </div>

            {error && <p style={{ color: "crimson", fontSize: 13, marginTop: 8 }}>{error}</p>}

            {s.lastRunResult && (
              <p style={{ fontSize: 13, marginTop: 8 }}>
                Status {s.lastRunResult.statusCode} · {s.lastRunResult.latencyMs}ms ·{" "}
                {s.lastRunResult.assertionsPassed ? "assertions passed" : "assertions failed"}
              </p>
            )}
          </div>
        );
      })}

      {!loadingExisting && scripts.length === 0 && (
        <p style={{ color: "#888" }}>No scenarios yet — generate some above.</p>
      )}
    </div>
  );
}
