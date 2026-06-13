package com.navicode.runtime.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navicode.runtime.CancellationContext;
import com.navicode.runtime.CancellationToken;
import com.navicode.runtime.task.TaskRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RuntimeApiServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RuntimeThreadStore store;
    private final RuntimeTurnRunner runner;
    private final String apiKey;
    private final HttpServer server;
    private final Map<String, RuntimeThreadSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "navicode-runtime-api");
        thread.setDaemon(true);
        return thread;
    });

    public RuntimeApiServer(RuntimeThreadStore store, TaskRunner runner, int port, String apiKey) throws IOException {
        this(store, (RuntimeTurnRunner) context -> runner.run(context.input()), port, apiKey);
    }

    public RuntimeApiServer(RuntimeThreadStore store, RuntimeTurnRunner runner, int port, String apiKey) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Runtime API 需要配置 NAVICODE_RUNTIME_API_KEY 或 -Dnavicode.runtime.api.key");
        }
        this.store = store;
        this.runner = runner;
        this.apiKey = apiKey;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/v1/threads", this::handleThreads);
        this.server.setExecutor(executor);
    }

    public static String configuredApiKey() {
        String configured = System.getProperty("navicode.runtime.api.key");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("NAVICODE_RUNTIME_API_KEY");
        }
        return configured;
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleThreads(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                writeJson(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && "/v1/threads".equals(path)) {
                String id = store.createThread();
                writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"object\":\"thread\"}");
                return;
            }
            if ("POST".equals(method) && path.matches("/v1/threads/[^/]+/turns")) {
                handleTurn(exchange, threadId(path));
                return;
            }
            if ("POST".equals(method) && path.matches("/v1/threads/[^/]+/cancel")) {
                handleCancel(exchange, threadId(path));
                return;
            }
            if ("GET".equals(method) && path.matches("/v1/threads/[^/]+/events")) {
                handleEvents(exchange, threadId(path));
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"not_found\"}");
        } catch (Exception e) {
            writeJson(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleTurn(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, "{\"error\":\"thread_not_found\"}");
            return;
        }
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        String input = body.path("input").asText("");
        if (input.isBlank()) {
            writeJson(exchange, 400, "{\"error\":\"input_required\"}");
            return;
        }
        String cwd = body.path("cwd").asText("");
        if (!cwd.isBlank()) {
            store.updateThreadCwd(threadId, cwd);
        }
        String turnId = "turn_" + Long.toHexString(System.nanoTime());
        store.updateThreadTurn(threadId, turnId);
        RuntimeThreadSession session = sessions.computeIfAbsent(threadId, RuntimeThreadSession::new);
        store.appendEvent(threadId, "turn.queued",
                "{\"turn_id\":\"" + turnId + "\",\"input\":\"" + escape(input) + "\"}");
        session.submit(() -> runTurn(session, threadId, turnId, input, cwd));
        writeJson(exchange, 202, "{\"id\":\"" + turnId + "\",\"object\":\"turn\",\"status\":\"queued\"}");
    }

    private void handleCancel(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, "{\"error\":\"thread_not_found\"}");
            return;
        }
        RuntimeThreadSession session = sessions.get(threadId);
        if (session == null || !session.cancelActive()) {
            writeJson(exchange, 409, "{\"error\":\"no_running_turn\"}");
            return;
        }
        writeJson(exchange, 200, "{\"object\":\"thread.cancel\",\"status\":\"cancelling\"}");
    }

    private void runTurn(RuntimeThreadSession session, String threadId, String turnId, String input, String cwd) {
        CancellationToken token = CancellationContext.startRun();
        session.markActive(turnId, token);
        store.appendEvent(threadId, "turn.started",
                "{\"turn_id\":\"" + turnId + "\",\"input\":\"" + escape(input) + "\"}");
        RuntimeTurnContext context = new RuntimeTurnContext(
                threadId,
                turnId,
                input,
                cwd,
                (eventType, dataJson) -> store.appendEvent(threadId, eventType, dataJson));
        try {
            String result = runner.run(context);
            if (result != null && !result.isBlank()) {
                context.emitMessageDelta(result);
            }
            if (CancellationContext.isCancelled()) {
                store.appendEvent(threadId, "turn.cancelled",
                        "{\"turn_id\":\"" + turnId + "\",\"status\":\"cancelled\"}");
            } else {
                store.appendEvent(threadId, "turn.completed",
                        "{\"turn_id\":\"" + turnId + "\",\"status\":\"completed\"}");
            }
        } catch (Exception e) {
            if (CancellationContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                store.appendEvent(threadId, "turn.cancelled",
                        "{\"turn_id\":\"" + turnId + "\",\"status\":\"cancelled\"}");
            } else {
                store.appendEvent(threadId, "turn.failed",
                        "{\"turn_id\":\"" + turnId + "\",\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        } finally {
            session.clearActive(turnId);
            CancellationContext.clear(token);
        }
    }

    private void handleEvents(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, "{\"error\":\"thread_not_found\"}");
            return;
        }
        long after = parseAfter(exchange.getRequestURI().getQuery());
        List<RuntimeEvent> events = store.events(threadId, after);
        byte[] body = formatSse(events).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String direct = exchange.getRequestHeaders().getFirst("X-Navicode-API-Key");
        return ("Bearer " + apiKey).equals(auth) || apiKey.equals(direct);
    }

    private static String threadId(String path) {
        String[] parts = path.split("/");
        return parts.length >= 4 ? parts[3] : "";
    }

    private static long parseAfter(String query) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("after=")) {
                try {
                    return Long.parseLong(part.substring("after=".length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String formatSse(List<RuntimeEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (RuntimeEvent event : events) {
            sb.append("id: ").append(event.id()).append('\n');
            sb.append("event: ").append(event.type()).append('\n');
            sb.append("data: ").append(event.data()).append("\n\n");
        }
        return sb.toString();
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        sessions.values().forEach(RuntimeThreadSession::close);
    }

    private static final class RuntimeThreadSession implements AutoCloseable {
        private final ExecutorService executor;
        private volatile CancellationToken activeToken;
        private volatile String activeTurnId;

        private RuntimeThreadSession(String threadId) {
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "navicode-runtime-thread-" + threadId);
                thread.setDaemon(true);
                return thread;
            });
        }

        private Future<?> submit(Runnable task) {
            return executor.submit(task);
        }

        private synchronized void markActive(String turnId, CancellationToken token) {
            activeTurnId = turnId;
            activeToken = token;
        }

        private synchronized boolean cancelActive() {
            if (activeToken == null || activeTurnId == null) {
                return false;
            }
            activeToken.cancel();
            return true;
        }

        private synchronized void clearActive(String turnId) {
            if (turnId != null && turnId.equals(activeTurnId)) {
                activeTurnId = null;
                activeToken = null;
            }
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
