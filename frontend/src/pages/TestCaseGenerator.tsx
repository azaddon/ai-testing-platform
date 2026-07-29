import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api/client";

interface TestCase {
  id: string;
  title: string;
  description: string;
  type: string;
  priority: string;
  status: string;
  steps: { action: string; expected: string }[];
}

export default function TestCaseGenerator({ projectId }: { projectId: string }) {
  const navigate = useNavigate();
  const [requirementText, setRequirementText] = useState("");
  const [count, setCount] = useState(5);
  const [testTypes, setTestTypes] = useState<string[]>(["functional", "edge", "negative"]);
  const [targetUrl, setTargetUrl] = useState("http://host.docker.internal:5173");
  const [testCases, setTestCases] = useState<TestCase[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingExisting, setLoadingExisting] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // per-row transient state for the Run action
  const [rowBusy, setRowBusy] = useState<Record<string, boolean>>({});
  const [rowError, setRowError] = useState<Record<string, string | null>>({});

  // Load previously generated test cases for this project on mount (and whenever the
  // project changes) — without this, a refresh shows an empty list even though
  // everything is still in Mongo, since generate() only ever set local state.
  useEffect(() => {
    let cancelled = false;
    setLoadingExisting(true);
    api
      .listTestCases(projectId)
      .then((existing) => {
        if (!cancelled) setTestCases(existing as TestCase[]);
      })
      .catch((e: any) => {
        if (!cancelled) setError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoadingExisting(false);
      });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  async function handleGenerate() {
    setLoading(true);
    setError(null);
    try {
      const generated = (await api.generateTestCases(projectId, { requirementText, testTypes, count })) as TestCase[];
      // Append to whatever's already loaded rather than replacing it, so a second
      // "Generate" call builds up the suite instead of hiding earlier results.
      setTestCases((prev) => [...prev, ...generated]);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleApprove(id: string) {
    await api.approveTestCase(id, projectId);
    setTestCases((prev) => prev.map((tc) => (tc.id === id ? { ...tc, status: "approved" } : tc)));
  }

  async function handleRun(testCaseId: string) {
    setRowBusy((prev) => ({ ...prev, [testCaseId]: true }));
    setRowError((prev) => ({ ...prev, [testCaseId]: null }));
    try {
      const run = (await api.runTestCase(projectId, testCaseId, targetUrl)) as { id: string };
      // The run starts async on the backend (queued -> running -> passed/failed); jump to
      // its detail page, which already polls/subscribes over the WebSocket for live status.
      navigate(`/test-runs/${run.id}`);
    } catch (e: any) {
      setRowError((prev) => ({ ...prev, [testCaseId]: e.message }));
    } finally {
      setRowBusy((prev) => ({ ...prev, [testCaseId]: false }));
    }
  }

  return (
    <div>
      <h2>AI Test Case Generation</h2>

      <textarea
        placeholder="Paste a requirement, user story, or ticket description..."
        value={requirementText}
        onChange={(e) => setRequirementText(e.target.value)}
        rows={6}
        style={{ width: "100%", padding: 8, marginBottom: 12 }}
      />

      <div style={{ display: "flex", gap: 16, alignItems: "center", marginBottom: 12, flexWrap: "wrap" }}>
        <label>
          Count:{" "}
          <input type="number" value={count} min={1} max={20} onChange={(e) => setCount(Number(e.target.value))} />
        </label>

        {["functional", "edge", "negative", "regression"].map((type) => (
          <label key={type}>
            <input
              type="checkbox"
              checked={testTypes.includes(type)}
              onChange={(e) =>
                setTestTypes((prev) => (e.target.checked ? [...prev, type] : prev.filter((t) => t !== type)))
              }
            />{" "}
            {type}
          </label>
        ))}

        <button onClick={handleGenerate} disabled={loading || !requirementText}>
          {loading ? "Generating..." : "Generate test cases"}
        </button>
      </div>

      <div style={{ marginBottom: 16 }}>
        <label>
          Target URL (for Run):{" "}
          <input
            value={targetUrl}
            onChange={(e) => setTargetUrl(e.target.value)}
            style={{ width: 260 }}
            placeholder="http://localhost:5173"
          />
        </label>
      </div>

      {error && <p style={{ color: "crimson" }}>{error}</p>}
      {loadingExisting && <p style={{ color: "#888" }}>Loading existing test cases...</p>}

      {testCases.map((tc) => {
        const busy = !!rowBusy[tc.id];
        const runError = rowError[tc.id];
        const approved = tc.status === "approved";

        return (
          <div key={tc.id} style={{ border: "1px solid #ddd", borderRadius: 8, padding: 16, marginBottom: 12 }}>
            <div style={{ display: "flex", justifyContent: "space-between" }}>
              <strong>{tc.title}</strong>
              <span style={{ fontSize: 12, color: "#888" }}>
                {tc.type} · {tc.priority} · {tc.status}
              </span>
            </div>
            <p style={{ color: "#444" }}>{tc.description}</p>
            <ol>
              {tc.steps?.map((s, i) => (
                <li key={i}>
                  {s.action} → <em>{s.expected}</em>
                </li>
              ))}
            </ol>

            <div style={{ display: "flex", gap: 8 }}>
              {!approved && <button onClick={() => handleApprove(tc.id)}>Approve</button>}
              <button
                onClick={() => handleRun(tc.id)}
                disabled={!approved || busy}
                title={approved ? undefined : "Approve this test case first"}
              >
                {busy ? "Starting run..." : "Run"}
              </button>
            </div>

            {runError && <p style={{ color: "crimson", fontSize: 13, marginTop: 8 }}>{runError}</p>}
          </div>
        );
      })}

      {!loadingExisting && testCases.length === 0 && (
        <p style={{ color: "#888" }}>No test cases yet — generate some above.</p>
      )}
    </div>
  );
}
