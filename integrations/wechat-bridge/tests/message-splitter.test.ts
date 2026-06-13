import test from "node:test";
import assert from "node:assert/strict";
import { splitMessage } from "../src/message-splitter.js";

test("keeps short messages intact", () => {
  assert.deepEqual(splitMessage("hello"), ["hello"]);
});

test("splits long Chinese text at sentence boundaries", () => {
  const text = "第一句。".repeat(100);
  const chunks = splitMessage(text, 80);
  assert.ok(chunks.length > 1);
  assert.ok(chunks.every(chunk => chunk.length <= 80));
});

test("prefers paragraph boundaries for markdown", () => {
  const chunks = splitMessage("a\n\nb\n\nc", 4);
  assert.deepEqual(chunks, ["a\n\nb", "c"]);
});
