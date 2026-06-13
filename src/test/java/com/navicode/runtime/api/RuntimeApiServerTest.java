package com.navicode.runtime.api;

import com.navicode.runtime.CancellationContext;
import com.navicode.runtime.task.TaskRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeApiServerTest {

    @Test
    void exposesThreadTurnAndSseEvents(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, (TaskRunner) prompt -> "reply:" + prompt, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> created = client.send(request(base + "/v1/threads", "POST", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode());
            String threadId = extract(created.body(), "thread_");

            HttpResponse<String> turn = client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                            "{\"input\":\"hello\"}").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, turn.statusCode());

            String events = waitForEvents(client, base, threadId);
            assertTrue(events.contains("event: turn.queued"));
            assertTrue(events.contains("event: turn.started"));
            assertTrue(events.contains("event: message.delta"));
            assertTrue(events.contains("reply:hello"));
            assertTrue(events.contains("event: turn.completed"));
        }
    }

    @Test
    void reusesSessionStateForTurnsInSameThread(@TempDir Path tempDir) throws Exception {
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        RuntimeTurnRunner runner = context -> {
            int count = counts.computeIfAbsent(context.threadId(), ignored -> new AtomicInteger()).incrementAndGet();
            return "state:" + count + " input:" + context.input();
        };
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, runner, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String threadId = createThread(client, base);

            assertEquals(202, client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                            "{\"input\":\"first\"}").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            waitForBodyContaining(client, base, threadId, "state:1 input:first");

            assertEquals(202, client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                            "{\"input\":\"second\"}").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            String events = waitForBodyContaining(client, base, threadId, "state:2 input:second");

            assertTrue(events.contains("state:1 input:first"));
            assertTrue(events.contains("state:2 input:second"));
        }
    }

    @Test
    void forwardsTurnCwdToRunner(@TempDir Path tempDir) throws Exception {
        RuntimeTurnRunner runner = context -> "cwd:" + context.cwd();
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, runner, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String threadId = createThread(client, base);

            assertEquals(202, client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                            "{\"input\":\"where\",\"cwd\":\"" + jsonPath(tempDir) + "\"}").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

            String events = waitForBodyContaining(client, base, threadId, "cwd:" + jsonPath(tempDir));
            assertTrue(events.contains("event: message.delta"));
        }
    }

    @Test
    void cancelRunningTurnEmitsCancelledEvent(@TempDir Path tempDir) throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        RuntimeTurnRunner runner = context -> {
            running.countDown();
            while (!CancellationContext.isCancelled()) {
                Thread.sleep(20);
            }
            return "";
        };
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, runner, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String threadId = createThread(client, base);

            HttpResponse<String> turn = client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                            "{\"input\":\"long\"}").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, turn.statusCode());
            assertTrue(running.await(5, TimeUnit.SECONDS), "turn did not start");

            HttpResponse<String> cancel = client.send(request(base + "/v1/threads/" + threadId + "/cancel", "POST", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cancel.statusCode());

            String events = waitForBodyContaining(client, base, threadId, "event: turn.cancelled");
            assertTrue(events.contains("\"status\":\"cancelled\""));
        }
    }

    @Test
    void rejectsMissingApiKey(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, (TaskRunner) prompt -> "x", 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/threads"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
        }
    }

    private static HttpRequest.Builder request(String url, String method, String body) {
        HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer secret")
                .header("Content-Type", "application/json")
                .method(method, publisher);
    }

    private static String createThread(HttpClient client, String base) throws Exception {
        HttpResponse<String> created = client.send(request(base + "/v1/threads", "POST", "")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        return extract(created.body(), "thread_");
    }

    private static String waitForEvents(HttpClient client, String base, String threadId) throws Exception {
        return waitForBodyContaining(client, base, threadId, "turn.completed");
    }

    private static String waitForBodyContaining(HttpClient client, String base, String threadId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(request(base + "/v1/threads/" + threadId + "/events", "GET", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.body().contains(expected)) {
                return response.body();
            }
            Thread.sleep(30);
        }
        fail("events did not contain: " + expected);
        return "";
    }

    private static String extract(String body, String prefix) {
        int start = body.indexOf(prefix);
        assertTrue(start >= 0, body);
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }
}
