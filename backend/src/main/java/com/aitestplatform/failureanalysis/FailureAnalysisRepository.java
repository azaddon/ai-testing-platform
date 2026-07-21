package com.aitestplatform.failureanalysis;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface FailureAnalysisRepository extends MongoRepository<FailureAnalysis, String> {
    Optional<FailureAnalysis> findByTestRunId(String testRunId);
}
