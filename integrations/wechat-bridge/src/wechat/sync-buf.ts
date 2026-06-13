import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

function syncBufPath(dataDir: string): string {
  return resolve(dataDir, "get_updates_buf");
}

export function loadSyncBuf(dataDir: string): string {
  const path = syncBufPath(dataDir);
  return existsSync(path) ? readFileSync(path, "utf8").trim() : "";
}

export function saveSyncBuf(dataDir: string, value: string): void {
  const path = syncBufPath(dataDir);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, value, "utf8");
}
