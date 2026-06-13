import test from "node:test";
import assert from "node:assert/strict";
import type { WeChatApi } from "../src/wechat/api.js";
import { createSender } from "../src/wechat/send.js";
import { MessageItemType, MessageState, MessageType, type SendMessageReq } from "../src/wechat/types.js";

test("sendText creates a finished bot text message", async () => {
  const sent: SendMessageReq[] = [];
  const api = {
    async sendMessage(req: SendMessageReq) {
      sent.push(req);
    },
  } as unknown as WeChatApi;

  const sender = createSender(api, "bot_1");
  await sender.sendText("user_1", "ctx_1", "hello");

  assert.equal(sent.length, 1);
  assert.equal(sent[0].msg.from_user_id, "bot_1");
  assert.equal(sent[0].msg.to_user_id, "user_1");
  assert.equal(sent[0].msg.message_type, MessageType.BOT);
  assert.equal(sent[0].msg.message_state, MessageState.FINISH);
  assert.equal(sent[0].msg.context_token, "ctx_1");
  assert.equal(sent[0].msg.item_list[0].type, MessageItemType.TEXT);
  assert.equal(sent[0].msg.item_list[0].text_item?.text, "hello");
});
