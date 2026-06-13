const SENTENCE_END = /[。！？.!?]$/;

function safeSplitPoint(text: string, maxLen: number): number {
  let idx = text.lastIndexOf("\n", maxLen);
  if (idx >= maxLen * 0.3) return idx;
  for (let i = maxLen; i >= maxLen * 0.5; i--) {
    if (SENTENCE_END.test(text.slice(i - 1, i))) return i;
  }
  idx = text.lastIndexOf(" ", maxLen);
  if (idx >= maxLen * 0.3) return idx;
  return maxLen;
}

function splitLongBlock(block: string, maxLen: number): string[] {
  const chunks: string[] = [];
  let remaining = block;
  while (remaining.length > maxLen) {
    const idx = safeSplitPoint(remaining, maxLen);
    chunks.push(remaining.slice(0, idx).trim());
    remaining = remaining.slice(idx).replace(/^\s+/, "");
  }
  if (remaining) chunks.push(remaining);
  return chunks;
}

export function splitMessage(text: string, maxLen = 3800): string[] {
  const normalized = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n").trim();
  if (!normalized) return [];
  if (normalized.length <= maxLen) return [normalized];
  const chunks: string[] = [];
  let current = "";
  for (const block of normalized.split(/\n\n+/)) {
    if (block.length > maxLen) {
      if (current) {
        chunks.push(current);
        current = "";
      }
      chunks.push(...splitLongBlock(block, maxLen));
      continue;
    }
    const next = current ? `${current}\n\n${block}` : block;
    if (next.length <= maxLen) {
      current = next;
    } else {
      if (current) chunks.push(current);
      current = block;
    }
  }
  if (current) chunks.push(current);
  return chunks;
}
