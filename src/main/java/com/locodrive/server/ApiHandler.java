package com.locodrive.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Internal REST API for the Dashboard to query server status.
 * GET /api/status  → JSON: { running, sessions, logCount, uptime }
 */
public class ApiHandler implements HttpHandler {

    private final LocalFileServer server;
    private final long startTime = System.currentTimeMillis();

    public ApiHandler(LocalFileServer server) {
        this.server = server;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.endsWith("/status")) {
            handleStatus(exchange);
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

    private void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}
