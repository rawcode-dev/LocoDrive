package com.locodrive.model;

/**
 * Represents a user account for the file server.
 */
public class User {

    public enum Role {
        ADMIN, USER, GUEST
    }

    private String username;
    private String hashedPassword;  // SHA-256 + salt, stored as "salt:hash"
    private Role role;
    private boolean enabled;

    public User() {
        this.enabled = true;
        this.role = Role.USER;
    }

    public User(String username, String hashedPassword, Role role) {
        this.username = username;
        this.hashedPassword = hashedPassword;
        this.role = role;
        this.enabled = true;
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getHashedPassword() { return hashedPassword; }
    public void setHashedPassword(String hashedPassword) { this.hashedPassword = hashedPassword; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAdmin() { return role == Role.ADMIN; }
    public boolean isGuest() { return role == Role.GUEST; }

    @Override
    public String toString() {
        return username + " (" + role.name() + ")";
    }
}
