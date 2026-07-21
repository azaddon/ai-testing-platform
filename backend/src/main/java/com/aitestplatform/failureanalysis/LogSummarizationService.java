package com.aitestplatform.failureanalysis;

import com.aitestplatform.llm.LlmProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Map-reduce log summarization: chunk a (potentially huge) raw log into fixed-size windows,
 * summarize each chunk independently (map), then summarize the summaries into one paragraph
 * (reduce). Keeps token usage roughly linear in log size instead of needing one giant prompt
 * that would blow the context window on a verbose CI log.
 */
@Service
public class LogSummarizationService {

    private static final int CHUNK_SIZE_CHARS = 8_000;

    private final LlmProvider llmProvider;

    public LogSummarizationService(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String summarize(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) {
            return "No log content available.";
        }

        List<String> chunks = chunk(rawLog, CHUNK_SIZE_CHARS);

        if (chunks.size() == 1) {
            return llmProvider.summarizeLogChunk(chunks.get(0));
        }

        List<String> chunkSummaries = new ArrayList<>();
        for (String chunk : chunks) {
            chunkSummaries.add(llmProvider.summarizeLogChunk(chunk));
        }

        String combined = String.join("\n", chunkSummaries);
        // Reduce step: summarize the summaries. Reuses the same chunk-summary prompt/model
        // since it's already tuned for "condense this text, focus on errors/anomalies".
        return llmProvider.summarizeLogChunk(combined);
    }

    private List<String> chunk(String text, int size) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }
}
