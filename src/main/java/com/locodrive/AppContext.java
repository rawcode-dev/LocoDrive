package com.locodrive;

import com.locodrive.model.ServerConfig;
import com.locodrive.server.LocalFileServer;

/**
 * Application-wide singleton context.
 * Holds shared state: current config, running server instance, and primary stage.
 */
public class AppContext {

    private static AppContext instance;

    private ServerConfig config = new ServerConfig();
    private LocalFileServer server;

    private AppContext() {}

    public static AppContext getInstance() {
        if (instance == null) {
            instance = new AppContext();
        }
        return instance;
    }

    // ── Config ────────────────────────────────────────────────────────────────
    public ServerConfig getConfig() { return config; }
    public void setConfig(ServerConfig c) { this.config = c; }

    // ── Server ────────────────────────────────────────────────────────────────
    public LocalFileServer getServer() { return server; }
    public void setServer(LocalFileServer s) { this.server = s; }
}
