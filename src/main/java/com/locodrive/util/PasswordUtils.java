package com.locodrive.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing utilities using SHA-256 + random salt.
 * Stored format: "BASE64_SALT:HEX_HASH"
 */
public class PasswordUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_LENGTH = 16; // bytes

    /** Minimum password length enforced in the UI. */
    public static final int MIN_PASSWORD_LENGTH = 6;

    /**
     * Hashes a plain-text password with a random salt.
     * @return "BASE64_SALT:HEX_HASH" string for storage.
     */
    public static String hash(String plainPassword) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        String saltB64 = Base64.getEncoder().encodeToString(salt);
        String hash = sha256Hex(saltB64 + plainPassword);
        return saltB64 + ":" + hash;
    }

    /**
     * Verifies a plain-text password against a stored hash.
     * @param plainPassword  The password entered by the user.
     * @param storedHash     The "BASE64_SALT:HEX_HASH" string from config.
     * @return true if the password matches.
     */
    public static boolean verify(String plainPassword, String storedHash) {
        if (storedHash == null || !storedHash.contains(":")) return false;
        String[] parts = storedHash.split(":", 2);
        String saltB64 = parts[0];
        String expectedHex = parts[1];
        String actualHex = sha256Hex(saltB64 + plainPassword);
        return expectedHex.equals(actualHex);
    }

    /**
     * Returns a password strength label: WEAK / FAIR / STRONG
     */
    public static String strengthLabel(String password) {
        if (password == null || password.length() < 6) return "WEAK";
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        int score = (hasUpper ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);
        if (password.length() >= 12 && score >= 2) return "STRONG";
        if (password.length() >= 8 && score >= 1) return "FAIR";
        return "WEAK";
    }

    // ── Internal ──────────────────────────────────────────────────────────────
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
