package com.aitestplatform.config;

import com.aitestplatform.apitest.ScriptStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.util.List;

/**
 * Registers a lenient reader for ScriptStatus so that documents written under an earlier
 * version of the enum (e.g. the old GENERATED constant, before it split into
 * SCENARIO_GENERATED / CODE_GENERATED) don't throw "No enum constant ..." and take down
 * the whole /api-tests list endpoint. Unrecognized values map to SCENARIO_GENERATED
 * (the safest default: it just means "generate code" is enabled again for that row)
 * instead of failing to load the document at all.
 */
@Configuration
public class MongoConversionConfig {

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(new StringToScriptStatusConverter()));
    }

    @ReadingConverter
    static class StringToScriptStatusConverter implements Converter<String, ScriptStatus> {
        @Override
        public ScriptStatus convert(String source) {
            for (ScriptStatus candidate : ScriptStatus.values()) {
                if (candidate.name().equalsIgnoreCase(source) || candidate.getStatus().equalsIgnoreCase(source)) {
                    return candidate;
                }
            }
            // Legacy/unknown value (e.g. old "GENERATED") — don't crash the list endpoint,
            // just treat it as not-yet-code-generated.
            return ScriptStatus.SCENARIO_GENERATED;
        }
    }
}
