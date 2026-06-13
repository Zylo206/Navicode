import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { log } from "../logger.js";
import type { WeChatApi } from "./api.js";
import { uploadLocalFile } from "./media.js";
import {
  MessageItemType,
  MessageState,
  MessageType,
  TypingStatus,
  type MessageItem,
  type OutboundMessage,
} from "./types.js";

const TYPING_KEEPALIVE_MS = 5000;

export interface WeChatSender {
  sendText: (toUserId: string, contextToken: string, text: string) => Promise<void>;
  sendFile: (toUserId: string, contextToken: string, filePath: string) => Promise<void>;
  startTyping: (toUserId: string, contextToken: string) => () => void;
}

export function createSender(api: WeChatApi, botAccountId: string): WeChatSender {
  let clientCounter = 0;
  const typingTicketCache = new Map<string, { ticket: string; fetchedAt: number }>();

  function clientId(): string {
    clientCounter++;
    return `navicode-${Date.now()}-${clientCounter}`;
  }

  async function sendText(toUserId: string, contextToken: string, text: string): Promise<void> {
    const msg: OutboundMessage = {
      from_user_id: botAccountId,
      to_user_id: toUserId,
      client_id: clientId(),
      message_type: MessageType.BOT,
      message_state: MessageState.FINISH,
      context_token: contextToken,
      item_list: [{ type: MessageItemType.TEXT, text_item: { text } }],
    };
    await api.sendMessage({ msg });
  }

  async function sendFile(toUserId: string, contextToken: string, filePath: string): Promise<void> {
    const resolved = resolve(filePath.replace(/^~/, process.env.USERPROFILE ?? process.env.HOME ?? ""));
    if (!existsSync(resolved)) {
      await sendText(toUserId, contextToken, `文件不存在: ${resolved}`);
      return;
    }
    const media = await uploadLocalFile(api, resolved);
    const item: MessageItem = media.mediaType === "image"
      ? {
          type: MessageItemType.IMAGE,
          image_item: {
            media: {
              aes_key: Buffer.from(media.aesKey).toString("base64"),
              encrypt_query_param: media.encryptQueryParam,
            },
            mid_size: media.fileSize,
          },
        }
      : {
          type: MessageItemType.FILE,
          file_item: {
            media: {
              aes_key: Buffer.from(media.aesKey).toString("base64"),
              encrypt_query_param: media.encryptQueryParam,
            },
            file_name: media.fileName,
            len: String(media.rawSize),
          },
        };
    const msg: OutboundMessage = {
      from_user_id: botAccountId,
      to_user_id: toUserId,
      client_id: clientId(),
      message_type: MessageType.BOT,
      message_state: MessageState.FINISH,
      context_token: contextToken,
      item_list: [item],
    };
    await api.sendMessage({ msg });
  }

  function startTyping(toUserId: string, contextToken: string): () => void {
    let stopped = false;
    void (async () => {
      const ticket = await typingTicket(toUserId, contextToken);
      if (!ticket || stopped) {
        return;
      }
      while (!stopped) {
        try {
          await api.sendTyping({ ilink_user_id: toUserId, typing_ticket: ticket, status: TypingStatus.TYPING });
        } catch (error) {
          log("debug", "WeChat send typing failed", { error: error instanceof Error ? error.message : String(error) });
          return;
        }
        await sleep(TYPING_KEEPALIVE_MS);
      }
      try {
        await api.sendTyping({ ilink_user_id: toUserId, typing_ticket: ticket, status: TypingStatus.CANCEL });
      } catch {
        // best effort only
      }
    })();
    return () => {
      stopped = true;
    };
  }

  async function typingTicket(toUserId: string, contextToken: string): Promise<string> {
    const cached = typingTicketCache.get(toUserId);
    if (cached && Date.now() - cached.fetchedAt < 24 * 60 * 60 * 1000) {
      return cached.ticket;
    }
    const config = await api.getConfig(toUserId, contextToken);
    const ticket = config.typing_ticket ?? "";
    if (ticket) {
      typingTicketCache.set(toUserId, { ticket, fetchedAt: Date.now() });
    }
    return ticket;
  }

  return { sendText, sendFile, startTyping };
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
