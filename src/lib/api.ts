import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

const TAURI = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

export type AudioCodec = "adpcm_24k" | "ulaw_24k";

export interface CloodConfig {
  openai_api_key: string;
  agent_name: string;
  voice: string;
  orb_color: string;
  audio_codec: AudioCodec;
  tailscale_port: number;
  api_port: number;
  pairing_token: string;
  base_instructions: string;
  paired_device_name: string | null;
  launch_at_startup: boolean;
  start_in_tray: boolean;
  onboarding_completed: boolean;
}

export interface TailscaleInfo {
  available: boolean;
  ipv4: string | null;
  dns_name: string | null;
  tailnet_name: string | null;
  error: string | null;
}

export interface TranscriptEntry {
  speaker: "agent" | "user";
  text: string;
  timestamp_ms: number;
}

export interface CallRecord {
  call_id: string;
  caller_name: string;
  status: "ringing" | "active" | "completed" | "declined" | "missed" | "failed" | "cancelled";
  context: string;
  started_at_ms: number;
  answered_at_ms: number | null;
  ended_at_ms: number | null;
  duration_sec: number;
  transcript: TranscriptEntry[];
  transcript_text: string;
  error: string | null;
}

export interface RuntimeStatus {
  tailscale: TailscaleInfo;
  phone_connected: boolean;
  phone_device_name: string | null;
  phone_server_running: boolean;
  api_url: string;
  active_call: CallRecord | null;
  agent_speaking: boolean;
  audio_level: number;
}

export interface PairingPayload {
  uri: string;
  host: string;
  port: number;
  token: string;
  name: string;
  color: string;
  codec: AudioCodec;
  tailnet: string | null;
}

const DEFAULT_CONFIG: CloodConfig = {
  openai_api_key: "",
  agent_name: "Clood",
  voice: "marin",
  orb_color: "#7c8cff",
  audio_codec: "adpcm_24k",
  tailscale_port: 19480,
  api_port: 19481,
  pairing_token: "dev-preview-token",
  base_instructions:
    "You are calling the user over Clood. Be concise, natural, warm, and direct. Respond in the same language as the context or user.",
  paired_device_name: null,
  launch_at_startup: false,
  start_in_tray: true,
  onboarding_completed: false,
};

const DEFAULT_STATUS: RuntimeStatus = {
  tailscale: {
    available: false,
    ipv4: null,
    dns_name: null,
    tailnet_name: null,
    error: "Browser preview mode",
  },
  phone_connected: false,
  phone_device_name: null,
  phone_server_running: false,
  api_url: "http://127.0.0.1:19481",
  active_call: null,
  agent_speaking: false,
  audio_level: 0,
};

export async function getConfig(): Promise<CloodConfig> {
  if (!TAURI) return DEFAULT_CONFIG;
  return invoke<CloodConfig>("get_config");
}

export async function saveConfig(config: CloodConfig): Promise<CloodConfig> {
  if (!TAURI) return config;
  return invoke<CloodConfig>("save_config", { config });
}

export async function getStatus(): Promise<RuntimeStatus> {
  if (!TAURI) return DEFAULT_STATUS;
  return invoke<RuntimeStatus>("get_status");
}

export async function refreshTailscale(): Promise<RuntimeStatus> {
  if (!TAURI) return DEFAULT_STATUS;
  return invoke<RuntimeStatus>("refresh_tailscale");
}

export async function getPairingPayload(): Promise<PairingPayload> {
  if (!TAURI) {
    return {
      uri: "clood://pair?host=100.64.0.1&port=19480&token=preview&name=Clood&color=%237c8cff",
      host: "100.64.0.1",
      port: 19480,
      token: "preview",
      name: "Clood",
      color: "#7c8cff",
      codec: "adpcm_24k",
      tailnet: "preview.ts.net",
    };
  }
  return invoke<PairingPayload>("get_pairing_payload");
}

export async function rotatePairingToken(): Promise<PairingPayload> {
  if (!TAURI) return getPairingPayload();
  return invoke<PairingPayload>("rotate_pairing_token");
}

export async function startTestCall(): Promise<CallRecord> {
  if (!TAURI) {
    throw new Error("Available only in the desktop app.");
  }
  return invoke<CallRecord>("start_test_call");
}

export async function hangupActiveCall(): Promise<void> {
  if (!TAURI) return;
  await invoke("hangup_active_call");
}

export async function listCalls(): Promise<CallRecord[]> {
  if (!TAURI) return [];
  return invoke<CallRecord[]>("list_calls");
}

export async function deleteCall(callId: string): Promise<void> {
  if (!TAURI) return;
  await invoke("delete_call", { callId });
}

export async function onStatusEvent(cb: (status: RuntimeStatus) => void): Promise<UnlistenFn> {
  if (!TAURI) return () => {};
  return listen<RuntimeStatus>("clood://status", (e) => cb(e.payload));
}

export async function onAudioLevelEvent(
  cb: (payload: { speaking: boolean; level: number }) => void,
): Promise<UnlistenFn> {
  if (!TAURI) return () => {};
  return listen<{ speaking: boolean; level: number }>("clood://audio-level", (e) => cb(e.payload));
}

export async function onCallErrorEvent(cb: (error: string) => void): Promise<UnlistenFn> {
  if (!TAURI) return () => {};
  return listen<string>("clood://call-error", (e) => cb(e.payload));
}
