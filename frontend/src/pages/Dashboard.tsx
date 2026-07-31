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

// One row per day, e.g. {day: "2026-07-30", passed: 5, failed: 2} — matches
// AnalyticsService.trends()'s reshaped output (one column per status that occurred that day).
interface TrendRow {
  day: string;
  [status: string]: string | number;
}

const STATUS_COLORS: Record<string, string> = {
  passed: "#1a7f37",
  failed: "#cf222e",
  running: "#9a6700",
  queued: "#57606a",
  flaky: "#bf8700"
};
const STATUS_ORDER = ["passed", "failed", "running", "queued", "flaky"];

export default function Dashboard({ projectId }: { projectId: string }) {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [runs, setRuns] = useState<TestRun[]>([]);
  const [trends, setTrends] = useState<TrendRow[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const [summaryData, runsData, trendsData] = await Promise.all([
          api.dashboardSummary(projectId) as Promise<Summary>,
          api.listTestRuns(projectId) as Promise<TestRun[]>,
          api.trends(projectId, 30) as Promise<TrendRow[]>
        ]);
        if (!cancelled) {
          setSummary(summaryData);
          setRuns(runsData);
          setTrends(trendsData);
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

      <h3>Trend (last 30 days)</h3>
      <div style={{ marginBottom: 24 }}>
        <TrendChart data={trends} />
      </div>

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
  return <span style={{ color: STATUS_COLORS[status] ?? "#57606a", fontWeight: 600 }}>{status}</span>;
}

// Dependency-free stacked bar chart (plain inline SVG) — the app has no chart library
// installed, and this is a small enough visualization not to warrant adding one. Each bar
// is one day; segments stack passed/failed/running/etc. on top of each other, tallest
// possible bar scaled to the day with the most total runs in the window.
function TrendChart({ data }: { data: TrendRow[] }) {
  if (data.length === 0) {
    return <p style={{ color: "#888", fontSize: 13 }}>No runs in this window yet — the trend will appear once some exist.</p>;
  }

  const chartHeight = 140;
  const barGap = 6;
  const barWidth = 28;
  const chartWidth = data.length * (barWidth + barGap);
  const maxTotal = Math.max(
    1,
    ...data.map((d) => STATUS_ORDER.reduce((sum, s) => sum + (Number(d[s]) || 0), 0))
  );

  return (
    <div>
      <div style={{ overflowX: "auto" }}>
        <svg width={chartWidth} height={chartHeight + 24} style={{ display: "block" }}>
          {data.map((d, i) => {
            const x = i * (barWidth + barGap);
            let yOffset = chartHeight;
            const bars = STATUS_ORDER.map((status) => {
              const count = Number(d[status]) || 0;
              if (count === 0) return null;
              const barHeight = (count / maxTotal) * chartHeight;
              yOffset -= barHeight;
              return (
                <rect key={status} x={x} y={yOffset} width={barWidth} height={barHeight}
                      fill={STATUS_COLORS[status] ?? "#57606a"}>
                  <title>{`${d.day} — ${status}: ${count}`}</title>
                </rect>
              );
            });
            return (
              <g key={d.day}>
                {bars}
                <text x={x + barWidth / 2} y={chartHeight + 16} textAnchor="middle" fontSize="9" fill="#888">
                  {d.day.slice(5)}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      <div style={{ display: "flex", gap: 16, fontSize: 12, color: "#555", marginTop: 8 }}>
        {STATUS_ORDER.map((status) => (
          <span key={status} style={{ display: "flex", alignItems: "center", gap: 4 }}>
            <span style={{ width: 10, height: 10, background: STATUS_COLORS[status], display: "inline-block", borderRadius: 2 }} />
            {status}
          </span>
        ))}
      </div>
    </div>
  );
}
