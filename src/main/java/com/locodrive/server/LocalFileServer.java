package com.locodrive.server;

import com.locodrive.model.ServerConfig;
import com.locodrive.model.SharedFolder;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * The embedded HTTP file server.
 * Starts/stops on a background thread-pool executor.
 * Reports connection log entries via a callback for the Dashboard UI.
 */
public class LocalFileServer {

    private HttpServer httpServer;
    private final ServerConfig config;
    private final SessionStore sessionStore = new SessionStore();
    private final List<String> accessLog = new CopyOnWriteArrayList<>();
    private Consumer<String> logListener;

    public LocalFileServer(ServerConfig config) {
        this.config = config;
    }

    /**
     * Starts the server. Binds to config.bindAddress:config.port.
     * @throws IOException if the port is in use or binding fails.
     */
    public void start() throws IOException {
        InetSocketAddress addr = new InetSocketAddress(config.getBindAddress(), config.getPort());
        httpServer = HttpServer.create(addr, 50);

        // ── Route: Login page ──────────────────────────────────────────────────
        AuthHandler authHandler = new AuthHandler(config, sessionStore, this::logAccess);
        httpServer.createContext("/login", authHandler);
        httpServer.createContext("/logout", authHandler);

        // ── Route: File browser / download ─────────────────────────────────────
        FileHandler fileHandler = new FileHandler(config, sessionStore, this::logAccess);
        httpServer.createContext("/browse", fileHandler);

        // ── Route: API for dashboard ───────────────────────────────────────────
        ApiHandler apiHandler = new ApiHandler(this);
        httpServer.createContext("/api", apiHandler);

        // ── Route: Root redirect ───────────────────────────────────────────────
        httpServer.createContext("/", exchange -> {
            String location = "/browse/";
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        // Use a cached thread pool for concurrency
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();

        logAccess("🟢 Server started on " + config.getBindAddress() + ":" + config.getPort());
    }

    /**
     * Stops the server gracefully (gives active requests 2 seconds to finish).
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(2);
            httpServer = null;
            logAccess("🔴 Server stopped.");
        }
    }

    public boolean isRunning() {
        return httpServer != null;
    }

    // ── Log ───────────────────────────────────────────────────────────────────
    public void logAccess(String entry) {
        String ts = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String line = "[" + ts + "] " + entry;
        accessLog.add(line);
        if (accessLog.size() > 500) accessLog.remove(0); // cap log
        if (logListener != null) logListener.accept(line);
    }

    public List<String> getAccessLog() { return List.copyOf(accessLog); }

    public void setLogListener(Consumer<String> listener) {
        this.logListener = listener;
    }

    public int getActiveSessionCount() {
        return sessionStore.activeCount();
    }

    public ServerConfig getConfig() { return config; }

    public SessionStore getSessionStore() { return sessionStore; }
}
