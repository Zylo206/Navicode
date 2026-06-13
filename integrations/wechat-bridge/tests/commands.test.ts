import test from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { defaultConfig } from "../src/config.js";
import { RuntimeClient } from "../src/navicode/runtime-client.js";
import { ThreadMap } from "../src/navicode/thread-map.js";
import { routeCommand } from "../src/commands/router.js";

class MockRuntime extends RuntimeClient {
  cancelled: string[] = [];

  constructor() {
    super({ baseUrl: "http://127.0.0.1:1", apiKey: "secret" });
  }

  override async cancelTurn(threadId: string): Promise<void> {
    this.cancelled.push(threadId);
  }
}

test("routes help and unknown commands", async () => {
  const ctx = makeContext();
  assert.match((await routeCommand({ ...ctx, text: "/help" })).reply ?? "", /\/status/);
  assert.match((await routeCommand({ ...ctx, text: "/nope" })).reply ?? "", /未知命令/);
  ctx.cleanup();
});

test("clears and stops the mapped runtime thread", async () => {
  const ctx = makeContext();
  ctx.threads.set("user", { runtimeThreadId: "thread_1" });
  assert.match((await routeCommand({ ...ctx, text: "/stop" })).reply ?? "", /已请求停止/);
  assert.deepEqual(ctx.runtime.cancelled, ["thread_1"]);
  assert.match((await routeCommand({ ...ctx, text: "/clear" })).reply ?? "", /已清除/);
  assert.equal(ctx.threads.get("user"), undefined);
  ctx.cleanup();
});

test("sets cwd only inside allowed roots", async () => {
  const ctx = makeContext();
  const allowed = join(ctx.dataDir, "allowed");
  const sibling = `${ctx.dataDir}-sibling`;
  ctx.config.allowedRoots = [ctx.dataDir];
  mkdirSync(sibling);
  assert.match((await routeCommand({ ...ctx, text: `/cwd ${allowed}` })).reply ?? "", /目录不存在/);
  assert.match((await routeCommand({ ...ctx, text: `/cwd ${ctx.dataDir}` })).reply ?? "", /已设置工作目录/);
  assert.match((await routeCommand({ ...ctx, text: `/cwd ${sibling}` })).reply ?? "", /allowedRoots/);
  rmSync(sibling, { recursive: true, force: true });
  ctx.cleanup();
});

function makeContext() {
  const dataDir = mkdtempSync(join(tmpdir(), "navicode-wechat-"));
  const config = { ...defaultConfig(), dataDir, allowedRoots: [dataDir], defaultCwd: dataDir };
  const runtime = new MockRuntime();
  const threads = new ThreadMap(dataDir);
  return {
    text: "",
    userKey: "user",
    dataDir,
    config,
    runtime,
    threads,
    cleanup: () => rmSync(dataDir, { recursive: true, force: true }),
  };
}
