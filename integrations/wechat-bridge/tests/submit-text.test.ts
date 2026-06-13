import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { defaultConfig } from "../src/config.js";
import { submitTextForUser } from "../src/main.js";
import { RuntimeClient, type RuntimeEvent } from "../src/navicode/runtime-client.js";
import { ThreadMap } from "../src/navicode/thread-map.js";

class MockRuntime extends RuntimeClient {
  submitted: Array<{ threadId: string; input: string; cwd?: string }> = [];

  constructor(private readonly runtimeEvents: RuntimeEvent[]) {
    super({ baseUrl: "http://127.0.0.1:1", apiKey: "secret" });
  }

  override async createThread(): Promise<string> {
    return "thread_mock";
  }

  override async submitTurn(threadId: string, input: string, options: { cwd?: string } = {}): Promise<string> {
    this.submitted.push({ threadId, input, cwd: options.cwd });
    return "turn_mock";
  }

  override async getEvents(): Promise<RuntimeEvent[]> {
    return this.runtimeEvents;
  }
}

test("submitTextForUser submits text, reads runtime events, and splits output", async () => {
  const dataDir = mkdtempSync(join(tmpdir(), "navicode-wechat-"));
  try {
    const config = { ...defaultConfig(), dataDir, defaultCwd: dataDir, allowedRoots: [dataDir] };
    const threads = new ThreadMap(dataDir);
    const runtime = new MockRuntime([
      { id: 1, event: "message.delta", data: { content: "第一段\n\n第二段" } },
      { id: 2, event: "turn.completed", data: { status: "completed" } },
    ]);

    const chunks = await submitTextForUser("wechat_user", "hello", {
      config,
      runtime,
      threads,
      pollIntervalMs: 0,
      maxPolls: 1,
    });

    assert.deepEqual(chunks, ["第一段\n\n第二段"]);
    assert.deepEqual(runtime.submitted, [{ threadId: "thread_mock", input: "hello", cwd: dataDir }]);
    assert.equal(threads.get("wechat_user")?.runtimeThreadId, "thread_mock");
  } finally {
    rmSync(dataDir, { recursive: true, force: true });
  }
});
