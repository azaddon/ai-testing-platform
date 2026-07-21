package com.aitestplatform.config;

import com.aitestplatform.execution.TestRunWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TestRunWebSocketHandler testRunWebSocketHandler;

    public WebSocketConfig(TestRunWebSocketHandler testRunWebSocketHandler) {
        this.testRunWebSocketHandler = testRunWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(testRunWebSocketHandler, "/ws/test-runs/{runId}")
                .setAllowedOrigins("*");
    }

    @Bean
    public org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        return container;
    }
}
