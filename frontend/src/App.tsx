import { useState } from "react";
import { BrowserRouter, Routes, Route, NavLink } from "react-router-dom";
import Dashboard from "./pages/Dashboard";
import TestCaseGenerator from "./pages/TestCaseGenerator";
import ApiTestGenerator from "./pages/ApiTestGenerator";
import RunDetail from "./pages/RunDetail";

const PROJECT_ID_STORAGE_KEY = "aitp_project_id";

export default function App() {
  const [projectId, setProjectId] = useState(
    () => new URLSearchParams(window.location.search).get("projectId") || "demo-project"
  );

  return (
    <BrowserRouter>
      <div style={{ fontFamily: "system-ui, sans-serif", maxWidth: 1100, margin: "0 auto", padding: 24 }}>
        <header style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 24 }}>
          <h1 style={{ fontSize: 20 }}>AI Testing Platform</h1>
          <label>
            Project ID:{" "}
            <input value={projectId} onChange={(e) => setProjectId(e.target.value)} style={{ padding: 4 }} />
          </label>
        </header>

        <nav style={{ display: "flex", gap: 16, marginBottom: 24, borderBottom: "1px solid #ddd", paddingBottom: 8 }}>
          <NavLink to="/">Dashboard</NavLink>
          <NavLink to="/test-cases">Test Case Generator</NavLink>
          <NavLink to="/api-tests">API Test Generator</NavLink>
        </nav>

        <Routes>
          <Route path="/" element={<Dashboard projectId={projectId} />} />
          <Route path="/test-cases" element={<TestCaseGenerator projectId={projectId} />} />
          <Route path="/api-tests" element={<ApiTestGenerator projectId={projectId} />} />
          <Route path="/test-runs/:runId" element={<RunDetail />} />
        </Routes>
      </div>
    </BrowserRouter>
  );
}
