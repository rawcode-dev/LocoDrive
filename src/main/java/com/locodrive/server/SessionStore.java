package com.locodrive.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session store.
 * Maps session token (UUID string) → username.
 * Sessions expire after 8 hours of inactivity.
 */
public class SessionStore {

    private static final long SESSION_TIMEOUT_MS = 8L * 60 * 60 * 1000; // 8 hours

    private static class Session {
        final String username;
        long lastAccess;

        Session(String username) {
            this.username = username;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Creates a new session for the given username. Returns the session token. */
    public String create(String username) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(username));
        return token;
    }

    /**
     * Looks up the username for a token. Returns null if not found or expired.
     * Touch the session on valid access.
     */
    public String getUsername(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (now - s.lastAccess > SESSION_TIMEOUT_MS) {
            sessions.remove(token);
            return null;
        }
        s.lastAccess = now;
        return s.username;
    }

    /** Invalidates a session (logout). */
    public void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Returns the number of currently active (non-expired) sessions. */
    public int activeCount() {
        long now = System.currentTimeMillis();
        return (int) sessions.values().stream()
            .filter(s -> now - s.lastAccess <= SESSION_TIMEOUT_MS)
            .count();
    }

    /** Extracts the session token from the Cookie header, or null. */
    public static String extractToken(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) return null;
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("LDSESSION=")) {
                return trimmed.substring("LDSESSION=".length()).trim();
            }
        }
        return null;
    }
}
