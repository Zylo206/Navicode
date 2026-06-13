import { existsSync, mkdirSync, statSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { basename, extname, resolve } from "node:path";
import { log } from "../logger.js";
import type { WeChatApi } from "./api.js";
import { MessageItemType, type FileItem, type ImageItem, type MessageItem, type WeChatMediaRef } from "./types.js";

export interface DownloadedMedia {
  path: string;
  fileName: string;
  mediaType: "image" | "file";
}

export interface UploadedMedia {
  fileName: string;
  fileSize: number;
  rawSize: number;
  aesKey: string;
  encryptQueryParam: string;
  mediaType: "image" | "file";
}

export function extractText(item: MessageItem): string {
  return item.text_item?.text ?? item.voice_item?.voice_text ?? "";
}

export function firstImage(items?: MessageItem[]): MessageItem | undefined {
  return items?.find(item => item.type === MessageItemType.IMAGE && item.image_item);
}

export function firstFile(items?: MessageItem[]): MessageItem | undefined {
  return items?.find(item => item.type === MessageItemType.FILE && item.file_item);
}

export async function downloadMessageMedia(item: MessageItem, dataDir: string, fetchImpl: typeof fetch = fetch): Promise<DownloadedMedia | undefined> {
  if (item.type === MessageItemType.IMAGE && item.image_item) {
    return downloadFromRef(imageMediaRef(item.image_item), dataDir, "image", imageFileName(item.image_item), fetchImpl);
  }
  if (item.type === MessageItemType.FILE && item.file_item) {
    return downloadFromRef(fileMediaRef(item.file_item), dataDir, "file", item.file_item.file_name ?? "wechat-file", fetchImpl);
  }
  return undefined;
}

export async function uploadLocalFile(api: WeChatApi, filePath: string, fetchImpl: typeof fetch = fetch): Promise<UploadedMedia> {
  const resolved = resolve(filePath);
  if (!existsSync(resolved)) {
    throw new Error(`File does not exist: ${resolved}`);
  }
  const stat = statSync(resolved);
  const fileName = basename(resolved);
  const mediaType = isImagePath(fileName) ? "image" : "file";
  const upload = await api.getUploadUrl({
    file_name: fileName,
    file_size: stat.size,
    file_type: mediaType,
  });
  if (!upload.url || !upload.aes_key || !upload.encrypt_query_param) {
    throw new Error("WeChat getuploadurl response missed upload fields");
  }
  const buffer = await import("node:fs/promises").then(fs => fs.readFile(resolved));
  const response = await fetchImpl(upload.url, { method: "PUT", body: buffer });
  if (!response.ok) {
    throw new Error(`WeChat media upload failed: HTTP ${response.status}`);
  }
  return {
    fileName,
    fileSize: stat.size,
    rawSize: stat.size,
    aesKey: upload.aes_key,
    encryptQueryParam: upload.encrypt_query_param,
    mediaType,
  };
}

function imageMediaRef(item: ImageItem): WeChatMediaRef | undefined {
  return item.media ?? item.cdn_media ?? (item.url ? { cdn_url: item.url } : undefined);
}

function fileMediaRef(item: FileItem): WeChatMediaRef | undefined {
  return item.media ?? item.cdn_media;
}

async function downloadFromRef(
  ref: WeChatMediaRef | undefined,
  dataDir: string,
  mediaType: "image" | "file",
  preferredName: string,
  fetchImpl: typeof fetch,
): Promise<DownloadedMedia | undefined> {
  const url = ref?.cdn_url;
  if (!url) {
    log("warn", "WeChat media has no direct CDN URL; encrypted CDN download is not implemented yet", { mediaType });
    return undefined;
  }
  const response = await fetchImpl(url);
  if (!response.ok) {
    throw new Error(`Failed to download WeChat media: HTTP ${response.status}`);
  }
  const dir = resolve(dataDir, "downloads", new Date().toISOString().slice(0, 10));
  mkdirSync(dir, { recursive: true });
  const target = resolve(dir, safeFileName(preferredName));
  await writeFile(target, Buffer.from(await response.arrayBuffer()));
  return { path: target, fileName: basename(target), mediaType };
}

function imageFileName(item: ImageItem): string {
  const ext = item.url ? extname(new URL(item.url).pathname) : "";
  return `wechat-image-${Date.now()}${ext || ".jpg"}`;
}

function safeFileName(value: string): string {
  return value.replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_").slice(0, 180) || "wechat-media";
}

function isImagePath(fileName: string): boolean {
  return [".png", ".jpg", ".jpeg", ".gif", ".webp"].includes(extname(fileName).toLowerCase());
}
