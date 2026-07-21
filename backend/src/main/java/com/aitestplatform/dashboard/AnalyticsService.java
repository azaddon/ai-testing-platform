package com.aitestplatform.dashboard;

import com.aitestplatform.execution.TestRun;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.*;

@Service
public class AnalyticsService {

    private final MongoTemplate mongoTemplate;

    public AnalyticsService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Map<String, Object> summary(String projectId) {
        long total = mongoTemplate.count(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("projectId").is(projectId)),
                TestRun.class);

        long passed = countByStatus(projectId, "passed");
        long failed = countByStatus(projectId, "failed");
        long running = countByStatus(projectId, "running");

        double passRate = total == 0 ? 0.0 : (double) passed / total;

        return Map.of(
                "totalRuns", total,
                "passed", passed,
                "failed", failed,
                "running", running,
                "passRate", passRate
        );
    }

    /**
     * Pass/fail trend over a rolling window, bucketed by day. `range` is expressed as
     * "30d" / "7d" style strings for simplicity; parse into a real Duration in production.
     */
    public List<Document> trends(String projectId, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        Aggregation aggregation = newAggregation(
                match(org.springframework.data.mongodb.core.query.Criteria.where("projectId").is(projectId)
                        .and("startedAt").gte(since)),
                project("status")
                        .and("startedAt").dateAsFormattedString("%Y-%m-%d").as("day"),
                group("day", "status").count().as("count"),
                sort(org.springframework.data.domain.Sort.Direction.ASC, "_id.day")
        );

        AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, "testRun", Document.class);
        return results.getMappedResults();
    }

    /** Flakiness score = failures / total runs over the rolling window, per test case. */
    public double flakinessScore(String projectId, String testCaseId, int windowRuns) {
        // Simplified: in a full implementation this would join testRun.results against
        // testCaseId and compute failures/total over the last N runs via aggregation.
        return 0.0;
    }

    private long countByStatus(String projectId, String status) {
        return mongoTemplate.count(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("projectId").is(projectId)
                                .and("status").is(status)),
                TestRun.class);
    }
}
