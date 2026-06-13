export enum MessageType {
  USER = 1,
  BOT = 2,
}

export enum MessageItemType {
  TEXT = 1,
  IMAGE = 2,
  VOICE = 3,
  FILE = 4,
  VIDEO = 5,
}

export enum MessageState {
  NEW = 0,
  GENERATING = 1,
  FINISH = 2,
}

export enum TypingStatus {
  TYPING = 1,
  CANCEL = 2,
}

export interface WeChatMediaRef {
  aes_key?: string;
  encrypt_query_param?: string;
  cdn_url?: string;
}

export interface TextItem {
  text: string;
}

export interface ImageItem {
  cdn_media?: WeChatMediaRef;
  aeskey?: string;
  media?: WeChatMediaRef;
  url?: string;
  mid_size?: number;
  hd_size?: number;
}

export interface FileItem {
  cdn_media?: WeChatMediaRef;
  media?: WeChatMediaRef;
  file_name?: string;
  len?: string;
}

export interface VoiceItem {
  cdn_media?: WeChatMediaRef;
  voice_text?: string;
}

export interface VideoItem {
  cdn_media?: WeChatMediaRef;
}

export interface MessageItem {
  type: MessageItemType;
  text_item?: TextItem;
  image_item?: ImageItem;
  file_item?: FileItem;
  voice_item?: VoiceItem;
  video_item?: VideoItem;
}

export interface WeixinMessage {
  seq?: number;
  message_id?: number | string;
  from_user_id?: string;
  to_user_id?: string;
  create_time_ms?: number;
  message_type?: MessageType;
  message_state?: MessageState;
  item_list?: MessageItem[];
  context_token?: string;
}

export interface GetUpdatesResp {
  ret?: number;
  retmsg?: string;
  sync_buf?: string;
  get_updates_buf?: string;
  msgs?: WeixinMessage[];
}

export interface OutboundMessage {
  from_user_id: string;
  to_user_id: string;
  client_id: string;
  message_type: MessageType;
  message_state: MessageState;
  context_token: string;
  item_list: MessageItem[];
}

export interface SendMessageReq {
  msg: OutboundMessage;
}

export interface GetConfigResp {
  ret?: number;
  retmsg?: string;
  typing_ticket?: string;
}

export interface SendTypingReq {
  ilink_user_id: string;
  typing_ticket: string;
  status: TypingStatus;
}

export interface GetUploadUrlReq {
  file_type: string;
  file_size: number;
  file_name: string;
}

export interface GetUploadUrlResp {
  errcode?: number;
  ret?: number;
  url: string;
  aes_key: string;
  encrypt_query_param: string;
}
