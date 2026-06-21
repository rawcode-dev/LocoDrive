package com.locodrive.server;

import com.locodrive.model.ServerConfig;
import com.locodrive.model.SharedFolder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles /browse/... routes.
 * - /browse/                    → list all accessible shared folders
 * - /browse/{alias}/            → list folder contents
 * - /browse/{alias}/{path...}   → sub-directory listing or file download
 *
 * Per-folder guest access: if a folder is guest-accessible, unauthenticated
 * users can browse it. Otherwise they are redirected to /login.
 */
public class FileHandler implements HttpHandler {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("MMM dd, HH:mm");

    private final ServerConfig config;
    private final SessionStore sessionStore;
    private final Consumer<String> logger;

    public FileHandler(ServerConfig config, SessionStore sessionStore, Consumer<String> logger) {
        this.config = config;
        this.sessionStore = sessionStore;
        this.logger = logger;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String rawPath = exchange.getRequestURI().getPath(); // e.g. /browse/docs/report.pdf
        String browsePath = rawPath.startsWith("/browse") ? rawPath.substring(7) : rawPath;
        if (!browsePath.startsWith("/")) browsePath = "/" + browsePath;

        // Resolve session
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionStore.extractToken(cookieHeader);
        String username = sessionStore.getUsername(token); // null if not logged in

        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        // ── Root: list all accessible folders ─────────────────────────────────
        if (browsePath.equals("/") || browsePath.equals("")) {
            List<SharedFolder> accessible = config.getSharedFolders().stream()
                .filter(f -> f.isGuestAccessible() || username != null)
                .toList();

            if (accessible.isEmpty() && username == null) {
                redirect(exchange, "/login?redirect=" + URLEncoder.encode("/browse/", StandardCharsets.UTF_8));
                return;
            }
            logger.accept("📂 " + (username != null ? username : "Guest") + " @ " + clientIp + " → folder list");
            sendHtml(exchange, 200, buildFolderListPage(accessible, username));
            return;
        }

        // ── Parse alias and sub-path ───────────────────────────────────────────
        String[] segments = browsePath.substring(1).split("/", 2);
        String alias = segments[0];
        String subPath = segments.length > 1 ? segments[1] : "";
        subPath = URLDecoder.decode(subPath, StandardCharsets.UTF_8);

        SharedFolder folder = config.findFolder(alias);
        if (folder == null) {
            sendError(exchange, 404, "Folder not found: " + alias);
            return;
        }

        // Auth check per-folder
        if (!folder.isGuestAccessible() && username == null) {
            redirect(exchange, "/login?redirect=" + URLEncoder.encode(rawPath, StandardCharsets.UTF_8));
            return;
        }

        // Resolve the physical path (prevent path traversal)
        Path rootPath = Path.of(folder.getPath()).toAbsolutePath().normalize();
        Path targetPath = rootPath.resolve(subPath).normalize();

        // Security: ensure target is inside root
        if (!targetPath.startsWith(rootPath)) {
            logger.accept("🚨 Path traversal blocked! " + clientIp + " tried " + subPath);
            sendError(exchange, 403, "Access Denied");
            return;
        }

        if (!Files.exists(targetPath)) {
            sendError(exchange, 404, "Not Found: " + subPath);
            return;
        }

        if (Files.isDirectory(targetPath)) {
            // Directory listing
            logger.accept("📂 " + (username != null ? username : "Guest")
                + " @ " + clientIp + " → /" + alias + "/" + subPath);
            sendHtml(exchange, 200, buildDirectoryPage(folder, targetPath, rootPath, subPath, username));
        } else {
            // File download
            String fileName = targetPath.getFileName().toString();
            logger.accept("⬇ " + (username != null ? username : "Guest")
                + " @ " + clientIp + " downloaded " + fileName);
            serveFile(exchange, targetPath, fileName);
        }
    }

    // ── Directory Listing ─────────────────────────────────────────────────────
    private String buildFolderListPage(List<SharedFolder> folders, String username) {
        StringBuilder cards = new StringBuilder();
        for (SharedFolder f : folders) {
            String icon = f.isGuestAccessible() ? "🌐" : "🔒";
            String badge = f.isGuestAccessible()
                ? "<span class=\"badge public\">Public</span>"
                : "<span class=\"badge private\">Login Required</span>";
            cards.append("""
                <a class="folder-card" href="/browse/%s/">
                  <div class="fc-icon">📁</div>
                  <div class="fc-info">
                    <div class="fc-name">%s</div>
                    <div class="fc-path">%s</div>
                  </div>
                  <div class="fc-right">%s %s</div>
                </a>
                """.formatted(f.getSafeAlias(), f.getAlias(),
                    f.getDisplayPath(), icon, badge));
        }
        return PAGE_TEMPLATE
            .replace("{{TITLE}}", "LocoDrive")
            .replace("{{HEADING}}", "Shared Folders")
            .replace("{{BREADCRUMB}}", breadcrumb(null, null, null))
            .replace("{{USERNAME}}", username != null ? username : "Guest")
            .replace("{{LOGOUT_LINK}}", username != null ? "<a href=\"/logout\" class=\"logout-btn\">Sign Out</a>" : "")
            .replace("{{CONTENT}}", "<div class=\"folder-grid\">" + cards + "</div>");
    }

    private String buildDirectoryPage(SharedFolder folder, Path dir, Path root, String subPath, String username) throws IOException {
        List<Path> entries;
        try (var stream = Files.list(dir)) {
            entries = stream.sorted((a, b) -> {
                boolean aDir = Files.isDirectory(a);
                boolean bDir = Files.isDirectory(b);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).toList();
        }

        // Breadcrumb
        String baseUrl = "/browse/" + folder.getSafeAlias() + "/";
        String crumb = breadcrumb(folder.getAlias(), baseUrl, subPath);

        // Parent link
        String parentLink = "";
        if (!subPath.isBlank()) {
            String parentPath = subPath.contains("/")
                ? subPath.substring(0, subPath.lastIndexOf('/'))
                : "";
            parentLink = "<a href=\"" + baseUrl + encode(parentPath) + "\" class=\"file-row parent\">⬆ Parent Directory</a>";
        } else {
            parentLink = "<a href=\"/browse/\" class=\"file-row parent\">⬆ All Folders</a>";
        }

        StringBuilder rows = new StringBuilder(parentLink);
        for (Path entry : entries) {
            boolean isDir = Files.isDirectory(entry);
            String name = entry.getFileName().toString();
            String relPath = subPath.isBlank() ? name : subPath + "/" + name;
            String href = isDir
                ? baseUrl + encode(relPath) + "/"
                : baseUrl + encode(relPath);
            String icon = isDir ? "📁" : getFileIcon(name);
            String size = isDir ? "—" : formatSize(Files.size(entry));
            String modified = DT_FMT.format(LocalDateTime.ofInstant(
                Files.getLastModifiedTime(entry).toInstant(), java.time.ZoneId.systemDefault()));
            rows.append("""
                <a href="%s" class="file-row %s">
                  <span class="fi-icon">%s</span>
                  <span class="fi-name">%s</span>
                  <span class="fi-size">%s</span>
                  <span class="fi-date">%s</span>
                </a>
                """.formatted(href, isDir ? "dir" : "file", icon, name, size, modified));
        }

        String logout = username != null ? "<a href=\"/logout\" class=\"logout-btn\">Sign Out</a>" : "";
        return PAGE_TEMPLATE
            .replace("{{TITLE}}", folder.getAlias() + " — LocoDrive")
            .replace("{{HEADING}}", folder.getAlias())
            .replace("{{BREADCRUMB}}", crumb)
            .replace("{{USERNAME}}", username != null ? username : "Guest")
            .replace("{{LOGOUT_LINK}}", logout)
            .replace("{{CONTENT}}", "<div class=\"file-list\">" + rows + "</div>");
    }

    // ── File Serving ──────────────────────────────────────────────────────────
    private void serveFile(HttpExchange exchange, Path file, String name) throws IOException {
        String mime = Files.probeContentType(file);
        if (mime == null) mime = "application/octet-stream";

        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Content-Disposition",
            "attachment; filename=\"" + name + "\"");

        long size = Files.size(file);
        exchange.sendResponseHeaders(200, size);
        try (OutputStream os = exchange.getResponseBody();
             InputStream is = Files.newInputStream(file)) {
            is.transferTo(os);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────
    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        String html = "<html><body style='background:#0F1117;color:#E8EAF6;font-family:sans-serif;"
            + "display:flex;align-items:center;justify-content:center;height:100vh;'>"
            + "<div style='text-align:center'><h1>" + code + "</h1><p>" + msg + "</p>"
            + "<a href='/browse/' style='color:#4F9CF9'>← Back</a></div></body></html>";
        sendHtml(exchange, code, html);
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private String breadcrumb(String folderName, String baseUrl, String subPath) {
        StringBuilder sb = new StringBuilder("<a href=\"/browse/\" class=\"crumb\">🏠 Home</a>");
        if (folderName != null) {
            sb.append(" <span class=\"crumb-sep\">/</span> <a href=\"").append(baseUrl)
              .append("\" class=\"crumb\">").append(folderName).append("</a>");
            if (subPath != null && !subPath.isBlank()) {
                String[] parts = subPath.split("/");
                StringBuilder cumPath = new StringBuilder();
                for (String part : parts) {
                    cumPath.append(part).append("/");
                    sb.append(" <span class=\"crumb-sep\">/</span> <a href=\"")
                      .append(baseUrl).append(encode(cumPath.toString()))
                      .append("\" class=\"crumb\">").append(part).append("</a>");
                }
            }
        }
        return sb.toString();
    }

    private String encode(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        DecimalFormat df = new DecimalFormat("#.#");
        if (bytes < 1024 * 1024) return df.format(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return df.format(bytes / (1024.0 * 1024)) + " MB";
        return df.format(bytes / (1024.0 * 1024 * 1024)) + " GB";
    }

    private String getFileIcon(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "📄";
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|webp|svg|bmp)")) return "🖼️";
        if (lower.matches(".*\\.(mp4|mkv|avi|mov|webm)")) return "🎬";
        if (lower.matches(".*\\.(mp3|wav|flac|aac|ogg)")) return "🎵";
        if (lower.matches(".*\\.(zip|rar|7z|tar|gz)")) return "📦";
        if (lower.matches(".*\\.(doc|docx|odt)")) return "📝";
        if (lower.matches(".*\\.(xls|xlsx|csv|ods)")) return "📊";
        if (lower.matches(".*\\.(ppt|pptx|odp)")) return "📊";
        if (lower.matches(".*\\.(txt|md|log)")) return "📃";
        if (lower.matches(".*\\.(java|py|js|ts|html|css|json|xml|sh)")) return "💻";
        return "📄";
    }

    // ── HTML Page Template ────────────────────────────────────────────────────
    private static final String PAGE_TEMPLATE = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>{{TITLE}}</title>
          <style>
            *{margin:0;padding:0;box-sizing:border-box}
            body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
                 background:#0F1117;color:#E8EAF6;min-height:100vh}
            header{background:#1A1D2E;border-bottom:1px solid rgba(255,255,255,.08);
                   padding:16px 24px;display:flex;align-items:center;justify-content:space-between;
                   position:sticky;top:0;z-index:100}
            .logo{display:flex;align-items:center;gap:10px;font-weight:700;font-size:18px;color:#fff}
            .logo-icon{font-size:22px}
            .user-info{display:flex;align-items:center;gap:12px;font-size:14px;color:#9EA3B8}
            .logout-btn{background:#4F9CF9;color:#fff;padding:6px 14px;border-radius:8px;
                        text-decoration:none;font-size:13px;font-weight:600}
            .logout-btn:hover{background:#6BAFFF}
            .breadcrumb{padding:12px 24px;font-size:13px;color:#9EA3B8;
                        background:rgba(255,255,255,.02);border-bottom:1px solid rgba(255,255,255,.05)}
            .crumb{color:#4F9CF9;text-decoration:none}
            .crumb:hover{text-decoration:underline}
            .crumb-sep{margin:0 6px;color:#444}
            main{padding:24px;max-width:1200px;margin:0 auto}
            h2{font-size:22px;font-weight:700;color:#fff;margin-bottom:20px}
            /* Folder grid */
            .folder-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:16px}
            .folder-card{background:#1A1D2E;border:1px solid rgba(255,255,255,.08);border-radius:14px;
                         padding:20px;text-decoration:none;color:#E8EAF6;
                         display:flex;align-items:center;gap:14px;
                         transition:border-color .2s,transform .15s}
            .folder-card:hover{border-color:#4F9CF9;transform:translateY(-2px)}
            .fc-icon{font-size:36px}
            .fc-info{flex:1;overflow:hidden}
            .fc-name{font-size:16px;font-weight:600;color:#fff;margin-bottom:4px}
            .fc-path{font-size:12px;color:#9EA3B8;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .fc-right{display:flex;flex-direction:column;align-items:flex-end;gap:6px;font-size:20px}
            .badge{font-size:11px;padding:3px 8px;border-radius:6px;font-weight:600}
            .badge.public{background:rgba(76,175,80,.15);color:#81C784}
            .badge.private{background:rgba(255,152,0,.15);color:#FFB74D}
            /* File list */
            .file-list{background:#1A1D2E;border:1px solid rgba(255,255,255,.08);border-radius:14px;overflow:hidden}
            .file-row{display:flex;align-items:center;padding:13px 20px;text-decoration:none;
                      color:#E8EAF6;border-bottom:1px solid rgba(255,255,255,.05);
                      transition:background .15s;gap:12px}
            .file-row:last-child{border-bottom:none}
            .file-row:hover{background:rgba(79,156,249,.08)}
            .file-row.parent{color:#9EA3B8;font-size:14px}
            .file-row.dir{color:#fff}
            .fi-icon{font-size:20px;width:28px;text-align:center}
            .fi-name{flex:1;font-size:14px;word-break:break-all}
            .fi-size{min-width:70px;text-align:right;font-size:12px;color:#9EA3B8}
            .fi-date{min-width:90px;text-align:right;font-size:12px;color:#9EA3B8}
            .badge-lnonly{position:fixed;bottom:16px;right:16px;background:#1A1D2E;
                          border:1px solid rgba(255,255,255,.1);border-radius:8px;
                          padding:6px 12px;font-size:11px;color:#9EA3B8}
            @media(max-width:600px){
              .fi-size,.fi-date{display:none}
              header{padding:12px 16px}
              main{padding:16px}
            }
          </style>
        </head>
        <body>
          <header>
            <div class="logo"><span class="logo-icon">🗄️</span> LocoDrive</div>
            <div class="user-info">
              <span>👤 {{USERNAME}}</span>
              {{LOGOUT_LINK}}
            </div>
          </header>
          <div class="breadcrumb">{{BREADCRUMB}}</div>
          <main>
            <h2>{{HEADING}}</h2>
            {{CONTENT}}
          </main>
          <div class="badge-lnonly">🔒 Local Network Only</div>
        </body>
        </html>
        """;
}
