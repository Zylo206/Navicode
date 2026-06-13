import { type BridgeConfig, loadConfig, runtimeApiKey } from "./config.js";
import { log } from "./logger.js";
import { RuntimeClient } from "./navicode/runtime-client.js";
import { ThreadMap } from "./navicode/thread-map.js";
import { splitMessage } from "./message-splitter.js";

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

if (process.argv[1]?.endsWith("main.js")) {
  log("info", "wechat bridge skeleton is installed; real ilink monitor is a later phase");
}
