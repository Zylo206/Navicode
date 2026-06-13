export interface RuntimeEvent {
  id: number;
  event: string;
  data: Record<string, unknown>;
}

export interface RuntimeClientOptions {
  baseUrl: string;
  apiKey: string;
}

export class RuntimeClient {
  private readonly baseUrl: string;
  private readonly apiKey: string;

  constructor(options: RuntimeClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.apiKey = options.apiKey;
    if (!this.apiKey) {
      throw new Error("Runtime API key is required");
    }
  }

  async createThread(): Promise<string> {
    const body = await this.request("/v1/threads", { method: "POST" });
    return String(body.id);
  }

  async submitTurn(threadId: string, input: string, options: { cwd?: string } = {}): Promise<string> {
    const body = await this.request(`/v1/threads/${encodeURIComponent(threadId)}/turns`, {
      method: "POST",
      body: JSON.stringify({ input, ...(options.cwd ? { cwd: options.cwd } : {}) }),
    });
    return String(body.id);
  }

  async cancelTurn(threadId: string): Promise<void> {
    await this.request(`/v1/threads/${encodeURIComponent(threadId)}/cancel`, { method: "POST" });
  }

  async getEvents(threadId: string, after = 0): Promise<RuntimeEvent[]> {
    const response = await fetch(`${this.baseUrl}/v1/threads/${encodeURIComponent(threadId)}/events?after=${after}`, {
      headers: this.headers(),
    });
    if (!response.ok) {
      throw new Error(`Runtime API events failed: HTTP ${response.status}`);
    }
    return parseSse(await response.text());
  }

  private async request(path: string, init: RequestInit): Promise<Record<string, unknown>> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: {
        ...this.headers(),
        "Content-Type": "application/json",
        ...(init.headers ?? {}),
      },
    });
    const text = await response.text();
    const body = text ? JSON.parse(text) as Record<string, unknown> : {};
    if (!response.ok) {
      throw new Error(`Runtime API request failed: HTTP ${response.status} ${JSON.stringify(body)}`);
    }
    return body;
  }

  private headers(): Record<string, string> {
    return { Authorization: `Bearer ${this.apiKey}` };
  }
}

export function parseSse(input: string): RuntimeEvent[] {
  const events: RuntimeEvent[] = [];
  for (const block of input.split(/\n\n+/)) {
    if (!block.trim()) continue;
    let id = 0;
    let event = "message";
    let data = "{}";
    for (const line of block.split(/\n/)) {
      if (line.startsWith("id:")) id = Number(line.slice(3).trim());
      if (line.startsWith("event:")) event = line.slice(6).trim();
      if (line.startsWith("data:")) data = line.slice(5).trim();
    }
    events.push({ id, event, data: JSON.parse(data) as Record<string, unknown> });
  }
  return events;
}
