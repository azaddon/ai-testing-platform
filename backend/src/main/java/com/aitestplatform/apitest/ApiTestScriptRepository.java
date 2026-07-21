package com.aitestplatform.apitest;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ApiTestScriptRepository extends MongoRepository<ApiTestScript, String> {
    List<ApiTestScript> findByProjectId(String projectId);
}
