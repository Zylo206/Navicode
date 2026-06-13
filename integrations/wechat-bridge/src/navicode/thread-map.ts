import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

export interface ThreadBinding {
  runtimeThreadId: string;
  cwd?: string;
  updatedAt: string;
}

export class ThreadMap {
  private readonly path: string;
  private cache: Record<string, ThreadBinding> = {};

  constructor(dataDir: string) {
    this.path = resolve(dataDir, "sessions", "threads.json");
    this.load();
  }

  get(userKey: string): ThreadBinding | undefined {
    return this.cache[userKey];
  }

  set(userKey: string, binding: Omit<ThreadBinding, "updatedAt">): ThreadBinding {
    const saved = { ...binding, updatedAt: new Date().toISOString() };
    this.cache[userKey] = saved;
    this.save();
    return saved;
  }

  clear(userKey: string): void {
    delete this.cache[userKey];
    this.save();
  }

  private load(): void {
    if (!existsSync(this.path)) {
      this.cache = {};
      return;
    }
    this.cache = JSON.parse(readFileSync(this.path, "utf8")) as Record<string, ThreadBinding>;
  }

  private save(): void {
    mkdirSync(dirname(this.path), { recursive: true });
    writeFileSync(this.path, JSON.stringify(this.cache, null, 2), "utf8");
  }
}
