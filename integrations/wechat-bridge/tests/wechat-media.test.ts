import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { downloadMessageMedia } from "../src/wechat/media.js";
import { MessageItemType } from "../src/wechat/types.js";

test("downloadMessageMedia saves image items with direct CDN URLs", async () => {
  const dataDir = mkdtempSync(join(tmpdir(), "navicode-wechat-"));
  const fetchImpl = async (): Promise<Response> => new Response(Buffer.from("image-bytes"));
  try {
    const media = await downloadMessageMedia({
      type: MessageItemType.IMAGE,
      image_item: { url: "https://example.weixin.qq.com/a/photo.png" },
    }, dataDir, fetchImpl);

    assert.ok(media);
    assert.equal(media.mediaType, "image");
    assert.ok(existsSync(media.path));
    assert.equal(readFileSync(media.path, "utf8"), "image-bytes");
  } finally {
    rmSync(dataDir, { recursive: true, force: true });
  }
});
