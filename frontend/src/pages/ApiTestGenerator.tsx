import { useState } from "react";
import { api } from "../api/client";

interface ApiTestScript {
  id: string;
  endpoint: string;
  method: string;
  scenario: string;
  generatedCode: string;
  status: string;
  lastRunResult?: { statusCode: number; latencyMs: number; assertionsPassed: boolean; output: string };
}

export default function ApiTestGenerator({ projectId }: { projectId: string }) {
  const [openApiSpec, setOpenApiSpec] = useState("");
  const [baseUri, setBaseUri] = useState("http://localhost:8080");
  const [scripts, setScripts] = useState<ApiTestScript[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleGenerate() {
    setLoading(true);
    setError(null);
    try {
      const generated = (await api.generateApiTests(projectId, {
        openApiSpec,
        endpointFilters: []
      })) as ApiTestScript[];
      setScripts(generated);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleExecute(scriptId: string) {
    const updated = (await api.executeApiTest(scriptId, baseUri)) as ApiTestScript;
    setScripts((prev) => prev.map((s) => (s.id === scriptId ? updated : s)));
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

      <div style={{ display: "flex", gap: 16, marginBottom: 16 }}>
        <label>
          Base URI:{" "}
          <input value={baseUri} onChange={(e) => setBaseUri(e.target.value)} style={{ width: 260 }} />
        </label>
        <button onClick={handleGenerate} disabled={loading || !openApiSpec}>
          {loading ? "Generating..." : "Generate API tests"}
        </button>
      </div>

      {error && <p style={{ color: "crimson" }}>{error}</p>}

      {scripts.map((s) => (
        <div key={s.id} style={{ border: "1px solid #ddd", borderRadius: 8, padding: 16, marginBottom: 12 }}>
          <div style={{ display: "flex", justifyContent: "space-between" }}>
            <strong>
              {s.method} {s.endpoint} — {s.scenario}
            </strong>
            <span style={{ fontSize: 12, color: "#888" }}>{s.status}</span>
          </div>
          <pre style={{ background: "#f6f8fa", padding: 12, overflowX: "auto", fontSize: 12 }}>{s.generatedCode}</pre>
          <button onClick={() => handleExecute(s.id)}>Run</button>
          {s.lastRunResult && (
            <p style={{ fontSize: 13, marginTop: 8 }}>
              Status {s.lastRunResult.statusCode} · {s.lastRunResult.latencyMs}ms ·{" "}
              {s.lastRunResult.assertionsPassed ? "assertions passed" : "assertions failed"}
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
