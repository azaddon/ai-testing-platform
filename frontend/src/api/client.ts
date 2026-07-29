const API_BASE = "/api/v1";

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options
  });
  if (!res.ok) {
    // Backend returns {"message": "..."} (or "error") via GlobalExceptionHandler for
    // known workflow errors — surface that instead of a raw status code where possible.
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      message = body.message || body.error || message;
    } catch {
      // response wasn't JSON; fall back to the status text above
    }
    throw new Error(message);
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

  // Bridges an approved TestCase to an actual run: resolves each step's locator via the
  // LLM, assembles a UiTestScript, and starts a TestRun against targetUrl. Returns the
  // TestRun (status starts "queued"/"running") — follow up via getTestRun / the WebSocket.
  runTestCase: (projectId: string, testCaseId: string, targetUrl: string) =>
    request(`/projects/${projectId}/test-cases/${testCaseId}/run`, {
      method: "POST",
      body: JSON.stringify({ targetUrl })
    }),

  // Step 1: scenarios only, no code
  generateApiTestScenarios: (
    projectId: string,
    body: { openApiSpec: string; endpointFilters: string[]; count: number }
  ) => request(`/projects/${projectId}/api-tests/generate-scenarios`, { method: "POST", body: JSON.stringify(body) }),

  // Step 2: code for one scenario. Throws (with a friendly message) if a scenario doesn't
  // exist (404) or already has code (409).
  generateApiTestCode: (scriptId: string) =>
    request(`/api-tests/${scriptId}/generate-code`, { method: "POST" }),

  listApiTests: (projectId: string) => request(`/projects/${projectId}/api-tests`),

  // Step 3: run one scenario. Throws (with a friendly message) if no code exists yet (400).
  executeApiTest: (scriptId: string, baseUri: string) =>
    request(`/api-tests/${scriptId}/execute`, { method: "POST", body: JSON.stringify({ baseUri }) }),

  executeAllApiTests: (projectId: string, baseUri: string) =>
    request(`/projects/${projectId}/api-tests/execute-all`, { method: "POST", body: JSON.stringify({ baseUri }) }),

  getTestRun: (runId: string) => request(`/test-runs/${runId}`),

  listTestRuns: (projectId: string) => request(`/projects/${projectId}/test-runs`),

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
