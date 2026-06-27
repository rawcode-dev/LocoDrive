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
import java.nio.channels.*;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Handles /browse/... and /download/... routes.
 * - GET  /browse/                    → list all accessible shared folders
 * - GET  /browse/{alias}/            → list folder contents
 * - GET  /browse/{alias}/{path...}   → sub-directory listing or file download
 * - POST /browse/{alias}/...         → file upload (multipart/form-data)
 * - POST /download/{alias}/          → batch ZIP download (selected files/folders)
 * - GET  /download/{alias}/{path}    → single folder as ZIP download
 */
public class FileHandler implements HttpHandler {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("MMM dd, HH:mm");
    private static final long MAX_UPLOAD_SIZE = 500L * 1024 * 1024; // 500 MB

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
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();

        if (path.startsWith("/download")) {
            if ("POST".equals(method)) {
                handleBatchDownload(exchange);
            } else if ("GET".equals(method)) {
                handleFolderDownload(exchange);
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        } else if (path.startsWith("/assets")) {
            handleAsset(exchange);
        } else if ("GET".equals(method)) {
            handleGet(exchange);
        } else if ("POST".equals(method)) {
            handleUpload(exchange);
        } else {
            sendError(exchange, 405, "Method Not Allowed");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Assets
    // ══════════════════════════════════════════════════════════════════════════
    
    private void handleAsset(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String assetName = path.substring(8); // remove "/assets/"
        
        try (InputStream is = getClass().getResourceAsStream("/images/" + assetName)) {
            if (is == null) {
                sendError(exchange, 404, "Asset Not Found");
                return;
            }
            String mime = "application/octet-stream";
            if (assetName.endsWith(".png")) mime = "image/png";
            else if (assetName.endsWith(".svg")) mime = "image/svg+xml";
            
            byte[] data = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", mime);
            // Cache for 1 day
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GET — Browse / Download
    // ══════════════════════════════════════════════════════════════════════════

    private void handleGet(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getPath();
        String browsePath = rawPath.startsWith("/browse") ? rawPath.substring(7) : rawPath;
        if (!browsePath.startsWith("/")) browsePath = "/" + browsePath;

        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionStore.extractToken(cookieHeader);
        String username = sessionStore.getUsername(token);
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        // ── Root: list all accessible folders
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

        // ── Parse alias and sub-path
        String[] segments = browsePath.substring(1).split("/", 2);
        String alias = segments[0];
        String subPath = segments.length > 1 ? segments[1] : "";
        subPath = URLDecoder.decode(subPath, StandardCharsets.UTF_8);

        SharedFolder folder = config.findFolder(alias);
        if (folder == null) {
            sendError(exchange, 404, "Folder not found: " + alias);
            return;
        }

        if (!folder.isGuestAccessible() && username == null) {
            redirect(exchange, "/login?redirect=" + URLEncoder.encode(rawPath, StandardCharsets.UTF_8));
            return;
        }

        Path rootPath = Path.of(folder.getPath()).toAbsolutePath().normalize();
        Path targetPath = rootPath.resolve(subPath).normalize();

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
            logger.accept("📂 " + (username != null ? username : "Guest")
                + " @ " + clientIp + " → /" + alias + "/" + subPath);
            boolean canUpload = username != null && !folder.isReadOnly();
            sendHtml(exchange, 200, buildDirectoryPage(folder, targetPath, rootPath, subPath, username, canUpload));
        } else {
            String fileName = targetPath.getFileName().toString();
            logger.accept("⬇ " + (username != null ? username : "Guest")
                + " @ " + clientIp + " downloaded " + fileName);
            serveFile(exchange, targetPath, fileName);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  POST — File Upload (multipart/form-data)
    // ══════════════════════════════════════════════════════════════════════════

    private void handleUpload(HttpExchange exchange) throws IOException {
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionStore.extractToken(cookieHeader);
        String username = sessionStore.getUsername(token);

        if (username == null) {
            sendError(exchange, 403, "You must be logged in to upload files.");
            return;
        }

        String rawPath = exchange.getRequestURI().getPath();
        String browsePath = rawPath.startsWith("/browse") ? rawPath.substring(7) : rawPath;
        if (!browsePath.startsWith("/")) browsePath = "/" + browsePath;

        String[] segments = browsePath.substring(1).split("/", 2);
        String alias = segments[0];
        String subPath = segments.length > 1 ? segments[1] : "";
        subPath = URLDecoder.decode(subPath, StandardCharsets.UTF_8);

        SharedFolder folder = config.findFolder(alias);
        if (folder == null) { sendError(exchange, 404, "Folder not found"); return; }
        if (folder.isReadOnly()) { sendError(exchange, 403, "This folder is read-only."); return; }

        Path rootPath = Path.of(folder.getPath()).toAbsolutePath().normalize();
        Path targetDir = rootPath.resolve(subPath).normalize();

        if (!targetDir.startsWith(rootPath) || !Files.isDirectory(targetDir)) {
            sendError(exchange, 403, "Invalid upload target.");
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) {
            sendError(exchange, 400, "Missing Content-Type");
            return;
        }

        int uploadCount = 0;
        try (InputStream is = exchange.getRequestBody()) {
            if (contentType.contains("multipart/form-data")) {
                String boundary = extractBoundary(contentType);
                if (boundary == null) { sendError(exchange, 400, "Missing boundary"); return; }
                uploadCount = parseMultipartAndSave(is, boundary, targetDir, clientIp, username);
            } else if (contentType.contains("application/octet-stream")) {
                String fileName = exchange.getRequestHeaders().getFirst("X-File-Name");
                String uploadId = exchange.getRequestHeaders().getFirst("X-Upload-Id");
                String offsetStr = exchange.getRequestHeaders().getFirst("X-Chunk-Offset");
                String isFinish = exchange.getRequestHeaders().getFirst("X-Upload-Finish");
                
                if (fileName == null || fileName.isBlank()) { sendError(exchange, 400, "Missing X-File-Name"); return; }
                fileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
                fileName = Path.of(fileName).getFileName().toString();
                if (fileName.isBlank() || fileName.startsWith(".")) fileName = "upload_" + System.currentTimeMillis();

                if ("true".equals(isFinish) && uploadId != null) {
                    Path partFile = targetDir.resolve(uploadId + ".part");
                    if (Files.exists(partFile)) {
                        Path dest = uniquePath(targetDir.resolve(fileName));
                        Files.move(partFile, dest, StandardCopyOption.REPLACE_EXISTING);
                        logger.accept("   📥 Finished chunked upload: " + dest.getFileName());
                    }
                    uploadCount = 1;
                } else if (offsetStr != null && uploadId != null) {
                    long offset = Long.parseLong(offsetStr);
                    Path partFile = targetDir.resolve(uploadId + ".part");
                    try (RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw");
                         FileChannel channel = raf.getChannel();
                         ReadableByteChannel rbc = Channels.newChannel(is)) {
                         
                        long expectedSize = -1;
                        String cl = exchange.getRequestHeaders().getFirst("Content-Length");
                        if (cl != null) expectedSize = Long.parseLong(cl);
                        
                        long transferred = 0;
                        while (expectedSize < 0 || transferred < expectedSize) {
                            long count = channel.transferFrom(rbc, offset + transferred, expectedSize < 0 ? Long.MAX_VALUE : (expectedSize - transferred));
                            if (count <= 0) break;
                            transferred += count;
                        }
                    }
                    uploadCount = 0; // Don't trigger standard logging for partial chunks
                } else {
                    Path dest = uniquePath(targetDir.resolve(fileName));
                    try (OutputStream os = Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        is.transferTo(os);
                    }
                    logger.accept("   📥 Saved: " + dest.getFileName() + " (streamed directly)");
                    uploadCount = 1;
                }
            } else {
                sendError(exchange, 400, "Unsupported Content-Type");
                return;
            }
        } catch (Exception e) {
            logger.accept("❌ Upload error from " + username + " @ " + clientIp + ": " + e.getMessage());
            sendError(exchange, 500, "Upload failed: " + e.getMessage());
            return;
        }

        logger.accept("⬆ " + username + " @ " + clientIp + " uploaded " + uploadCount + " file(s) to /" + alias + "/" + subPath);

        if ("XMLHttpRequest".equals(exchange.getRequestHeaders().getFirst("X-Requested-With"))) {
            sendHtml(exchange, 200, "OK");
        } else {
            String redirectUrl = "/browse/" + folder.getSafeAlias() + "/" + encode(subPath);
            if (!redirectUrl.endsWith("/")) redirectUrl += "/";
            redirect(exchange, redirectUrl);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GET /download/{alias}/{path} — Single folder as ZIP
    // ══════════════════════════════════════════════════════════════════════════

    private void handleFolderDownload(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getPath();
        String dlPath = rawPath.startsWith("/download") ? rawPath.substring(9) : rawPath;
        if (!dlPath.startsWith("/")) dlPath = "/" + dlPath;

        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionStore.extractToken(cookieHeader);
        String username = sessionStore.getUsername(token);
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        String[] segments = dlPath.substring(1).split("/", 2);
        if (segments.length == 0 || segments[0].isBlank()) { sendError(exchange, 400, "Missing folder"); return; }
        String alias = segments[0];
        String subPath = segments.length > 1 ? URLDecoder.decode(segments[1], StandardCharsets.UTF_8) : "";

        SharedFolder folder = config.findFolder(alias);
        if (folder == null) { sendError(exchange, 404, "Folder not found"); return; }
        if (!folder.isGuestAccessible() && username == null) { sendError(exchange, 403, "Login required"); return; }

        Path rootPath = Path.of(folder.getPath()).toAbsolutePath().normalize();
        Path targetPath = rootPath.resolve(subPath).normalize();

        if (!targetPath.startsWith(rootPath) || !Files.exists(targetPath)) {
            sendError(exchange, 404, "Not found");
            return;
        }

        if (Files.isDirectory(targetPath)) {
            String zipName = targetPath.getFileName().toString() + ".zip";
            logger.accept("📦 " + (username != null ? username : "Guest") + " @ " + clientIp + " downloaded folder as ZIP: " + zipName);

            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + zipName + "\"");
            exchange.sendResponseHeaders(200, 0);

            try (ZipOutputStream zos = new ZipOutputStream(exchange.getResponseBody())) {
                zipDirectory(targetPath, targetPath, zos);
            }
        } else {
            serveFile(exchange, targetPath, targetPath.getFileName().toString());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  POST /download/{alias}/ — Batch download selected files as ZIP
    // ══════════════════════════════════════════════════════════════════════════

    private void handleBatchDownload(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getPath();
        String dlPath = rawPath.startsWith("/download") ? rawPath.substring(9) : rawPath;
        if (!dlPath.startsWith("/")) dlPath = "/" + dlPath;

        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionStore.extractToken(cookieHeader);
        String username = sessionStore.getUsername(token);
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        String[] segments = dlPath.substring(1).split("/", 2);
        if (segments.length == 0 || segments[0].isBlank()) { sendError(exchange, 400, "Missing folder"); return; }
        String alias = segments[0];

        SharedFolder folder = config.findFolder(alias);
        if (folder == null) { sendError(exchange, 404, "Folder not found"); return; }
        if (!folder.isGuestAccessible() && username == null) { sendError(exchange, 403, "Login required"); return; }

        // Read POST body — expecting "files=path1\npath2\npath3"
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String[] lines = body.split("\n");

        Path rootPath = Path.of(folder.getPath()).toAbsolutePath().normalize();

        logger.accept("📦 " + (username != null ? username : "Guest") + " @ " + clientIp
            + " batch download " + lines.length + " item(s) from /" + alias);

        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"LocoDrive-download.zip\"");
        exchange.sendResponseHeaders(200, 0);

        try (ZipOutputStream zos = new ZipOutputStream(exchange.getResponseBody())) {
            for (String line : lines) {
                String filePath = line.trim();
                if (filePath.isBlank()) continue;
                filePath = URLDecoder.decode(filePath, StandardCharsets.UTF_8);

                Path target = rootPath.resolve(filePath).normalize();
                if (!target.startsWith(rootPath) || !Files.exists(target)) continue;

                if (Files.isDirectory(target)) {
                    zipDirectory(target, rootPath, zos);
                } else {
                    String entryName = rootPath.relativize(target).toString();
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(target, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    private void zipDirectory(Path dir, Path baseDir, ZipOutputStream zos) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> !Files.isDirectory(p)).forEach(p -> {
                try {
                    String entryName = baseDir.relativize(p).toString();
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    // skip unreadable files
                }
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Multipart Upload Parser
    // ══════════════════════════════════════════════════════════════════════════

    private String extractBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                String b = trimmed.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"")) b = b.substring(1, b.length() - 1);
                return b;
            }
        }
        return null;
    }

    private int parseMultipartAndSave(InputStream is, String boundary, Path targetDir,
                                       String clientIp, String username) throws IOException {
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        byte[] allBytes = is.readAllBytes();

        if (allBytes.length > MAX_UPLOAD_SIZE) {
            throw new IOException("Upload too large (max " + formatSize(MAX_UPLOAD_SIZE) + ")");
        }

        int count = 0;
        int pos = 0;

        while (pos < allBytes.length) {
            int bStart = indexOf(allBytes, boundaryBytes, pos);
            if (bStart < 0) break;
            pos = bStart + boundaryBytes.length;
            if (pos + 2 <= allBytes.length && allBytes[pos] == '-' && allBytes[pos + 1] == '-') break;
            if (pos < allBytes.length && allBytes[pos] == '\r') pos++;
            if (pos < allBytes.length && allBytes[pos] == '\n') pos++;

            int headerEnd = indexOf(allBytes, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), pos);
            if (headerEnd < 0) break;
            String headers = new String(allBytes, pos, headerEnd - pos, StandardCharsets.UTF_8);
            pos = headerEnd + 4;

            String filename = extractFilename(headers);
            if (filename == null || filename.isBlank()) continue;

            filename = Path.of(filename).getFileName().toString();
            if (filename.isBlank() || filename.startsWith(".")) filename = "upload_" + System.currentTimeMillis();

            int partEnd = indexOf(allBytes, boundaryBytes, pos);
            if (partEnd < 0) partEnd = allBytes.length;
            int dataEnd = partEnd;
            if (dataEnd >= 2 && allBytes[dataEnd - 2] == '\r' && allBytes[dataEnd - 1] == '\n') dataEnd -= 2;

            Path dest = uniquePath(targetDir.resolve(filename));
            writeBytes(dest, allBytes, pos, dataEnd - pos);

            logger.accept("   📥 Saved: " + dest.getFileName() + " (" + formatSize(dataEnd - pos) + ")");
            count++;
            pos = partEnd;
        }
        return count;
    }

    private static void writeBytes(Path dest, byte[] data, int offset, int length) throws IOException {
        try (OutputStream os = Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            os.write(data, offset, length);
        }
    }

    private String extractFilename(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().contains("content-disposition")) {
                int idx = line.indexOf("filename=\"");
                if (idx >= 0) {
                    int end = line.indexOf("\"", idx + 10);
                    if (end > idx) return line.substring(idx + 10, end);
                }
            }
        }
        return null;
    }

    private Path uniquePath(Path path) {
        if (!Files.exists(path)) return path;
        String name = path.getFileName().toString();
        String base, ext;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) { base = name.substring(0, dotIdx); ext = name.substring(dotIdx); }
        else { base = name; ext = ""; }
        for (int i = 1; i < 10000; i++) {
            Path c = path.getParent().resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(c)) return c;
        }
        return path.getParent().resolve(base + "_" + System.currentTimeMillis() + ext);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Page Builders
    // ══════════════════════════════════════════════════════════════════════════

    private String buildFolderListPage(List<SharedFolder> folders, String username) {
        StringBuilder cards = new StringBuilder();
        for (SharedFolder f : folders) {
            String badge = f.isGuestAccessible()
                ? "<span class=\"badge public\">Public</span>"
                : "<span class=\"badge private\">Login Required</span>";
            String rwBadge = f.isReadOnly()
                ? "<span class=\"badge ro\">Read Only</span>"
                : "<span class=\"badge rw\">Upload OK</span>";
            cards.append("""
                <a class="folder-card" href="/browse/%s/">
                  <div class="fc-icon">📁</div>
                  <div class="fc-info">
                    <div class="fc-name">%s</div>
                    <div class="fc-path">%s</div>
                    <div class="fc-badges">%s %s</div>
                  </div>
                  <div class="fc-arrow">›</div>
                </a>
                """.formatted(f.getSafeAlias(), f.getAlias(),
                    f.getDisplayPath(), badge, rwBadge));
        }

        String authLink = username != null
            ? "<a href=\"/logout\" class=\"header-btn logout-btn\">Sign Out</a>"
            : "<a href=\"/login\" class=\"header-btn login-btn\">Sign In</a>";

        return PAGE_TEMPLATE
            .replace("{{TITLE}}", "LocoDrive")
            .replace("{{HEADING}}", "Shared Folders")
            .replace("{{BREADCRUMB}}", breadcrumb(null, null, null))
            .replace("{{USERNAME}}", username != null ? username : "Guest")
            .replace("{{AUTH_LINK}}", authLink)
            .replace("{{UPLOAD_SECTION}}", "")
            .replace("{{DOWNLOAD_ALIAS}}", "")
            .replace("{{CONTENT}}", "<div class=\"folder-grid\">" + cards + "</div>");
    }

    private String buildDirectoryPage(SharedFolder folder, Path dir, Path root, String subPath,
                                       String username, boolean canUpload) throws IOException {
        List<Path> entries;
        try (var stream = Files.list(dir)) {
            entries = stream.sorted((a, b) -> {
                boolean aDir = Files.isDirectory(a);
                boolean bDir = Files.isDirectory(b);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).toList();
        }

        String baseUrl = "/browse/" + folder.getSafeAlias() + "/";
        String crumb = breadcrumb(folder.getAlias(), baseUrl, subPath);

        String parentLink;
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
            String browseHref = isDir
                ? baseUrl + encode(relPath) + "/"
                : baseUrl + encode(relPath);
            String downloadHref = isDir
                ? "/download/" + folder.getSafeAlias() + "/" + encode(relPath) + "/"
                : baseUrl + encode(relPath);
            String icon = isDir ? "📁" : getFileIcon(name);
            String size = isDir ? "—" : formatSize(Files.size(entry));
            String modified = DT_FMT.format(LocalDateTime.ofInstant(
                Files.getLastModifiedTime(entry).toInstant(), java.time.ZoneId.systemDefault()));
            String dlTitle = isDir ? "Download as ZIP" : "Download";

            rows.append("""
                <div class="file-row %s" data-path="%s">
                  <label class="fi-check" onclick="event.stopPropagation()">
                    <input type="checkbox" class="sel-cb" value="%s">
                    <span class="checkmark"></span>
                  </label>
                  <a href="%s" class="fi-link">
                    <span class="fi-icon">%s</span>
                    <span class="fi-name">%s</span>
                    <span class="fi-size">%s</span>
                    <span class="fi-date">%s</span>
                  </a>
                  <a href="%s" class="dl-fab" title="%s" onclick="event.stopPropagation()">
                    <img src="/assets/download.png">
                  </a>
                </div>
                """.formatted(
                    isDir ? "dir" : "file", encode(relPath),
                    encode(relPath),
                    browseHref, icon, name, size, modified,
                    downloadHref, dlTitle));
        }

        // Upload section
        String uploadSection = "";
        String uploadUrl = "/browse/" + folder.getSafeAlias() + "/" + encode(subPath);
        if (!uploadUrl.endsWith("/")) uploadUrl += "/";
        if (canUpload) {
            uploadSection = """
                <button class="upload-fab" onclick="document.getElementById('upload-modal').classList.add('active')" title="Upload Files">
                  <img src="/assets/upload.png">
                </button>
                <div class="modal-overlay" id="upload-modal" onclick="if(event.target==this) this.classList.remove('active')">
                  <div class="modal-content">
                    <div class="modal-header">
                      <h3>Upload Files</h3>
                      <button class="modal-close" onclick="document.getElementById('upload-modal').classList.remove('active')">✕</button>
                    </div>
                    <form method="POST" action="%s" enctype="multipart/form-data" id="upload-form">
                      <div class="upload-drop" id="drop-area">
                        <img src="/assets/upload.png" style="width:64px;height:64px;object-fit:contain;margin-bottom:12px;opacity:0.9;pointer-events:none">
                        <div class="upload-text">Drag &amp; drop files here</div>
                        <div class="upload-or">or</div>
                        <label class="upload-btn">
                          Choose Files
                          <input type="file" name="file" multiple id="file-input" style="display:none">
                        </label>
                      </div>
                      <div class="upload-progress" id="upload-progress" style="display:none">
                        <div class="progress-bar"><div class="progress-fill" id="progress-fill"></div></div>
                        <div class="progress-text" id="progress-text">Uploading...</div>
                      </div>
                    </form>
                  </div>
                </div>
                """.formatted(uploadUrl);
        }

        String authLink = username != null
            ? "<a href=\"/logout\" class=\"header-btn logout-btn\">Sign Out</a>"
            : "<a href=\"/login\" class=\"header-btn login-btn\">Sign In</a>";

        return PAGE_TEMPLATE
            .replace("{{TITLE}}", folder.getAlias() + " — LocoDrive")
            .replace("{{HEADING}}", folder.getAlias())
            .replace("{{BREADCRUMB}}", crumb)
            .replace("{{USERNAME}}", username != null ? username : "Guest")
            .replace("{{AUTH_LINK}}", authLink)
            .replace("{{UPLOAD_SECTION}}", uploadSection)
            .replace("{{DOWNLOAD_ALIAS}}", folder.getSafeAlias())
            .replace("{{CONTENT}}", "<div class=\"file-list\">" + rows + "</div>");
    }

    // ── File Serving ──────────────────────────────────────────────────────────
    private void serveFile(HttpExchange exchange, Path file, String name) throws IOException {
        String mime = Files.probeContentType(file);
        if (mime == null) mime = "application/octet-stream";

        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");

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
        String html = """
            <!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1.0">
            <title>Error — LocoDrive</title><style>
            *{margin:0;padding:0;box-sizing:border-box}
            body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
            background:#0F1117;color:#E8EAF6;min-height:100vh;display:flex;
            align-items:center;justify-content:center;padding:20px}
            .err{text-align:center;max-width:420px}
            h1{font-size:64px;font-weight:800;color:#4F9CF9;line-height:1}
            p{font-size:15px;color:#9EA3B8;margin:14px 0 22px}
            a{color:#4F9CF9;text-decoration:none;font-weight:600;font-size:14px;
            padding:10px 22px;border:1px solid rgba(79,156,249,.3);border-radius:10px;
            display:inline-block;transition:all .2s}
            a:hover{background:rgba(79,156,249,.1);border-color:#4F9CF9}
            </style></head><body><div class="err"><h1>%d</h1><p>%s</p>
            <a href="/browse/">← Back to folders</a></div></body></html>
            """.formatted(code, msg);
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

    // ══════════════════════════════════════════════════════════════════════════
    //  HTML Page Template — Fully Responsive
    // ══════════════════════════════════════════════════════════════════════════
    private static final String PAGE_TEMPLATE = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <title>{{TITLE}}</title>
          <style>
            /* ── Reset & Base ─────────────────────────────────────── */
            *{margin:0;padding:0;box-sizing:border-box}
            html{font-size:16px;-webkit-text-size-adjust:100%}
            body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,
                 'Helvetica Neue',Arial,sans-serif;
                 background:#0F1117;color:#E8EAF6;min-height:100vh;
                 overflow-x:hidden}

            /* ── Header ───────────────────────────────────────────── */
            header{background:#1A1D2E;border-bottom:1px solid rgba(255,255,255,.08);
                   padding:14px 20px;display:flex;align-items:center;
                   justify-content:space-between;position:sticky;top:0;z-index:100;
                   flex-wrap:wrap;gap:8px}
            .logo{display:flex;align-items:center;gap:8px;font-weight:700;
                  font-size:17px;color:#fff;white-space:nowrap}
            .logo-icon{font-size:20px}
            .user-info{display:flex;align-items:center;gap:10px;font-size:13px;
                       color:#9EA3B8}
            .header-btn{padding:6px 14px;border-radius:8px;text-decoration:none;
                        font-size:12px;font-weight:600;white-space:nowrap;
                        transition:all .2s}
            .logout-btn{background:rgba(244,67,54,.1);border:1px solid rgba(244,67,54,.25);
                        color:#EF9A9A}
            .logout-btn:hover{background:rgba(244,67,54,.2)}
            .login-btn{background:#4F9CF9;color:#fff}
            .login-btn:hover{background:#6BAFFF}

            /* ── Breadcrumb ────────────────────────────────────────── */
            .breadcrumb{padding:10px 20px;font-size:13px;color:#9EA3B8;
                        background:rgba(255,255,255,.02);
                        border-bottom:1px solid rgba(255,255,255,.05);
                        overflow-x:auto;white-space:nowrap;
                        -webkit-overflow-scrolling:touch}
            .crumb{color:#4F9CF9;text-decoration:none}
            .crumb:hover{text-decoration:underline}
            .crumb-sep{margin:0 5px;color:#444}

            /* ── Main Content ─────────────────────────────────────── */
            main{padding:20px;max-width:1200px;margin:0 auto;width:100%;
                 padding-bottom:80px}
            h2{font-size:20px;font-weight:700;color:#fff;margin-bottom:16px}

            /* ── Folder Grid ──────────────────────────────────────── */
            .folder-grid{display:grid;
                         grid-template-columns:repeat(auto-fill,minmax(280px,1fr));
                         gap:14px}
            .folder-card{background:#1A1D2E;
                         border:1px solid rgba(255,255,255,.08);
                         border-radius:14px;padding:18px;
                         text-decoration:none;color:#E8EAF6;
                         display:flex;align-items:center;gap:14px;
                         transition:border-color .2s,transform .15s,box-shadow .2s}
            .folder-card:hover{border-color:#4F9CF9;transform:translateY(-2px);
                               box-shadow:0 8px 24px rgba(79,156,249,.08)}
            .fc-icon{font-size:32px;flex-shrink:0}
            .fc-info{flex:1;overflow:hidden;min-width:0}
            .fc-name{font-size:15px;font-weight:600;color:#fff;margin-bottom:3px;
                     white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .fc-path{font-size:11px;color:#9EA3B8;white-space:nowrap;overflow:hidden;
                     text-overflow:ellipsis;margin-bottom:6px}
            .fc-badges{display:flex;gap:6px;flex-wrap:wrap}
            .fc-arrow{font-size:22px;flex-shrink:0;color:#555;font-weight:300}
            .badge{font-size:10px;padding:3px 8px;border-radius:6px;font-weight:600;
                   white-space:nowrap}
            .badge.public{background:rgba(76,175,80,.15);color:#81C784}
            .badge.private{background:rgba(255,152,0,.15);color:#FFB74D}
            .badge.ro{background:rgba(158,158,158,.12);color:#9EA3B8}
            .badge.rw{background:rgba(79,156,249,.12);color:#4F9CF9}

            .upload-drop.drag-over{border-color:#4F9CF9;background:rgba(79,156,249,.06)}
            .upload-text{font-size:14px;color:#9EA3B8;margin-bottom:4px}
            .upload-or{font-size:12px;color:#666;margin:6px 0}
            .upload-btn{display:inline-block;background:#4F9CF9;color:#fff;
                        padding:10px 24px;border-radius:10px;font-size:14px;
                        font-weight:600;cursor:pointer;transition:background .2s}
            .upload-btn:hover{background:#6BAFFF}
            .upload-progress{margin-top:12px}
            .progress-bar{height:6px;background:rgba(255,255,255,.08);
                          border-radius:6px;overflow:hidden}
            .progress-fill{height:100%;width:0%;background:linear-gradient(90deg,#4F9CF9,#6BAFFF);
                           border-radius:6px;transition:width .3s}
            .progress-text{font-size:12px;color:#9EA3B8;margin-top:6px;text-align:center}

            /* ── File List ─────────────────────────────────────────── */
            .file-list{background:#1A1D2E;border:1px solid rgba(255,255,255,.08);
                       border-radius:14px;overflow:hidden}
            .file-row{display:flex;align-items:center;
                      border-bottom:1px solid rgba(255,255,255,.05);
                      transition:background .15s;gap:0;min-height:52px;
                      padding:0 6px 0 0}
            .file-row:last-child{border-bottom:none}
            .file-row:hover{background:rgba(79,156,249,.04)}
            .file-row.parent{padding:12px 16px;color:#9EA3B8;font-size:14px;
                             text-decoration:none;gap:10px}
            .file-row.dir .fi-link{color:#fff}

            /* Checkbox */
            .fi-check{display:flex;align-items:center;justify-content:center;
                      width:44px;flex-shrink:0;cursor:pointer;align-self:stretch}
            .fi-check input{display:none}
            .checkmark{width:18px;height:18px;border:2px solid rgba(255,255,255,.15);
                       border-radius:5px;transition:all .15s;display:flex;
                       align-items:center;justify-content:center}
            .fi-check input:checked~.checkmark{background:#4F9CF9;border-color:#4F9CF9}
            .fi-check input:checked~.checkmark::after{content:'✓';color:#fff;
                       font-size:12px;font-weight:700}

            /* Link area (clickable to browse) */
            .fi-link{display:flex;align-items:center;gap:10px;flex:1;
                     text-decoration:none;color:#E8EAF6;padding:12px 8px;
                     min-width:0}
            .fi-icon{font-size:18px;width:26px;text-align:center;flex-shrink:0}
            .fi-name{flex:1;font-size:14px;word-break:break-word;min-width:0}
            .fi-size{min-width:55px;text-align:right;font-size:12px;color:#9EA3B8;
                     flex-shrink:0}
            .fi-date{min-width:75px;text-align:right;font-size:12px;color:#9EA3B8;
                     flex-shrink:0}

            /* Download FAB (per-item) */
            .dl-fab{display:flex;align-items:center;justify-content:center;
                    width:34px;height:34px;text-decoration:none;
                    flex-shrink:0;background:#FFFFFF;border:none;border-radius:10px;
                    box-shadow:0 2px 8px rgba(0,0,0,.2);
                    transition:all .2s cubic-bezier(.4,0,.2,1);margin:0 6px;padding:6px;
                    cursor:pointer}
            .dl-fab img{width:100%;height:100%;object-fit:contain;pointer-events:none}
            .dl-fab:hover{background:#F0F0F0;transform:translateY(-2px);box-shadow:0 4px 12px rgba(0,0,0,.3)}

            /* ── Floating Action Bar (batch download) ─────────────── */
            .fab-bar{position:fixed;bottom:0;left:0;right:0;
                     background:rgba(26,29,46,.96);
                     border-top:1px solid rgba(79,156,249,.2);
                     backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);
                     padding:12px 20px;display:none;align-items:center;
                     justify-content:space-between;z-index:200;gap:12px}
            .fab-bar.active{display:flex}
            .fab-info{font-size:13px;color:#9EA3B8}
            .fab-info strong{color:#fff}
            .fab-actions{display:flex;gap:8px}
            .fab-btn{padding:9px 20px;border-radius:10px;border:none;
                     font-size:13px;font-weight:600;cursor:pointer;
                     transition:all .2s;display:flex;align-items:center;gap:6px}
            .fab-dl{background:#4F9CF9;color:#fff}
            .fab-dl:hover{background:#6BAFFF}
            .fab-clear{background:rgba(255,255,255,.06);color:#9EA3B8;
                       border:1px solid rgba(255,255,255,.1)}
            .fab-clear:hover{background:rgba(255,255,255,.12);color:#fff}

            /* ── Floating Upload Button (White) ────────────── */
            .upload-fab{position:fixed;bottom:30px;right:30px;width:64px;height:64px;
                        background:#FFFFFF;border:none;border-radius:20px;
                        box-shadow:0 12px 32px rgba(0,0,0,.4);
                        cursor:pointer;display:flex;align-items:center;justify-content:center;
                        transition:all .3s cubic-bezier(.34,1.56,.64,1);z-index:90;padding:15px}
            .upload-fab img{width:100%;height:100%;object-fit:contain;pointer-events:none}
            .upload-fab:hover{transform:translateY(-6px);background:#F8F8F8;
                              box-shadow:0 20px 40px rgba(0,0,0,.5)}

            /* ── Modal Overlay ────────────────────────────────────── */
            .modal-overlay{position:fixed;top:0;left:0;right:0;bottom:0;
                           background:rgba(0,0,0,.7);backdrop-filter:blur(4px);
                           display:flex;align-items:center;justify-content:center;
                           z-index:300;opacity:0;visibility:hidden;transition:all .2s}
            .modal-overlay.active{opacity:1;visibility:visible}
            .modal-content{background:#1A1D2E;border:1px solid rgba(255,255,255,.08);
                           border-radius:16px;width:90%;max-width:500px;padding:24px;
                           transform:translateY(20px);transition:all .2s}
            .modal-overlay.active .modal-content{transform:translateY(0)}
            .modal-header{display:flex;justify-content:space-between;align-items:center;
                          margin-bottom:20px}
            .modal-header h3{font-size:18px;font-weight:600;margin:0}
            .modal-close{background:none;border:none;color:#9EA3B8;font-size:24px;
                         cursor:pointer;padding:4px;line-height:1}
            .modal-close:hover{color:#fff}

            /* ── Footer Badge ─────────────────────────────────────── */
            .badge-lnonly{position:fixed;bottom:12px;left:12px;background:#1A1D2E;
                          border:1px solid rgba(255,255,255,.08);border-radius:8px;
                          padding:5px 10px;font-size:10px;color:#9EA3B8;z-index:50}

            /* ══ Responsive: Tablets (< 768px) ════════════════════ */
            @media(max-width:768px){
              header{padding:12px 16px}
              main{padding:16px;padding-bottom:80px}
              h2{font-size:18px;margin-bottom:12px}
              .folder-grid{grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px}
              .folder-card{padding:14px}
              .fi-date{display:none}
              .upload-drop{padding:20px 16px}
            }

            /* ══ Responsive: Phones (< 480px) ════════════════════ */
            @media(max-width:480px){
              header{padding:10px 12px}
              .logo{font-size:15px}
              .breadcrumb{padding:8px 12px;font-size:12px}
              main{padding:12px;padding-bottom:80px}
              h2{font-size:16px;margin-bottom:10px}
              .folder-grid{grid-template-columns:1fr;gap:10px}
              .folder-card{padding:12px;gap:10px}
              .fc-icon{font-size:24px}
              .fc-name{font-size:14px}
              .fi-check{width:36px}
              .checkmark{width:16px;height:16px}
              .fi-link{padding:10px 6px;gap:8px}
              .fi-icon{font-size:16px;width:22px}
              .fi-name{font-size:13px}
              .fi-size,.fi-date{display:none}
              .dl-fab{width:32px;height:32px;font-size:13px;margin:0 4px}
              .upload-drop{padding:16px 12px}
              .upload-icon{font-size:24px}
              .upload-text{font-size:13px}
              .upload-btn{padding:9px 18px;font-size:13px}
              .badge-lnonly{display:none}
              .fab-bar{padding:10px 12px}
              .fab-info{font-size:12px}
              .fab-btn{padding:8px 14px;font-size:12px}
              .upload-fab{bottom:20px;right:20px;width:56px;height:56px;border-radius:16px;padding:13px}
            }
          </style>
        </head>
        <body>
          <header>
            <div class="logo"><span class="logo-icon">🗄️</span> LocoDrive</div>
            <div class="user-info">
              <span>👤 {{USERNAME}}</span>
              {{AUTH_LINK}}
            </div>
          </header>
          <div class="breadcrumb">{{BREADCRUMB}}</div>
          <main>
            <h2>{{HEADING}}</h2>
            {{UPLOAD_SECTION}}
            {{CONTENT}}
          </main>
          <div class="badge-lnonly" id="badge-ln">🔒 Local Network Only</div>

          <!-- Floating Action Bar for batch download -->
          <div class="fab-bar" id="fab-bar">
            <div class="fab-info"><strong id="sel-count">0</strong> item(s) selected</div>
            <div class="fab-actions">
              <button class="fab-btn fab-clear" onclick="clearSelection()">✕ Clear</button>
              <button class="fab-btn fab-dl" onclick="downloadSelected()">⬇ Download ZIP</button>
            </div>
          </div>

          <script>
          (function(){
            /* ── Upload (drag & drop) ──────────────────────────── */
            var dropArea = document.getElementById('drop-area');
            var fileInput = document.getElementById('file-input');
            var form = document.getElementById('upload-form');
            var progressDiv = document.getElementById('upload-progress');
            var progressFill = document.getElementById('progress-fill');
            var progressText = document.getElementById('progress-text');

            if (dropArea && fileInput) {
              ['dragenter','dragover'].forEach(function(evt){
                dropArea.addEventListener(evt, function(e){
                  e.preventDefault(); e.stopPropagation();
                  dropArea.classList.add('drag-over');
                });
              });
              ['dragleave','drop'].forEach(function(evt){
                dropArea.addEventListener(evt, function(e){
                  e.preventDefault(); e.stopPropagation();
                  dropArea.classList.remove('drag-over');
                });
              });
              dropArea.addEventListener('drop', function(e){
                if (e.dataTransfer.files.length > 0) uploadFiles(e.dataTransfer.files);
              });
              fileInput.addEventListener('change', function(){
                if (fileInput.files.length > 0) uploadFiles(fileInput.files);
              });
            }

            function uploadFiles(files){
              dropArea.style.display = 'none';
              progressDiv.style.display = 'block';
              
              var totalSize = 0;
              for (var i = 0; i < files.length; i++) totalSize += files[i].size;
              
              var totalLoaded = 0;
              var currentFileIndex = 0;
              var startTime = Date.now();
              var lastTime = startTime;
              var lastTotalLoaded = 0;
              var speedText = '';
              
              var CHUNK_SIZE = 5 * 1024 * 1024; // 5MB chunks
              var MAX_CONCURRENT = 4; // Concurrent chunks
              
              function uploadNext() {
                 if (currentFileIndex >= files.length) {
                    progressFill.style.width = '100%';
                    progressText.textContent = 'Upload complete! Refreshing...';
                    setTimeout(function(){ location.reload(); }, 600);
                    return;
                 }
                 
                 var file = files[currentFileIndex];
                 var fileStartLoaded = totalLoaded;
                 var uploadId = Date.now() + "_" + currentFileIndex;
                 
                 var chunks = [];
                 for (var offset = 0; offset < file.size; offset += CHUNK_SIZE) {
                     chunks.push({ offset: offset, blob: file.slice(offset, offset + CHUNK_SIZE) });
                 }
                 if (chunks.length === 0) chunks.push({ offset: 0, blob: file.slice(0, 0) });
                 
                 var chunksCompleted = 0;
                 var currentChunkIdx = 0;
                 var chunkLoadedMap = {};
                 var errorOccurred = false;
                 
                 function updateChunkProgress() {
                     var fileLoaded = 0;
                     for(var k in chunkLoadedMap) fileLoaded += chunkLoadedMap[k];
                     totalLoaded = fileStartLoaded + fileLoaded;
                     var pct = totalSize === 0 ? 100 : Math.round((totalLoaded / totalSize) * 100);
                     progressFill.style.width = pct + '%';
                     
                     var now = Date.now();
                     var timeDelta = (now - lastTime) / 1000;
                     if (timeDelta > 0.5 || (speedText === '' && timeDelta > 0.1)) {
                         var loadedDelta = totalLoaded - lastTotalLoaded;
                         var speedBps = loadedDelta / Math.max(timeDelta, 0.001);
                         if (speedBps > 1024 * 1024) speedText = (speedBps / (1024 * 1024)).toFixed(1) + ' MB/s';
                         else if (speedBps > 1024) speedText = (speedBps / 1024).toFixed(1) + ' KB/s';
                         else speedText = Math.round(speedBps) + ' B/s';
                         lastTime = now;
                         lastTotalLoaded = totalLoaded;
                     }
                     var spd = speedText ? ' - ' + speedText : '';
                     var multiInfo = files.length > 1 ? ' (' + (currentFileIndex+1) + '/' + files.length + ') ' : ' ';
                     progressText.textContent = 'Uploading' + multiInfo + pct + '%' + spd;
                 }
                 
                 function handleError(msg) {
                     if (errorOccurred) return;
                     errorOccurred = true;
                     progressText.textContent = 'Upload failed: ' + msg;
                     progressFill.style.background = '#EF5350';
                     setTimeout(function(){
                          dropArea.style.display = '';
                          progressDiv.style.display = 'none';
                          progressFill.style.width = '0%';
                          progressFill.style.background = '';
                     }, 3000);
                 }
                 
                 function uploadChunk() {
                     if (errorOccurred) return;
                     if (currentChunkIdx >= chunks.length) return;
                     
                     var chunk = chunks[currentChunkIdx++];
                     var xhr = new XMLHttpRequest();
                     xhr.open('POST', form.action, true);
                     xhr.setRequestHeader('X-File-Name', encodeURIComponent(file.name));
                     xhr.setRequestHeader('X-Upload-Id', uploadId);
                     xhr.setRequestHeader('X-Chunk-Offset', chunk.offset);
                     xhr.setRequestHeader('Content-Type', 'application/octet-stream');
                     xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                     
                     xhr.upload.addEventListener('progress', function(e) {
                         chunkLoadedMap[chunk.offset] = e.loaded;
                         updateChunkProgress();
                     });
                     
                     xhr.onload = function() {
                         if (errorOccurred) return;
                         if (xhr.status === 200 || xhr.status === 302) {
                             chunkLoadedMap[chunk.offset] = chunk.blob.size;
                             chunksCompleted++;
                             if (chunksCompleted === chunks.length) {
                                 var fXhr = new XMLHttpRequest();
                                 fXhr.open('POST', form.action, true);
                                 fXhr.setRequestHeader('X-File-Name', encodeURIComponent(file.name));
                                 fXhr.setRequestHeader('X-Upload-Id', uploadId);
                                 fXhr.setRequestHeader('X-Upload-Finish', 'true');
                                 fXhr.setRequestHeader('Content-Type', 'application/octet-stream');
                                 fXhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                                 fXhr.onload = function() {
                                     currentFileIndex++;
                                     uploadNext();
                                 };
                                 fXhr.send();
                             } else {
                                 uploadChunk();
                             }
                         } else {
                             handleError(xhr.responseText);
                         }
                     };
                     xhr.onerror = function() { handleError("Network error"); };
                     xhr.send(chunk.blob);
                 }
                 
                 for (var i = 0; i < MAX_CONCURRENT; i++) {
                     uploadChunk();
                 }
              }
              
              uploadNext();
            }

            /* ── Selection & batch download ────────────────────── */
            var fabBar = document.getElementById('fab-bar');
            var selCount = document.getElementById('sel-count');
            var badgeLn = document.getElementById('badge-ln');
            var alias = '{{DOWNLOAD_ALIAS}}';

            document.querySelectorAll('.sel-cb').forEach(function(cb){
              cb.addEventListener('change', updateFab);
            });

            function getSelected(){
              var items = [];
              document.querySelectorAll('.sel-cb:checked').forEach(function(cb){
                items.push(cb.value);
              });
              return items;
            }

            function updateFab(){
              var items = getSelected();
              if (items.length > 0) {
                fabBar.classList.add('active');
                if (badgeLn) badgeLn.style.display = 'none';
                selCount.textContent = items.length;
              } else {
                fabBar.classList.remove('active');
                if (badgeLn) badgeLn.style.display = '';
              }
            }

            window.clearSelection = function(){
              document.querySelectorAll('.sel-cb:checked').forEach(function(cb){
                cb.checked = false;
              });
              updateFab();
            };

            window.downloadSelected = function(){
              var items = getSelected();
              if (items.length === 0) return;
              if (!alias) return;

              // Use XHR to POST file list and trigger download
              var xhr = new XMLHttpRequest();
              xhr.open('POST', '/download/' + alias + '/', true);
              xhr.responseType = 'blob';
              xhr.onload = function(){
                if (xhr.status === 200) {
                  var blob = xhr.response;
                  var url = URL.createObjectURL(blob);
                  var a = document.createElement('a');
                  a.href = url;
                  a.download = 'LocoDrive-download.zip';
                  document.body.appendChild(a);
                  a.click();
                  document.body.removeChild(a);
                  URL.revokeObjectURL(url);
                  clearSelection();
                }
              };
              xhr.send(items.join('\\n'));
            };
          })();
          </script>
        </body>
        </html>
        """;
}
