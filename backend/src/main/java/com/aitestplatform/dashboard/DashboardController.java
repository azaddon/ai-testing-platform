package com.aitestplatform.dashboard;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    private final AnalyticsService analyticsService;

    public DashboardController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/projects/{projectId}/dashboard/summary")
    public Map<String, Object> summary(@PathVariable String projectId) {
        return analyticsService.summary(projectId);
    }

    /** One row per day, e.g. {"day":"2026-07-30","passed":5,"failed":2} — ready to chart directly. */
    @GetMapping("/projects/{projectId}/analytics/trends")
    public List<Map<String, Object>> trends(@PathVariable String projectId,
                                             @RequestParam(defaultValue = "30") int days) {
        return analyticsService.trends(projectId, days);
    }
}
