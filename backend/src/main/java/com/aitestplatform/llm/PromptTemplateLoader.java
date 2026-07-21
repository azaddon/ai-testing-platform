package com.aitestplatform.llm;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads versioned prompt templates from resources/prompts/*.txt and does simple
 * {{placeholder}} substitution. Keeping prompts out of Java source lets them be
 * iterated without a code change.
 */
@Component
public class PromptTemplateLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String render(String templateName, Map<String, String> variables) {
        String template = cache.computeIfAbsent(templateName, this::load);
        String result = template;
        for (var entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private String load(String templateName) {
        try (InputStream is = new ClassPathResource("prompts/" + templateName).getInputStream()) { 
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load prompt template: " + templateName, e);
        }
    }
}
