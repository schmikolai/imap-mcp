package io.imapmcp.mcp;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks MCP session lifecycle (initialize → notifications/initialized →
 * ready) in memory. Fine for a single-instance deployment; a horizontally
 * scaled deployment would need this backed by Redis (the same store already
 * used for rate limiting) so any instance can see any session.
 */
@Component
public class McpSessionRegistry {

    public enum State { INITIALIZING, INITIALIZED }

    public record Session(String id, State state, Instant createdAt) {
        Session withState(State newState) {
            return new Session(id, newState, createdAt);
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Session create() {
        Session session = new Session(UUID.randomUUID().toString(), State.INITIALIZING, Instant.now());
        sessions.put(session.id(), session);
        return session;
    }

    public Optional<Session> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public boolean isInitialized(String sessionId) {
        return sessionId != null && get(sessionId).map(s -> s.state() == State.INITIALIZED).orElse(false);
    }

    public void markInitialized(String sessionId) {
        sessions.computeIfPresent(sessionId, (id, session) -> session.withState(State.INITIALIZED));
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}
