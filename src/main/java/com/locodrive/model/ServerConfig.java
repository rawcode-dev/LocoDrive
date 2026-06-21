package com.locodrive.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level configuration object for the server.
 * Serialised to / from ~/.locodrive/config.json by ConfigManager.
 */
public class ServerConfig {

    private String bindAddress = "";   // LAN IP to bind to, e.g. "192.168.1.10"
    private int port = 8080;
    private boolean guestEnabled = false; // Master switch — guest access is per-folder
    private List<User> users = new ArrayList<>();
    private List<SharedFolder> sharedFolders = new ArrayList<>();

    public ServerConfig() {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Full server URL, e.g. http://192.168.1.10:8080 */
    public String getServerUrl() {
        return "http://" + bindAddress + ":" + port;
    }

    /** Returns true if there is at least one admin user configured. */
    public boolean hasAdmin() {
        return users.stream().anyMatch(User::isAdmin);
    }

    /** Returns user by username, or null. */
    public User findUser(String username) {
        return users.stream()
            .filter(u -> u.getUsername().equalsIgnoreCase(username))
            .findFirst().orElse(null);
    }

    /** Returns shared folder by alias, or null. */
    public SharedFolder findFolder(String alias) {
        return sharedFolders.stream()
            .filter(f -> f.getSafeAlias().equalsIgnoreCase(alias))
            .findFirst().orElse(null);
    }

    /** Returns true if this config is valid enough to start the server. */
    public boolean isReady() {
        return !bindAddress.isBlank()
            && port > 0 && port <= 65535
            && !sharedFolders.isEmpty()
            && sharedFolders.stream().allMatch(SharedFolder::isValid)
            && hasAdmin();
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isGuestEnabled() { return guestEnabled; }
    public void setGuestEnabled(boolean guestEnabled) { this.guestEnabled = guestEnabled; }

    public List<User> getUsers() { return users; }
    public void setUsers(List<User> users) { this.users = users; }

    public List<SharedFolder> getSharedFolders() { return sharedFolders; }
    public void setSharedFolders(List<SharedFolder> sharedFolders) { this.sharedFolders = sharedFolders; }
}
