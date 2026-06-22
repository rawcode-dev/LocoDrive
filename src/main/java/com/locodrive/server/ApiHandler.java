package com.locodrive.server;

import com.locodrive.AppContext;
import com.locodrive.model.ServerConfig;
import com.locodrive.model.SharedFolder;
import com.locodrive.model.User;
import com.locodrive.util.ConfigManager;
import com.locodrive.util.NetworkDetector;
import com.locodrive.util.PasswordUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Internal REST API used by the Tauri WebView UI.
 *
 * GET  /api/health        → {"status":"ok"}
 * GET  /api/status        → server stats
 * GET  /api/log           → access log entries as JSON array
 * GET  /api/config        → current ServerConfig as JSON
 * POST /api/config        → save new ServerConfig
 * GET  /api/networks      → list of LAN IP addresses
 */
public class ApiHandler implements HttpHandler {

    private final LocalFileServer server;
    private final long startTime = System.currentTimeMillis();

    public ApiHandler(LocalFileServer server) {
        this.server = server;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS headers for Tauri WebView
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        if (path.endsWith("/health")) {
            send(exchange, 200, "{\"status\":\"ok\"}");
        } else if (path.endsWith("/status")) {
            handleStatus(exchange);
        } else if (path.endsWith("/log")) {
            handleLog(exchange);
        } else if (path.endsWith("/config")) {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleConfigSave(exchange);
            } else {
                handleConfigGet(exchange);
            }
        } else if (path.endsWith("/networks")) {
            handleNetworks(exchange);
        } else {
            send(exchange, 404, "{\"error\":\"Not found\"}");
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        long uptime = System.currentTimeMillis() - startTime;
        long uptimeSec = uptime / 1000;
        String json = String.format(
            "{\"running\":true,\"sessions\":%d,\"logCount\":%d,\"uptimeSeconds\":%d}",
            server.getActiveSessionCount(),
            server.getAccessLog().size(),
            uptimeSec
        );
        send(exchange, 200, json);
    }

    private void handleLog(HttpExchange exchange) throws IOException {
        JSONArray arr = new JSONArray();
        List<String> log = server.getAccessLog();
        int start = Math.max(0, log.size() - 200);
        for (int i = start; i < log.size(); i++) {
            arr.put(log.get(i));
        }
        send(exchange, 200, arr.toString());
    }

    private void handleConfigGet(HttpExchange exchange) throws IOException {
        ServerConfig cfg = AppContext.getInstance().getConfig();
        JSONObject json = configToJson(cfg);
        send(exchange, 200, json.toString());
    }

    private void handleConfigSave(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(body);
            ServerConfig cfg = AppContext.getInstance().getConfig();

            if (json.has("bindAddress")) cfg.setBindAddress(json.getString("bindAddress"));
            if (json.has("port"))        cfg.setPort(json.getInt("port"));
            if (json.has("guestEnabled")) cfg.setGuestEnabled(json.getBoolean("guestEnabled"));

            if (json.has("sharedFolders")) {
                cfg.getSharedFolders().clear();
                JSONArray fArr = json.getJSONArray("sharedFolders");
                for (int i = 0; i < fArr.length(); i++) {
                    JSONObject fo = fArr.getJSONObject(i);
                    SharedFolder f = new SharedFolder();
                    f.setAlias(fo.optString("alias"));
                    f.setPath(fo.optString("path"));
                    f.setGuestAccessible(fo.optBoolean("guestAccessible", false));
                    f.setReadOnly(fo.optBoolean("readOnly", true));
                    cfg.getSharedFolders().add(f);
                }
            }

            if (json.has("users")) {
                cfg.getUsers().clear();
                JSONArray uArr = json.getJSONArray("users");
                for (int i = 0; i < uArr.length(); i++) {
                    JSONObject uo = uArr.getJSONObject(i);
                    User u = new User();
                    u.setUsername(uo.optString("username"));
                    // If a plain password provided, hash it; otherwise reuse stored hash
                    if (uo.has("password") && !uo.optString("password").isBlank()) {
                        String salt = PasswordUtils.generateSalt();
                        u.setHashedPassword(PasswordUtils.hashPassword(uo.getString("password"), salt));
                    } else {
                        u.setHashedPassword(uo.optString("hashedPassword", ""));
                    }
                    u.setRole(User.Role.valueOf(uo.optString("role", "USER")));
                    u.setEnabled(uo.optBoolean("enabled", true));
                    cfg.getUsers().add(u);
                }
            }

            ConfigManager.getInstance().saveConfig();
            send(exchange, 200, "{\"saved\":true}");
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleNetworks(HttpExchange exchange) throws IOException {
        JSONArray arr = new JSONArray(NetworkDetector.getLanAddresses());
        send(exchange, 200, arr.toString());
    }

    private JSONObject configToJson(ServerConfig cfg) {
        JSONObject root = new JSONObject();
        root.put("bindAddress", cfg.getBindAddress());
        root.put("port", cfg.getPort());
        root.put("guestEnabled", cfg.isGuestEnabled());
        root.put("serverUrl", cfg.getServerUrl());
        root.put("configExists", ConfigManager.getInstance().configExists());

        JSONArray users = new JSONArray();
        for (User u : cfg.getUsers()) {
            JSONObject uo = new JSONObject();
            uo.put("username", u.getUsername());
            uo.put("hashedPassword", u.getHashedPassword());
            uo.put("role", u.getRole().name());
            uo.put("enabled", u.isEnabled());
            users.put(uo);
        }
        root.put("users", users);

        JSONArray folders = new JSONArray();
        for (SharedFolder f : cfg.getSharedFolders()) {
            JSONObject fo = new JSONObject();
            fo.put("alias", f.getAlias());
            fo.put("path", f.getPath());
            fo.put("guestAccessible", f.isGuestAccessible());
            fo.put("readOnly", f.isReadOnly());
            folders.put(fo);
        }
        root.put("sharedFolders", folders);
        return root;
    }

    private void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}
