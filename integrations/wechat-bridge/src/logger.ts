export type LogLevel = "debug" | "info" | "warn" | "error";

const SECRET_KEYS = /(authorization|api[_-]?key|token|secret|botToken)/gi;

function sanitize(value: unknown): unknown {
  if (typeof value === "string") {
    return value.replace(/Bearer\s+\S+/gi, "Bearer [redacted]");
  }
  if (!value || typeof value !== "object") {
    return value;
  }
  const out: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
    out[key] = SECRET_KEYS.test(key) ? "[redacted]" : sanitize(entry);
    SECRET_KEYS.lastIndex = 0;
  }
  return out;
}

export function log(level: LogLevel, message: string, meta?: Record<string, unknown>): void {
  const line = {
    ts: new Date().toISOString(),
    level,
    message,
    ...(meta ? { meta: sanitize(meta) } : {}),
  };
  console.error(JSON.stringify(line));
}
