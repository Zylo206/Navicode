import { existsSync } from "node:fs";
import { isAbsolute, relative, resolve } from "node:path";
import type { BridgeConfig } from "../config.js";
import type { RuntimeClient } from "../navicode/runtime-client.js";
import type { ThreadMap } from "../navicode/thread-map.js";

export interface CommandContext {
  text: string;
  userKey: string;
  config: BridgeConfig;
  runtime: RuntimeClient;
  threads: ThreadMap;
}

export interface CommandResult {
  handled: boolean;
  reply?: string;
}

export function help(): CommandResult {
  return {
    handled: true,
    reply: [
      "Navicode WeChat Bridge",
      "",
      "/help - 显示帮助",
      "/status - 查看桥接状态",
      "/clear - 清除当前微信会话绑定的 Runtime thread",
      "/stop - 请求停止当前 Runtime turn",
      "/cwd <path> - 设置后续任务的工作目录",
    ].join("\n"),
  };
}

export function status(ctx: CommandContext): CommandResult {
  const binding = ctx.threads.get(ctx.userKey);
  return {
    handled: true,
    reply: [
      "Navicode bridge status",
      `Runtime: ${ctx.config.runtimeBaseUrl}`,
      `Thread: ${binding?.runtimeThreadId ?? "(none)"}`,
      `CWD: ${binding?.cwd ?? ctx.config.defaultCwd}`,
    ].join("\n"),
  };
}

export function clear(ctx: CommandContext): CommandResult {
  ctx.threads.clear(ctx.userKey);
  return { handled: true, reply: "已清除当前微信会话。下一条消息会创建新的 Runtime thread。" };
}

export async function stop(ctx: CommandContext): Promise<CommandResult> {
  const binding = ctx.threads.get(ctx.userKey);
  if (!binding) {
    return { handled: true, reply: "当前没有绑定的 Runtime thread。" };
  }
  try {
    await ctx.runtime.cancelTurn(binding.runtimeThreadId);
    return { handled: true, reply: "已请求停止当前任务。" };
  } catch (error) {
    return { handled: true, reply: `停止失败: ${error instanceof Error ? error.message : String(error)}` };
  }
}

export function cwd(ctx: CommandContext, rawPath: string): CommandResult {
  const next = resolve(rawPath.trim());
  if (!existsSync(next)) {
    return { handled: true, reply: `目录不存在: ${next}` };
  }
  const allowed = ctx.config.allowedRoots.map(root => resolve(root));
  if (!allowed.some(root => isInside(next, root))) {
    return { handled: true, reply: `目录不在 allowedRoots 内: ${next}` };
  }
  const binding = ctx.threads.get(ctx.userKey);
  ctx.threads.set(ctx.userKey, {
    runtimeThreadId: binding?.runtimeThreadId ?? "",
    cwd: next,
  });
  return { handled: true, reply: `已设置工作目录: ${next}` };
}

function isInside(candidate: string, root: string): boolean {
  const distance = relative(root, candidate);
  return distance === "" || (distance !== "" && !distance.startsWith("..") && !isAbsolute(distance));
}
