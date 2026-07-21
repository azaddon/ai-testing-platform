package com.aitestplatform.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Streams live status updates for a given testRunId to any subscribed dashboard client.
 * ExecutionEngine calls broadcast(runId, payload) as a run progresses; the frontend opens
 * one socket per run at /ws/test-runs/{runId}.
 */
@Component
public class TestRunWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, CopyOnWriteArraySet<WebSocketSession>> sessionsByRunId = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String runId = extractRunId(session);
        sessionsByRunId.computeIfAbsent(runId, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String runId = extractRunId(session);
        var set = sessionsByRunId.get(runId);
        if (set != null) set.remove(session);
    }

    public void broadcast(String runId, Object payload) {
        var set = sessionsByRunId.get(runId);
        if (set == null || set.isEmpty()) return;
        try {
            String json = objectMapper.writeValueAsString(payload);
            for (WebSocketSession session : set) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            // best-effort broadcast; a dropped update is fixed by the next one or a page reload
        }
    }

    private String extractRunId(WebSocketSession session) {
        String uri = session.getUri() == null ? "" : session.getUri().getPath();
        String[] parts = uri.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : "unknown";
    }
}
