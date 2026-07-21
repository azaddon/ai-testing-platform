package com.aitestplatform.testcase;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TestCaseRepository extends MongoRepository<TestCase, String> {
    List<TestCase> findByProjectId(String projectId);
    List<TestCase> findByProjectIdAndStatus(String projectId, String status);
}
