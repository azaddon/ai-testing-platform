package com.aitestplatform.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class LlmProviderConfig {

    @Bean
    public WebClient geminiWebClient(@Value("${llm.gemini.base-url}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Bean
    public WebClient openAiWebClient(@Value("${llm.openai.base-url}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * Selects the active provider at startup based on `llm.provider` (default: openai,
     * i.e. Groq). Everything downstream depends on the LlmProvider interface only.
     *
     * @Primary here (and NOT on GeminiProvider/OpenAiProvider themselves) is what makes
     * every plain `LlmProvider` injection point in the app actually go through this
     * switch instead of ambiguously resolving straight to one concrete implementation.
     */
    @Bean
    @Primary
    public LlmProvider activeLlmProvider(@Value("${llm.provider:openai}") String providerName,
                                          GeminiProvider geminiProvider,
                                          OpenAiProvider openAiProvider) {
        return "gemini".equalsIgnoreCase(providerName) ? geminiProvider : openAiProvider;
    }
}
