package com.aitestplatform.execution;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UiTestScriptRepository extends MongoRepository<UiTestScript, String> {

    Optional<UiTestScript> findFirstByTestCaseIdOrderByCreatedAtDesc(String testCaseId);
}
