import { log } from "../logger.js";
import { DEFAULT_ILINK_BASE_URL, type AccountData, saveAccount } from "./accounts.js";

const QR_POLL_INTERVAL_MS = 3000;

export interface QrLoginOptions {
  dataDir: string;
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  pollIntervalMs?: number;
  maxPolls?: number;
}

export interface QrLoginStart {
  qrcodeUrl: string;
  qrcodeId: string;
}

interface QrCodeResponse {
  ret: number;
  qrcode?: string;
  qrcode_img_content?: string;
  retmsg?: string;
}

interface QrStatusResponse {
  ret?: number;
  status?: string;
  retmsg?: string;
  bot_token?: string;
  ilink_bot_id?: string;
  baseurl?: string;
  ilink_user_id?: string;
}

export async function startQrLogin(options: Omit<QrLoginOptions, "dataDir"> = {}): Promise<QrLoginStart> {
  const baseUrl = (options.baseUrl ?? DEFAULT_ILINK_BASE_URL).replace(/\/+$/, "");
  const fetchImpl = options.fetchImpl ?? fetch;
  const response = await fetchImpl(`${baseUrl}/ilink/bot/get_bot_qrcode?bot_type=3`);
  if (!response.ok) {
    throw new Error(`Failed to get WeChat QR code: HTTP ${response.status}`);
  }
  const body = await response.json() as QrCodeResponse;
  if (body.ret !== 0 || !body.qrcode || !body.qrcode_img_content) {
    throw new Error(`Failed to get WeChat QR code: ${body.retmsg ?? `ret=${body.ret}`}`);
  }
  log("info", "WeChat QR code created", { qrcodeId: body.qrcode });
  return { qrcodeId: body.qrcode, qrcodeUrl: body.qrcode_img_content };
}

export async function waitForQrScan(qrcodeId: string, options: QrLoginOptions): Promise<AccountData> {
  const baseUrl = (options.baseUrl ?? DEFAULT_ILINK_BASE_URL).replace(/\/+$/, "");
  const fetchImpl = options.fetchImpl ?? fetch;
  const intervalMs = options.pollIntervalMs ?? QR_POLL_INTERVAL_MS;
  for (let polls = 0; options.maxPolls === undefined || polls < options.maxPolls; polls++) {
    const response = await fetchImpl(`${baseUrl}/ilink/bot/get_qrcode_status?qrcode=${encodeURIComponent(qrcodeId)}`);
    if (!response.ok) {
      throw new Error(`Failed to check WeChat QR status: HTTP ${response.status}`);
    }
    const body = await response.json() as QrStatusResponse;
    switch (body.status) {
      case "wait":
      case "scaned":
        await sleep(intervalMs);
        continue;
      case "confirmed": {
        if (!body.bot_token || !body.ilink_bot_id || !body.ilink_user_id) {
          throw new Error("WeChat QR confirmed but response missed bot token or account id");
        }
        const account: AccountData = {
          botToken: body.bot_token,
          accountId: body.ilink_bot_id,
          baseUrl: body.baseurl ?? DEFAULT_ILINK_BASE_URL,
          userId: body.ilink_user_id,
          createdAt: new Date().toISOString(),
        };
        saveAccount(options.dataDir, account);
        log("info", "WeChat QR login completed", { accountId: account.accountId });
        return account;
      }
      case "expired":
        throw new Error("WeChat QR code expired");
      default:
        if (body.retmsg) {
          throw new Error(`WeChat QR scan failed: ${body.retmsg}`);
        }
        await sleep(intervalMs);
    }
  }
  throw new Error("WeChat QR scan did not complete before maxPolls");
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
