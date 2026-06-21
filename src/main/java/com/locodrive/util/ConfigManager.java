package com.locodrive.util;

import com.locodrive.AppContext;
import com.locodrive.model.ServerConfig;
import com.locodrive.model.SharedFolder;
import com.locodrive.model.User;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Persists {@link ServerConfig} to ~/.locodrive/config.json.
 * Passwords are stored as salted SHA-256 hashes — never plain text.
 */
public class ConfigManager {

    private static ConfigManager instance;

    private static final Path CONFIG_DIR  = Path.of(System.getProperty("user.home"), ".locodrive");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    private ConfigManager() {}

    public static ConfigManager getInstance() {
        if (instance == null) instance = new ConfigManager();
        return instance;
    }

    // ── Save ──────────────────────────────────────────────────────────────────
    public void saveConfig() {
        ServerConfig cfg = AppContext.getInstance().getConfig();
        JSONObject root = new JSONObject();
        root.put("bindAddress", cfg.getBindAddress());
        root.put("port", cfg.getPort());
        root.put("guestEnabled", cfg.isGuestEnabled());

        // Users
        JSONArray usersArr = new JSONArray();
        for (User u : cfg.getUsers()) {
            JSONObject uo = new JSONObject();
            uo.put("username", u.getUsername());
            uo.put("hashedPassword", u.getHashedPassword());
            uo.put("role", u.getRole().name());
            uo.put("enabled", u.isEnabled());
            usersArr.put(uo);
        }
        root.put("users", usersArr);

        // Shared Folders
        JSONArray foldersArr = new JSONArray();
        for (SharedFolder f : cfg.getSharedFolders()) {
            JSONObject fo = new JSONObject();
            fo.put("alias", f.getAlias());
            fo.put("path", f.getPath());
            fo.put("guestAccessible", f.isGuestAccessible());
            fo.put("readOnly", f.isReadOnly());
            foldersArr.put(fo);
        }
        root.put("sharedFolders", foldersArr);

        try {
            Files.createDirectories(CONFIG_DIR);
            Files.writeString(CONFIG_FILE, root.toString(2), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("Config saved to: " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    public void loadConfig() {
        if (!Files.exists(CONFIG_FILE)) return;
        try {
            String json = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);

            ServerConfig cfg = new ServerConfig();
            cfg.setBindAddress(root.optString("bindAddress", ""));
            cfg.setPort(root.optInt("port", 8080));
            cfg.setGuestEnabled(root.optBoolean("guestEnabled", false));

            // Users
            JSONArray usersArr = root.optJSONArray("users");
            if (usersArr != null) {
                for (int i = 0; i < usersArr.length(); i++) {
                    JSONObject uo = usersArr.getJSONObject(i);
                    User u = new User();
                    u.setUsername(uo.optString("username"));
                    u.setHashedPassword(uo.optString("hashedPassword"));
                    u.setRole(User.Role.valueOf(uo.optString("role", "USER")));
                    u.setEnabled(uo.optBoolean("enabled", true));
                    cfg.getUsers().add(u);
                }
            }

            // Shared Folders
            JSONArray foldersArr = root.optJSONArray("sharedFolders");
            if (foldersArr != null) {
                for (int i = 0; i < foldersArr.length(); i++) {
                    JSONObject fo = foldersArr.getJSONObject(i);
                    SharedFolder f = new SharedFolder();
                    f.setAlias(fo.optString("alias"));
                    f.setPath(fo.optString("path"));
                    f.setGuestAccessible(fo.optBoolean("guestAccessible", false));
                    f.setReadOnly(fo.optBoolean("readOnly", true));
                    cfg.getSharedFolders().add(f);
                }
            }

            AppContext.getInstance().setConfig(cfg);
            System.out.println("Config loaded from: " + CONFIG_FILE);

        } catch (Exception e) {
            System.err.println("Failed to load config (using defaults): " + e.getMessage());
        }
    }

    public boolean configExists() {
        return Files.exists(CONFIG_FILE);
    }
}
