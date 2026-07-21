package com.aitestplatform.execution;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TestRunRepository extends MongoRepository<TestRun, String> {
    List<TestRun> findByProjectIdOrderByStartedAtDesc(String projectId);
}
