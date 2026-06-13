import test from "node:test";
import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { RuntimeClient, parseSse } from "../src/navicode/runtime-client.js";

test("parses runtime SSE events", () => {
  const events = parseSse([
    "id: 1",
    "event: message.delta",
    "data: {\"content\":\"hello\"}",
    "",
  ].join("\n"));
  assert.equal(events.length, 1);
  assert.equal(events[0].id, 1);
  assert.equal(events[0].event, "message.delta");
  assert.equal(events[0].data.content, "hello");
});

test("runtime client can create, submit, read events, and cancel", async () => {
  const seen: string[] = [];
  const turnBodies: unknown[] = [];
  const server = createServer(async (req: IncomingMessage, res: ServerResponse) => {
    seen.push(`${req.method} ${req.url}`);
    res.setHeader("Content-Type", req.url?.includes("/events") ? "text/event-stream" : "application/json");
    if (req.method === "POST" && req.url === "/v1/threads") {
      res.end(JSON.stringify({ id: "thread_test" }));
      return;
    }
    if (req.method === "POST" && req.url === "/v1/threads/thread_test/turns") {
      turnBodies.push(JSON.parse(await readBody(req)) as unknown);
      res.statusCode = 202;
      res.end(JSON.stringify({ id: "turn_test" }));
      return;
    }
    if (req.method === "GET" && req.url === "/v1/threads/thread_test/events?after=0") {
      res.end("id: 1\nevent: message.delta\ndata: {\"content\":\"ok\"}\n\n");
      return;
    }
    if (req.method === "POST" && req.url === "/v1/threads/thread_test/cancel") {
      res.end(JSON.stringify({ status: "cancelling" }));
      return;
    }
    res.statusCode = 404;
    res.end("{}");
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  try {
    const address = server.address();
    assert.ok(address && typeof address === "object");
    const client = new RuntimeClient({ baseUrl: `http://127.0.0.1:${address.port}`, apiKey: "secret" });
    assert.equal(await client.createThread(), "thread_test");
    assert.equal(await client.submitTurn("thread_test", "hello", { cwd: "/tmp/navicode" }), "turn_test");
    assert.deepEqual(turnBodies, [{ input: "hello", cwd: "/tmp/navicode" }]);
    const events = await client.getEvents("thread_test");
    assert.equal(events[0].data.content, "ok");
    await client.cancelTurn("thread_test");
    assert.deepEqual(seen, [
      "POST /v1/threads",
      "POST /v1/threads/thread_test/turns",
      "GET /v1/threads/thread_test/events?after=0",
      "POST /v1/threads/thread_test/cancel",
    ]);
  } finally {
    server.close();
  }
});

async function readBody(req: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString("utf8");
}
