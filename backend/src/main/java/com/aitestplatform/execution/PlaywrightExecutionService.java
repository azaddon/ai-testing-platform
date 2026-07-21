package com.aitestplatform.execution;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs a single UI test step (Playwright for Java) inside the backend JVM for the MVP.
 * In the docker-compose topology this responsibility moves to the `executor` container
 * (see docker-compose.yml) so a hung browser can't affect the API process.
 */
@Service
public class PlaywrightExecutionService {

    private final int timeoutSeconds;

    public PlaywrightExecutionService(@Value("${execution.sandbox.timeout-seconds:60}") int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public record UiRunResult(boolean passed, String errorMessage, String screenshotBase64) {}

    public UiRunResult run(UiTestScript script) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.navigate(script.getTargetUrl());
                invokeGeneratedScript(script.getGeneratedCode(), page);
                return new UiRunResult(true, null, captureScreenshot(page));
            } catch (Exception e) {
                Page page = browser.contexts().isEmpty() ? null
                        : browser.contexts().get(0).pages().isEmpty() ? null
                        : browser.contexts().get(0).pages().get(0);
                String screenshot = page != null ? captureScreenshot(page) : null;
                return new UiRunResult(false, e.getMessage(), screenshot);
            } finally {
                browser.close();
            }
        }
    }

    private String captureScreenshot(Page page) {
        try {
            byte[] bytes = page.screenshot();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private void invokeGeneratedScript(String generatedMethodBody, Page page) throws Exception {
        String className = "GeneratedUiTest_" + UUID.randomUUID().toString().replace("-", "");
        String source = """
                import com.microsoft.playwright.Page;
                import com.microsoft.playwright.options.*;

                public class %s {
                    public static void run(Page page) throws Exception {
                        %s
                    }
                }
                """.formatted(className, generatedMethodBody);

        Path tempDir = Files.createTempDirectory("ui-test-exec");
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available (JDK required, not just a JRE)");
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            var units = fileManager.getJavaFileObjects(sourceFile.toFile());
            boolean ok = compiler.getTask(null, fileManager, null,
                    List.of("-d", tempDir.toString()), null, units).call();
            if (!ok) {
                throw new IllegalStateException("Generated Playwright code failed to compile");
            }
        }

        try (URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> clazz = Class.forName(className, true, classLoader);
            Method runMethod = clazz.getMethod("run", Page.class);

            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var future = executor.submit(() -> { runMethod.invoke(null, page); return null; });
                future.get(timeoutSeconds, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                deleteRecursively(tempDir.toFile());
            }
        }
    }

    private void deleteRecursively(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) deleteRecursively(f); else f.delete();
            }
        }
        dir.delete();
    }
}
