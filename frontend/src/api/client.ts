const API_BASE = "/api/v1";

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`${res.status} ${res.statusText}: ${body}`);
  }
  return res.json() as Promise<T>;
}

export const api = {
  generateTestCases: (projectId: string, body: { requirementText: string; testTypes: string[]; count: number }) =>
    request(`/projects/${projectId}/test-cases/generate`, { method: "POST", body: JSON.stringify(body) }),

  listTestCases: (projectId: string, status?: string) =>
    request(`/projects/${projectId}/test-cases${status ? `?status=${status}` : ""}`),

  approveTestCase: (testCaseId: string, projectId: string) =>
    request(`/projects/${projectId}/test-cases/${testCaseId}/approve`, { method: "PUT" }),

  runTestCase: (projectId: string, testCaseId: string, targetUrl: string) =>
    request(`/projects/${projectId}/test-cases/${testCaseId}/run`, {
      method: "POST",
      body: JSON.stringify({ targetUrl })
    }),

  generateApiTests: (projectId: string, body: { openApiSpec: string; endpointFilters: string[] }) =>
    request(`/projects/${projectId}/api-tests/generate`, { method: "POST", body: JSON.stringify(body) }),

  listApiTests: (projectId: string) => request(`/projects/${projectId}/api-tests`),

  executeApiTest: (scriptId: string, baseUri: string) =>
    request(`/api-tests/${scriptId}/execute`, { method: "POST", body: JSON.stringify({ baseUri }) }),

  getTestRun: (runId: string) => request(`/test-runs/${runId}`),

  listTestRuns: (projectId: string) => request(`/projects/${projectId}/test-runs`),

  rerunFailedTests: (runId: string) => request(`/test-runs/${runId}/rerun-failed`, { method: "POST" }),

  analyzeFailure: (
    runId: string,
    body: { errorMessage: string; stackTrace: string; rawLog: string; domOrResponseSnapshot: Record<string, unknown> }
  ) => request(`/test-runs/${runId}/analyze`, { method: "POST", body: JSON.stringify(body) }),

  getAnalysis: (runId: string) => request(`/test-runs/${runId}/analysis`),

  dashboardSummary: (projectId: string) => request(`/projects/${projectId}/dashboard/summary`),

  trends: (projectId: string, days = 30) => request(`/projects/${projectId}/analytics/trends?days=${days}`)
};

export function openTestRunSocket(runId: string, onMessage: (data: any) => void): WebSocket {
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(`${protocol}://${window.location.host}/ws/test-runs/${runId}`);
  socket.onmessage = (event) => {
    try {
      onMessage(JSON.parse(event.data));
    } catch {
      // ignore malformed frames
    }
  };
  return socket;
}
