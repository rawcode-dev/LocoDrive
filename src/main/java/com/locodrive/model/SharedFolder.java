package com.locodrive.model;

import java.io.File;

/**
 * Represents a folder shared via the server.
 * Each folder has an alias (URL-safe name), a physical path,
 * and flags for guest access and read-only mode.
 */
public class SharedFolder {

    private String alias;       // URL-friendly name, e.g. "Documents"
    private String path;        // Absolute path on disk
    private boolean guestAccessible;  // Can guests browse without login?
    private boolean readOnly;   // Always true in Phase 1 (download only)

    public SharedFolder() {
        this.guestAccessible = false;
    }

    public SharedFolder(String alias, String path, boolean guestAccessible) {
        this.alias = alias;
        this.path = path;
        this.guestAccessible = guestAccessible;
    }

    public SharedFolder(String alias, String path, boolean guestAccessible, boolean readOnly) {
        this.alias = alias;
        this.path = path;
        this.guestAccessible = guestAccessible;
        this.readOnly = readOnly;
    }

    // ── Validation ────────────────────────────────────────────────────────────
    /** Returns true if the path exists and is a readable directory. */
    public boolean isValid() {
        if (path == null || path.isBlank()) return false;
        File f = new File(path);
        return f.exists() && f.isDirectory() && f.canRead();
    }

    /** Returns a URL-safe version of the alias (no spaces, lowercase). */
    public String getSafeAlias() {
        if (alias == null) return "folder";
        return alias.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public boolean isGuestAccessible() { return guestAccessible; }
    public void setGuestAccessible(boolean guestAccessible) { this.guestAccessible = guestAccessible; }

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    // For display in TableView
    public String getGuestLabel() { return guestAccessible ? "✓ Public" : "🔒 Login Required"; }
    public String getDisplayPath() {
        return path != null && path.length() > 55 ? "..." + path.substring(path.length() - 52) : path;
    }

    @Override
    public String toString() {
        return alias + " → " + path;
    }
}
