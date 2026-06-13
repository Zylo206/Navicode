import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { log } from "../logger.js";

export const DEFAULT_ILINK_BASE_URL = "https://ilinkai.weixin.qq.com";

export interface AccountData {
  botToken: string;
  accountId: string;
  baseUrl: string;
  userId: string;
  createdAt: string;
}

function accountsDir(dataDir: string): string {
  return resolve(dataDir, "accounts");
}

function validateAccountId(accountId: string): void {
  if (!/^[a-zA-Z0-9_.@=-]+$/.test(accountId)) {
    throw new Error(`Invalid accountId: ${accountId}`);
  }
}

export function accountPath(dataDir: string, accountId: string): string {
  validateAccountId(accountId);
  return join(accountsDir(dataDir), `${accountId}.json`);
}

export function saveAccount(dataDir: string, account: AccountData): void {
  const filePath = accountPath(dataDir, account.accountId);
  mkdirSync(accountsDir(dataDir), { recursive: true });
  writeFileSync(filePath, JSON.stringify(account, null, 2), "utf8");
  log("info", "WeChat account saved", { accountId: account.accountId });
}

export function loadAccount(dataDir: string, accountId: string): AccountData | undefined {
  const filePath = accountPath(dataDir, accountId);
  if (!existsSync(filePath)) {
    return undefined;
  }
  return JSON.parse(readFileSync(filePath, "utf8")) as AccountData;
}

export function loadLatestAccount(dataDir: string): AccountData | undefined {
  const dir = accountsDir(dataDir);
  if (!existsSync(dir)) {
    return undefined;
  }
  const files = readdirSync(dir).filter(file => file.endsWith(".json"));
  if (files.length === 0) {
    return undefined;
  }
  let latest = files[0];
  let latestMtime = -1;
  for (const file of files) {
    const mtime = statSync(join(dir, file)).mtimeMs;
    if (mtime > latestMtime) {
      latest = file;
      latestMtime = mtime;
    }
  }
  return loadAccount(dataDir, latest.replace(/\.json$/, ""));
}
