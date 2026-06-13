import type { CommandContext, CommandResult } from "./handlers.js";
import { clear, cwd, help, status, stop } from "./handlers.js";

export async function routeCommand(ctx: CommandContext): Promise<CommandResult> {
  const text = ctx.text.trim();
  if (!text.startsWith("/")) {
    return { handled: false };
  }
  const [command, ...rest] = text.split(/\s+/);
  const arg = rest.join(" ");
  switch (command.toLowerCase()) {
    case "/help":
      return help();
    case "/status":
      return status(ctx);
    case "/clear":
      return clear(ctx);
    case "/stop":
      return stop(ctx);
    case "/cwd":
      return arg ? cwd(ctx, arg) : { handled: true, reply: "用法: /cwd <path>" };
    default:
      return { handled: true, reply: `未知命令: ${command}\n发送 /help 查看可用命令。` };
  }
}
