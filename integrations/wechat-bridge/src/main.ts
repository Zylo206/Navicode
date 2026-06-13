import { type BridgeConfig, loadConfig, runtimeApiKey } from "./config.js";
import { log } from "./logger.js";
import { RuntimeClient } from "./navicode/runtime-client.js";
import { ThreadMap } from "./navicode/thread-map.js";
import { splitMessage } from "./message-splitter.js";
import { routeCommand } from "./commands/router.js";
import { loadLatestAccount } from "./wechat/accounts.js";
import { WeChatApi } from "./wechat/api.js";
import { startQrLogin, waitForQrScan } from "./wechat/login.js";
import { downloadMessageMedia, extractText } from "./wechat/media.js";
import { createMonitor } from "./wechat/monitor.js";
import { createSender } from "./wechat/send.js";
import { MessageType, type WeixinMessage } from "./wechat/types.js";

export interface SubmitTextOptions {
  config?: BridgeConfig;
  runtime?: RuntimeClient;
  threads?: ThreadMap;
  pollIntervalMs?: number;
  maxPolls?: number;
}

export async function submitTextForUser(userKey: string, text: string, options: SubmitTextOptions = {}): Promise<string[]> {
  const config = options.config ?? loadConfig();
  const runtime = options.runtime ?? new RuntimeClient({ baseUrl: config.runtimeBaseUrl, apiKey: runtimeApiKey(config) });
  const threads = options.threads ?? new ThreadMap(config.dataDir);
  const pollIntervalMs = options.pollIntervalMs ?? 250;
  let binding = threads.get(userKey);
  if (!binding || !binding.runtimeThreadId) {
    const runtimeThreadId = await runtime.createThread();
    binding = threads.set(userKey, { runtimeThreadId, cwd: config.defaultCwd });
  }
  await runtime.submitTurn(binding.runtimeThreadId, text, { cwd: binding.cwd });
  let after = 0;
  const chunks: string[] = [];
  for (let polls = 0; options.maxPolls === undefined || polls < options.maxPolls; polls++) {
    const events = await runtime.getEvents(binding.runtimeThreadId, after);
    for (const event of events) {
      after = Math.max(after, event.id);
      if (event.event === "message.delta" && typeof event.data.content === "string") {
        chunks.push(...splitMessage(event.data.content));
      }
      if (event.event === "turn.completed" || event.event === "turn.failed" || event.event === "turn.cancelled") {
        return chunks;
      }
    }
    await new Promise(resolve => setTimeout(resolve, pollIntervalMs));
  }
  throw new Error("Runtime turn did not finish before maxPolls");
}

export async function setupWechatLogin(config = loadConfig()): Promise<void> {
  const qr = await startQrLogin();
  console.log("Scan this WeChat QR code URL or data URI:");
  console.log(qr.qrcodeUrl);
  await waitForQrScan(qr.qrcodeId, { dataDir: config.dataDir });
  console.log(`WeChat account saved under ${config.dataDir}`);
}

export async function startWechatBridge(config = loadConfig()): Promise<void> {
  const account = loadLatestAccount(config.dataDir);
  if (!account) {
    throw new Error(`No WeChat account found under ${config.dataDir}/accounts. Run "npm start -- setup" first.`);
  }
  const runtime = new RuntimeClient({ baseUrl: config.runtimeBaseUrl, apiKey: runtimeApiKey(config) });
  const threads = new ThreadMap(config.dataDir);
  const api = new WeChatApi({ botToken: account.botToken, baseUrl: account.baseUrl });
  const sender = createSender(api, account.accountId);
  const monitor = createMonitor(api, {
    onMessage: async message => {
      if (!message.from_user_id || message.message_type === MessageType.BOT) {
        return;
      }
      const stopTyping = sender.startTyping(message.from_user_id, message.context_token ?? "");
      try {
        const text = await messageToPrompt(message, config);
        if (!text) {
          await sender.sendText(message.from_user_id, message.context_token ?? "", "暂时只支持文本、带直连 URL 的图片和文件消息。");
          return;
        }
        const command = await routeCommand({
          text,
          userKey: message.from_user_id,
          config,
          runtime,
          threads,
        });
        if (command.handled) {
          if (command.reply) {
            await sender.sendText(message.from_user_id, message.context_token ?? "", command.reply);
          }
          return;
        }
        const chunks = await submitTextForUser(message.from_user_id, text, { config, runtime, threads });
        for (const chunk of chunks) {
          await sender.sendText(message.from_user_id, message.context_token ?? "", chunk);
        }
      } catch (error) {
        await sender.sendText(
          message.from_user_id,
          message.context_token ?? "",
          `Navicode WeChat Bridge error: ${error instanceof Error ? error.message : String(error)}`,
        );
      } finally {
        stopTyping();
      }
    },
    onSessionExpired: () => log("warn", "WeChat session expired; rerun setup"),
  }, { dataDir: config.dataDir });
  process.once("SIGINT", () => monitor.stop());
  process.once("SIGTERM", () => monitor.stop());
  await monitor.run();
}

async function messageToPrompt(message: WeixinMessage, config: BridgeConfig): Promise<string> {
  const textParts: string[] = [];
  for (const item of message.item_list ?? []) {
    const text = extractText(item).trim();
    if (text) {
      textParts.push(text);
    }
    const media = await downloadMessageMedia(item, config.dataDir);
    if (media?.mediaType === "image") {
      textParts.push(`用户通过微信发送了一张图片:\n@image:${media.path}`);
    } else if (media) {
      textParts.push(`用户通过微信发送了文件: ${media.fileName}\n文件已保存到: ${media.path}\n请先读取这个文件再回答。`);
    }
  }
  return textParts.join("\n\n").trim();
}

if (process.argv[1]?.endsWith("main.js")) {
  const command = process.argv[2] ?? "start";
  const run = command === "setup"
    ? setupWechatLogin()
    : command === "start"
      ? startWechatBridge()
      : Promise.reject(new Error(`Unknown command: ${command}. Use setup or start.`));
  run.catch(error => {
    log("error", "wechat bridge command failed", { error: error instanceof Error ? error.message : String(error) });
    process.exitCode = 1;
  });
}
