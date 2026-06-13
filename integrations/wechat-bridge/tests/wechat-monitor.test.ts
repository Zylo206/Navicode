import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { WeChatApi } from "../src/wechat/api.js";
import { createMonitor } from "../src/wechat/monitor.js";

test("monitor saves sync buffer and deduplicates messages", async () => {
  const dataDir = mkdtempSync(join(tmpdir(), "navicode-wechat-"));
  const seen: Array<number | string | undefined> = [];
  let polls = 0;
  const api = {
    async getUpdates() {
      polls++;
      return {
        ret: 0,
        get_updates_buf: `buf_${polls}`,
        msgs: [{ message_id: 7, from_user_id: "user" }, { message_id: 7, from_user_id: "user" }],
      };
    },
  } as unknown as WeChatApi;
  const monitor = createMonitor(api, {
    onMessage: async message => {
      seen.push(message.message_id);
      monitor.stop();
    },
  }, { dataDir, shortBackoffMs: 0 });
  try {
    await monitor.run();
    assert.deepEqual(seen, [7]);
  } finally {
    rmSync(dataDir, { recursive: true, force: true });
  }
});
