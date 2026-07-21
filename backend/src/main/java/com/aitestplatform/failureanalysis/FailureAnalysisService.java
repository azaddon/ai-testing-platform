package com.aitestplatform.failureanalysis;

import com.aitestplatform.llm.LlmProvider;
import com.aitestplatform.llm.dto.LlmDtos.FailureAnalysisResult;
import com.aitestplatform.llm.dto.LlmDtos.FailureContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class FailureAnalysisService {

    private final LlmProvider llmProvider;
    private final LogSummarizationService logSummarizationService;
    private final FailureAnalysisRepository repository;

    public FailureAnalysisService(LlmProvider llmProvider,
                                   LogSummarizationService logSummarizationService,
                                   FailureAnalysisRepository repository) {
        this.llmProvider = llmProvider;
        this.logSummarizationService = logSummarizationService;
        this.repository = repository;
    }

    /**
     * Runs (or re-runs) failure analysis for a test run. errorMessage/stackTrace/rawLog/context
     * would normally be pulled from the TestRun's stored artifacts; they're passed in here to
     * keep this service decoupled from where artifacts are persisted (Mongo, GridFS, S3, ...).
     */
    public FailureAnalysis analyze(String testRunId, String errorMessage, String stackTrace,
                                    String rawLog, Map<String, Object> domOrResponseSnapshot) {

        String logSummary = logSummarizationService.summarize(rawLog);

        FailureContext context = new FailureContext(
                testRunId, errorMessage, stackTrace,
                List.of(logSummary), domOrResponseSnapshot);

        FailureAnalysisResult result = llmProvider.analyzeFailure(context);

        FailureAnalysis analysis = repository.findByTestRunId(testRunId).orElse(new FailureAnalysis());
        analysis.setTestRunId(testRunId);
        analysis.setRootCause(result.rootCause());
        analysis.setCategory(result.category());
        analysis.setConfidence(result.confidence());
        analysis.setSuggestedFix(result.suggestedFix());
        analysis.setLogSummary(logSummary);
        analysis.setModelUsed(llmProvider.name());

        return repository.save(analysis);
    }

    public FailureAnalysis get(String testRunId) {
        return repository.findByTestRunId(testRunId)
                .orElseThrow(() -> new IllegalArgumentException("No analysis found for test run: " + testRunId));
    }
}
