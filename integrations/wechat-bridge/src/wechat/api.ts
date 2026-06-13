import { log } from "../logger.js";
import { DEFAULT_ILINK_BASE_URL } from "./accounts.js";
import type {
  GetConfigResp,
  GetUpdatesResp,
  GetUploadUrlReq,
  GetUploadUrlResp,
  SendMessageReq,
  SendTypingReq,
} from "./types.js";

export interface WeChatApiOptions {
  botToken: string;
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  minSendIntervalMs?: number;
}

export class WeChatApi {
  private readonly botToken: string;
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;
  private readonly uin: string;
  private readonly nextSendTime = new Map<string, number>();
  private readonly minSendIntervalMs: number;

  constructor(options: WeChatApiOptions) {
    if (!options.botToken) {
      throw new Error("WeChat bot token is required");
    }
    this.botToken = options.botToken;
    this.baseUrl = normalizeIlinkBaseUrl(options.baseUrl ?? DEFAULT_ILINK_BASE_URL);
    this.fetchImpl = options.fetchImpl ?? fetch;
    this.uin = generateUin();
    this.minSendIntervalMs = options.minSendIntervalMs ?? 2500;
  }

  async getUpdates(buf?: string): Promise<GetUpdatesResp> {
    return this.request<GetUpdatesResp>("ilink/bot/getupdates", buf ? { get_updates_buf: buf } : {}, 35_000);
  }

  async sendMessage(req: SendMessageReq): Promise<void> {
    const userId = req.msg.to_user_id;
    await this.waitForUserRateLimit(userId);
    let delayMs = 3000;
    for (let attempt = 0; attempt < 3; attempt++) {
      const response = await this.request<{ ret?: number; retmsg?: string }>("ilink/bot/sendmessage", req);
      if (response.ret !== -2) {
        return;
      }
      this.nextSendTime.set(userId, Date.now() + delayMs + this.minSendIntervalMs);
      if (attempt === 2) {
        throw new Error(`sendmessage rate-limited: ${response.retmsg ?? "ret=-2"}`);
      }
      log("warn", "WeChat sendmessage rate-limited, retrying", { userId, attempt, delayMs });
      await sleep(delayMs);
      delayMs = Math.min(delayMs * 2, 15_000);
    }
  }

  async getConfig(ilinkUserId: string, contextToken?: string): Promise<GetConfigResp> {
    return this.request<GetConfigResp>("ilink/bot/getconfig", {
      ilink_user_id: ilinkUserId,
      context_token: contextToken,
    }, 10_000);
  }

  async sendTyping(req: SendTypingReq): Promise<void> {
    await this.request("ilink/bot/sendtyping", req, 10_000);
  }

  async getUploadUrl(req: GetUploadUrlReq): Promise<GetUploadUrlResp> {
    return this.request<GetUploadUrlResp>("ilink/bot/getuploadurl", req, 15_000);
  }

  private headers(): Record<string, string> {
    return {
      "Content-Type": "application/json",
      Authorization: `Bearer ${this.botToken}`,
      AuthorizationType: "ilink_bot_token",
      "X-WECHAT-UIN": this.uin,
    };
  }

  private async request<T>(path: string, body: unknown, timeoutMs = 15_000): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const url = `${this.baseUrl}/${path}`;
    try {
      const response = await this.fetchImpl(url, {
        method: "POST",
        headers: this.headers(),
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${await response.text()}`);
      }
      return await response.json() as T;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        throw new Error(`Request timed out after ${timeoutMs}ms: ${url}`);
      }
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }

  private async waitForUserRateLimit(userId: string): Promise<void> {
    const now = Date.now();
    const next = Math.max(now, this.nextSendTime.get(userId) ?? 0);
    this.nextSendTime.set(userId, next + this.minSendIntervalMs);
    const waitMs = next - now;
    if (waitMs > 0) {
      await sleep(waitMs);
    }
  }
}

export function normalizeIlinkBaseUrl(value: string): string {
  const url = new URL(value);
  const allowed = url.hostname === "weixin.qq.com"
    || url.hostname.endsWith(".weixin.qq.com")
    || url.hostname === "wechat.com"
    || url.hostname.endsWith(".wechat.com");
  if (url.protocol !== "https:" || !allowed) {
    throw new Error("ilink baseUrl must be an https weixin.qq.com or wechat.com URL");
  }
  return value.replace(/\/+$/, "");
}

function generateUin(): string {
  return Buffer.from(crypto.getRandomValues(new Uint8Array(4))).toString("base64");
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
