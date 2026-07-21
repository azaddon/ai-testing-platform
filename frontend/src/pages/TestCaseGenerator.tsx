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
  const [testCases, setTestCases] = useState<TestCase[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [targetUrl, setTargetUrl] = useState("http://localhost:3000");
  const [runningId, setRunningId] = useState<string | null>(null);

  // Reload previously generated/approved test cases so they survive a page refresh
  // instead of only ever showing the most recent "Generate" batch.
  useEffect(() => {
    let cancelled = false;
    api
      .listTestCases(projectId)
      .then((existing) => {
        if (!cancelled) setTestCases(existing as TestCase[]);
      })
      .catch((e: any) => {
        if (!cancelled) setError(e.message);
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
      setTestCases((prev) => [...generated, ...prev]);
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

  async function handleRun(id: string) {
    setRunningId(id);
    setError(null);
    try {
      const run = (await api.runTestCase(projectId, id, targetUrl)) as { id: string };
      navigate(`/test-runs/${run.id}`);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setRunningId(null);
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

      <div style={{ display: "flex", gap: 16, alignItems: "center", marginBottom: 16 }}>
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

      <div style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 16 }}>
        <label>
          Target URL to run against:{" "}
          <input value={targetUrl} onChange={(e) => setTargetUrl(e.target.value)} style={{ width: 260 }} />
        </label>
      </div>

      {error && <p style={{ color: "crimson" }}>{error}</p>}

      {testCases.map((tc) => (
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
          <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
            {tc.status !== "approved" && <button onClick={() => handleApprove(tc.id)}>Approve</button>}
            {tc.status === "approved" && (
              <button onClick={() => handleRun(tc.id)} disabled={runningId === tc.id}>
                {runningId === tc.id ? "Starting run..." : "Run"}
              </button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
