import { useEffect, useState } from "react";
import { api } from "../api/client";

interface Summary {
  totalRuns: number;
  passed: number;
  failed: number;
  running: number;
  passRate: number;
}

interface TestRun {
  id: string;
  status: string;
  type: string;
  startedAt: string;
}

export default function Dashboard({ projectId }: { projectId: string }) {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [runs, setRuns] = useState<TestRun[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const [summaryData, runsData] = await Promise.all([
          api.dashboardSummary(projectId) as Promise<Summary>,
          api.listTestRuns(projectId) as Promise<TestRun[]>
        ]);
        if (!cancelled) {
          setSummary(summaryData);
          setRuns(runsData);
          setError(null);
        }
      } catch (e: any) {
        if (!cancelled) setError(e.message);
      }
    }

    load();
    const interval = setInterval(load, 5000); // simple polling refresh; swap for the WS feed per-run
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [projectId]);

  if (error) return <p style={{ color: "crimson" }}>Failed to load dashboard: {error}</p>;

  return (
    <div>
      <h2>Test Execution Dashboard</h2>

      {summary && (
        <div style={{ display: "flex", gap: 16, marginBottom: 24 }}>
          <StatCard label="Total runs" value={summary.totalRuns} />
          <StatCard label="Passed" value={summary.passed} />
          <StatCard label="Failed" value={summary.failed} />
          <StatCard label="Running" value={summary.running} />
          <StatCard label="Pass rate" value={`${Math.round(summary.passRate * 100)}%`} />
        </div>
      )}

      <h3>Recent runs</h3>
      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>
            <th>Run ID</th>
            <th>Type</th>
            <th>Status</th>
            <th>Started</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => (
            <tr key={run.id} style={{ borderBottom: "1px solid #eee" }}>
              <td>
                <a href={`/test-runs/${run.id}`}>{run.id}</a>
              </td>
              <td>{run.type}</td>
              <td>
                <StatusBadge status={run.status} />
              </td>
              <td>{new Date(run.startedAt).toLocaleString()}</td>
            </tr>
          ))}
          {runs.length === 0 && (
            <tr>
              <td colSpan={4} style={{ padding: 12, color: "#888" }}>
                No test runs yet for this project.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: string | number }) {
  return (
    <div style={{ border: "1px solid #ddd", borderRadius: 8, padding: 16, minWidth: 100 }}>
      <div style={{ fontSize: 12, color: "#888" }}>{label}</div>
      <div style={{ fontSize: 24, fontWeight: 600 }}>{value}</div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const colors: Record<string, string> = {
    passed: "#1a7f37",
    failed: "#cf222e",
    running: "#9a6700",
    queued: "#57606a",
    flaky: "#bf8700"
  };
  return <span style={{ color: colors[status] ?? "#57606a", fontWeight: 600 }}>{status}</span>;
}
