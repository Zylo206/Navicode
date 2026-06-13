import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadLatestAccount } from "../src/wechat/accounts.js";
import { startQrLogin, waitForQrScan } from "../src/wechat/login.js";

test("QR login starts, waits for confirmation, and saves account", async () => {
  const dataDir = mkdtempSync(join(tmpdir(), "navicode-wechat-"));
  const calls: string[] = [];
  const fetchImpl = async (input: string | URL | Request): Promise<Response> => {
    const url = String(input);
    calls.push(url);
    if (url.includes("get_bot_qrcode")) {
      return Response.json({ ret: 0, qrcode: "qr_1", qrcode_img_content: "data:image/png;base64,abc" });
    }
    return Response.json({
      ret: 0,
      status: "confirmed",
      bot_token: "bot_secret",
      ilink_bot_id: "bot_1",
      ilink_user_id: "user_1",
      baseurl: "https://ilinkai.weixin.qq.com",
    });
  };
  try {
    const qr = await startQrLogin({ fetchImpl });
    assert.equal(qr.qrcodeId, "qr_1");
    const account = await waitForQrScan(qr.qrcodeId, { dataDir, fetchImpl, pollIntervalMs: 0, maxPolls: 1 });
    assert.equal(account.accountId, "bot_1");
    assert.equal(loadLatestAccount(dataDir)?.botToken, "bot_secret");
    assert.equal(calls.length, 2);
  } finally {
    rmSync(dataDir, { recursive: true, force: true });
  }
});
