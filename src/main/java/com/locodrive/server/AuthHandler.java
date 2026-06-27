package com.locodrive.server;

import com.locodrive.model.ServerConfig;
import com.locodrive.model.SharedFolder;
import com.locodrive.model.User;
import com.locodrive.util.PasswordUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Handles /login and /logout routes.
 * GET  /login           → serve the HTML login form
 * POST /login           → process credentials, set session cookie, redirect
 * GET  /logout          → clear session cookie, redirect to /login
 */
public class AuthHandler implements HttpHandler {

    private final ServerConfig config;
    private final SessionStore sessionStore;
    private final Consumer<String> logger;

    public AuthHandler(ServerConfig config, SessionStore sessionStore, Consumer<String> logger) {
        this.config = config;
        this.sessionStore = sessionStore;
        this.logger = logger;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        if (path.startsWith("/logout")) {
            handleLogout(exchange);
        } else if ("GET".equals(method)) {
            sendLoginPage(exchange, null);
        } else if ("POST".equals(method)) {
            handleLoginPost(exchange);
        } else {
            sendError(exchange, 405, "Method Not Allowed");
        }
    }

    private void handleLoginPost(HttpExchange exchange) throws IOException {
        // Read body
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        Map<String, String> params = parseForm(body);

        String username = params.getOrDefault("username", "").trim();
        String password = params.getOrDefault("password", "");
        String redirect = params.getOrDefault("redirect", "/browse/");
        if (redirect.isBlank()) redirect = "/browse/";

        // Validate
        User user = config.findUser(username);
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        if (user == null || !user.isEnabled() || !PasswordUtils.verify(password, user.getHashedPassword())) {
            logger.accept("⛔ Failed login attempt for '" + username + "' from " + clientIp);
            sendLoginPage(exchange, "Invalid username or password.");
            return;
        }

        // Create session
        String token = sessionStore.create(user.getUsername());
        logger.accept("✅ Login: " + username + " from " + clientIp);

        // Set cookie and redirect
        exchange.getResponseHeaders().set("Set-Cookie",
            "LDSESSION=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
        exchange.getResponseHeaders().set("Location", redirect);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionStore.extractToken(cookieHeader);
        if (token != null) {
            String username = sessionStore.getUsername(token);
            sessionStore.invalidate(token);
            if (username != null) logger.accept("👋 Logout: " + username);
        }
        // Clear cookie + redirect
        exchange.getResponseHeaders().set("Set-Cookie",
            "LDSESSION=; Path=/; HttpOnly; Max-Age=0");
        exchange.getResponseHeaders().set("Location", "/login");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void sendLoginPage(HttpExchange exchange, String errorMsg) throws IOException {
        String error = errorMsg != null
            ? "<div class=\"error\">⚠ " + errorMsg + "</div>"
            : "";
        String html = LOGIN_HTML.replace("{{ERROR}}", error);
        sendHtml(exchange, 200, html);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Map<String, String> parseForm(String body) {
        Map<String, String> result = new HashMap<>();
        if (body == null || body.isBlank()) return result;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                           URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, b.length);
        exchange.getResponseBody().write(b);
        exchange.close();
    }

    // ── Login Page HTML ───────────────────────────────────────────────────────
    private static final String LOGIN_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <title>LocoDrive — Sign In</title>
          <style>
            *{margin:0;padding:0;box-sizing:border-box}
            body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,
                 'Helvetica Neue',Arial,sans-serif;
                 background:#0F1117;color:#E8EAF6;min-height:100vh;
                 display:flex;align-items:center;justify-content:center;
                 padding:20px}
            .card{background:#1A1D2E;border:1px solid rgba(255,255,255,.08);
                  border-radius:16px;padding:40px 32px;width:100%;max-width:420px;
                  box-shadow:0 24px 64px rgba(0,0,0,.5)}
            .logo{text-align:center;margin-bottom:28px}
            .logo-icon{font-size:44px;margin-bottom:10px}
            h1{font-size:22px;font-weight:700;color:#fff;text-align:center}
            .subtitle{text-align:center;color:#9EA3B8;font-size:13px;margin-top:5px}
            .form-group{margin-top:20px}
            label{display:block;font-size:13px;font-weight:500;color:#9EA3B8;margin-bottom:7px}
            input{width:100%;padding:12px 16px;background:rgba(255,255,255,.06);
                  border:1px solid rgba(255,255,255,.1);border-radius:10px;
                  color:#E8EAF6;font-size:15px;outline:none;transition:border .2s;
                  -webkit-appearance:none}
            input:focus{border-color:#4F9CF9;box-shadow:0 0 0 3px rgba(79,156,249,.12)}
            .btn{width:100%;padding:14px;background:#4F9CF9;border:none;border-radius:10px;
                 color:#fff;font-size:15px;font-weight:600;cursor:pointer;
                 margin-top:24px;transition:background .2s}
            .btn:hover{background:#6BAFFF}
            .btn:active{transform:scale(.98)}
            .error{background:rgba(244,67,54,.12);border:1px solid rgba(244,67,54,.25);
                   border-radius:8px;padding:12px 16px;font-size:14px;color:#FF8A80;
                   margin-top:18px;text-align:center}
            .badge{text-align:center;margin-top:20px;font-size:11px;color:#9EA3B8}

            @media(max-width:480px){
              body{padding:16px;align-items:flex-start;padding-top:60px}
              .card{padding:28px 20px;border-radius:14px}
              .logo-icon{font-size:36px}
              h1{font-size:20px}
              input{font-size:16px;padding:11px 14px}
              .btn{padding:13px;font-size:15px}
            }
          </style>
        </head>
        <body>
          <div class="card">
            <div class="logo">
              <div class="logo-icon">🗄️</div>
              <h1>LocoDrive</h1>
              <p class="subtitle">Local Network File Server</p>
            </div>
            <form method="POST" action="/login">
              <div class="form-group">
                <label for="username">Username</label>
                <input type="text" id="username" name="username" placeholder="Enter username" autocomplete="username" required>
              </div>
              <div class="form-group">
                <label for="password">Password</label>
                <input type="password" id="password" name="password" placeholder="Enter password" autocomplete="current-password" required>
              </div>
              {{ERROR}}
              <button type="submit" class="btn">Sign In</button>
            </form>
            <p class="badge">🔒 Local Network Only — Not connected to the internet</p>
          </div>
        </body>
        </html>
        """;
}
