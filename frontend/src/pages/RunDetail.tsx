import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api, openTestRunSocket } from "../api/client";

interface StepResult {
  testCaseId: string;
  status: string;
  durationMs: number;
  errorMessage?: string;
}

interface TestRun {
  id: string;
  status: string;
  type: string;
  results: StepResult[];
}

interface FailureAnalysis {
  rootCause: string;
  category: string;
  confidence: number;
  suggestedFix: string;
  logSummary: string;
}

export default function RunDetail() {
  const { runId } = useParams<{ runId: string }>();
  const [run, setRun] = useState<TestRun | null>(null);
  const [analysis, setAnalysis] = useState<FailureAnalysis | null>(null);

  useEffect(() => {
    if (!runId) return;

    api.getTestRun(runId).then(setRun as any);

    // Live updates while the run is in progress; falls back to the initial fetch above
    // once the run finishes and the socket closes.
    const socket = openTestRunSocket(runId, (data) => setRun(data));
    return () => socket.close();
  }, [runId]);

  useEffect(() => {
    if (run?.status === "failed" && runId) {
      api.getAnalysis(runId).then(setAnalysis as any).catch(() => setAnalysis(null));
    }
  }, [run?.status, runId]);

  if (!run) return <p>Loading run...</p>;

  return (
    <div>
      <h2>Test Run {run.id}</h2>
      <p>
        Type: {run.type} · Status: <strong>{run.status}</strong>
      </p>

      <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: 24 }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>
            <th>Test case</th>
            <th>Status</th>
            <th>Duration</th>
            <th>Error</th>
          </tr>
        </thead>
        <tbody>
          {run.results?.map((r, i) => (
            <tr key={i} style={{ borderBottom: "1px solid #eee" }}>
              <td>{r.testCaseId}</td>
              <td>{r.status}</td>
              <td>{r.durationMs}ms</td>
              <td style={{ color: "crimson" }}>{r.errorMessage}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {analysis && (
        <div style={{ border: "1px solid #ddd", borderRadius: 8, padding: 16 }}>
          <h3>AI Failure Analysis</h3>
          <p>
            <strong>Category:</strong> {analysis.category} ({Math.round(analysis.confidence * 100)}% confidence)
          </p>
          <p>
            <strong>Root cause:</strong> {analysis.rootCause}
          </p>
          <p>
            <strong>Suggested fix:</strong> {analysis.suggestedFix}
          </p>
          <details>
            <summary>Log summary</summary>
            <p>{analysis.logSummary}</p>
          </details>
        </div>
      )}
    </div>
  );
}
