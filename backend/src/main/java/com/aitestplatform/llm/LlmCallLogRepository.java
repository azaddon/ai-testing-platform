package com.aitestplatform.llm;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface LlmCallLogRepository extends MongoRepository<LlmCallLog, String> {
}
