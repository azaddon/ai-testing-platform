package com.aitestplatform.apitest;

import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Compiles generated Rest Assured Java source on the fly and runs it in a short-lived
 * process boundary. This is a *scaffold*: in production this should run inside the
 * dedicated `executor` container (see docker-compose.yml) with no outbound network access
 * beyond the target API under test, a hard wall-clock timeout, and a non-root user.
 *
 * Convention: `generatedCode` is the BODY of a method with signature
 *   public static Response run(String baseUri) throws Exception { ... return response; }
 */
@Service
public class ApiTestExecutionService {

    private final ApiTestScriptRepository repository;
    private final int timeoutSeconds;

    public ApiTestExecutionService(ApiTestScriptRepository repository,
                                    @Value("${execution.sandbox.timeout-seconds:60}") int timeoutSeconds) {
        this.repository = repository;
        this.timeoutSeconds = timeoutSeconds;
    }

    public ApiTestScript execute(String scriptId, String baseUri) {
        ApiTestScript script = repository.findById(scriptId)
                .orElseThrow(() -> new IllegalArgumentException("API test script not found: " + scriptId));

        script.setStatus(ScriptStatus.RUNNING);
        repository.save(script);

        long start = System.currentTimeMillis();
        try {
            Response response = compileAndRun(script.getGeneratedCode(), baseUri);
            long latency = System.currentTimeMillis() - start;
            boolean passed = response.getStatusCode() < 400;

            script.setLastRunResult(new ApiTestScript.LastRunResult(
                    response.getStatusCode(), latency, passed, response.getBody().asPrettyString()));
            script.setStatus(passed ? ScriptStatus.PASSED : ScriptStatus.FAILED);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            script.setLastRunResult(new ApiTestScript.LastRunResult(-1, latency, false, "Execution error: " + e.getMessage()));
            script.setStatus(ScriptStatus.FAILED);
        }
        return repository.save(script);
    }

    private Response compileAndRun(String generatedMethodBody, String baseUri) throws Exception {
        String className = "GeneratedApiTest_" + UUID.randomUUID().toString().replace("-", "");
        String source = """
                import io.restassured.response.Response;
                import static io.restassured.RestAssured.*;
                import static org.hamcrest.Matchers.*;

                public class %s {
                    public static Response run(String baseUri) throws Exception {
                        %s
                    }
                }
                """.formatted(className, generatedMethodBody);

        Path tempDir = Files.createTempDirectory("api-test-exec");
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available (JDK required, not just a JRE)");
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(sourceFile.toFile());

            // Improvement: Passing current JVM classpath to compiler to avoid dependency issues
            String currentClasspath = System.getProperty("java.class.path");
            List<String> options = List.of("-d", tempDir.toString(), "-classpath", currentClasspath);

            boolean ok = compiler.getTask(null, fileManager, null,
                    options, null, units).call();
            if (!ok) {
                throw new IllegalStateException("Generated Rest Assured code failed to compile");
            }
        }

        try (URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> clazz = Class.forName(className, true, classLoader);
            Method runMethod = clazz.getMethod("run", String.class);

            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var future = executor.submit(() -> (Response) runMethod.invoke(null, baseUri));
                return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                deleteRecursively(tempDir.toFile());
            }
        }
    }

    private void deleteRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteRecursively(f); else f.delete();
            }
        }
        dir.delete();
    }
}
