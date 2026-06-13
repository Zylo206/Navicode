import { log } from "../logger.js";
import type { WeChatApi } from "./api.js";
import { loadSyncBuf, saveSyncBuf } from "./sync-buf.js";
import type { WeixinMessage } from "./types.js";

const SESSION_EXPIRED_RET = -14;
const DEFAULT_SHORT_BACKOFF_MS = 3000;
const DEFAULT_LONG_BACKOFF_MS = 30_000;
const DEFAULT_SESSION_EXPIRED_PAUSE_MS = 60 * 60 * 1000;

export interface MonitorCallbacks {
  onMessage: (message: WeixinMessage) => Promise<void>;
  onSessionExpired?: () => void;
  onError?: (error: unknown) => void;
}

export interface MonitorOptions {
  dataDir: string;
  shortBackoffMs?: number;
  longBackoffMs?: number;
  sessionExpiredPauseMs?: number;
  maxRememberedMessageIds?: number;
}

export interface WeChatMonitor {
  run: () => Promise<void>;
  stop: () => void;
}

export function createMonitor(api: WeChatApi, callbacks: MonitorCallbacks, options: MonitorOptions): WeChatMonitor {
  const controller = new AbortController();
  const recentMessageIds = new Set<string>();
  const maxIds = options.maxRememberedMessageIds ?? 1000;
  const shortBackoffMs = options.shortBackoffMs ?? DEFAULT_SHORT_BACKOFF_MS;
  const longBackoffMs = options.longBackoffMs ?? DEFAULT_LONG_BACKOFF_MS;
  const sessionExpiredPauseMs = options.sessionExpiredPauseMs ?? DEFAULT_SESSION_EXPIRED_PAUSE_MS;

  async function run(): Promise<void> {
    let failures = 0;
    while (!controller.signal.aborted) {
      try {
        const resp = await api.getUpdates(loadSyncBuf(options.dataDir) || undefined);
        if (resp.get_updates_buf) {
          saveSyncBuf(options.dataDir, resp.get_updates_buf);
        }
        if (resp.ret === SESSION_EXPIRED_RET) {
          callbacks.onSessionExpired?.();
          await sleep(sessionExpiredPauseMs, controller.signal);
          failures = 0;
          continue;
        }
        if (resp.ret !== undefined && resp.ret !== 0) {
          log("warn", "WeChat getupdates returned non-zero ret", { ret: resp.ret, retmsg: resp.retmsg });
        }
        for (const message of resp.msgs ?? []) {
          if (seen(message, recentMessageIds, maxIds)) {
            continue;
          }
          callbacks.onMessage(message).catch(error => {
            callbacks.onError?.(error);
            log("error", "WeChat message handler failed", {
              messageId: message.message_id,
              error: error instanceof Error ? error.message : String(error),
            });
          });
        }
        failures = 0;
      } catch (error) {
        if (controller.signal.aborted) {
          break;
        }
        failures++;
        callbacks.onError?.(error);
        log("error", "WeChat monitor poll failed", {
          failures,
          error: error instanceof Error ? error.message : String(error),
        });
        await sleep(failures >= 3 ? longBackoffMs : shortBackoffMs, controller.signal);
      }
    }
    log("info", "WeChat monitor stopped");
  }

  function stop(): void {
    controller.abort();
  }

  return { run, stop };
}

function seen(message: WeixinMessage, recent: Set<string>, maxIds: number): boolean {
  if (message.message_id === undefined || message.message_id === null) {
    return false;
  }
  const id = String(message.message_id);
  if (recent.has(id)) {
    return true;
  }
  recent.add(id);
  if (recent.size > maxIds) {
    for (const oldId of [...recent].slice(0, Math.max(1, Math.floor(maxIds / 2)))) {
      recent.delete(oldId);
    }
  }
  return false;
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise(resolve => {
    if (signal.aborted || ms <= 0) {
      resolve();
      return;
    }
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => {
      clearTimeout(timer);
      resolve();
    }, { once: true });
  });
}
