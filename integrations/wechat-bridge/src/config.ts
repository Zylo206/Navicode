import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, resolve } from "node:path";

export interface BridgeConfig {
  runtimeBaseUrl: string;
  runtimeApiKeyEnv: string;
  defaultCwd: string;
  allowedRoots: string[];
  dataDir: string;
  sendToolSummaries: boolean;
  maxQueuedMessages: number;
}

const DEFAULT_DATA_DIR = resolve(homedir(), ".navicode", "wechat");

export function defaultConfig(): BridgeConfig {
  const cwd = process.cwd();
  return {
    runtimeBaseUrl: "http://127.0.0.1:8080",
    runtimeApiKeyEnv: "NAVICODE_RUNTIME_API_KEY",
    defaultCwd: cwd,
    allowedRoots: [cwd],
    dataDir: DEFAULT_DATA_DIR,
    sendToolSummaries: true,
    maxQueuedMessages: 10,
  };
}

export function configPath(dataDir = DEFAULT_DATA_DIR): string {
  return resolve(dataDir, "config.json");
}

export function loadConfig(path = configPath()): BridgeConfig {
  const base = defaultConfig();
  if (!existsSync(path)) {
    return base;
  }
  const loaded = JSON.parse(readFileSync(path, "utf8")) as Partial<BridgeConfig>;
  const config = { ...base, ...loaded };
  validateRuntimeBaseUrl(config.runtimeBaseUrl);
  return config;
}

export function saveConfig(config: BridgeConfig, path = configPath(config.dataDir)): void {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(config, null, 2), "utf8");
}

export function runtimeApiKey(config: BridgeConfig): string {
  return process.env[config.runtimeApiKeyEnv] ?? "";
}

export function validateRuntimeBaseUrl(value: string): void {
  const url = new URL(value);
  const local = url.hostname === "127.0.0.1" || url.hostname === "localhost" || url.hostname === "::1";
  if (url.protocol !== "http:" || !local) {
    throw new Error("runtimeBaseUrl must be a local http URL");
  }
}
