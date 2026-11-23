export interface VoiceOption {
  id: string;
  label: string;
  tone: string;
}

export const GPT_LIVE_VOICES: readonly VoiceOption[] = [
  { id: "marin", label: "Marin", tone: "Warm" },
  { id: "cedar", label: "Cedar", tone: "Calm" },
  { id: "alloy", label: "Alloy", tone: "Neutral" },
  { id: "ash", label: "Ash", tone: "Crisp" },
  { id: "ballad", label: "Ballad", tone: "Expressive" },
  { id: "coral", label: "Coral", tone: "Bright" },
  { id: "echo", label: "Echo", tone: "Deep" },
  { id: "sage", label: "Sage", tone: "Measured" },
  { id: "shimmer", label: "Shimmer", tone: "Airy" },
  { id: "verse", label: "Verse", tone: "Fluid" },
  { id: "vesper", label: "Vesper", tone: "Velvet" },
  { id: "pearl", label: "Pearl", tone: "Clear" },
  { id: "quartz", label: "Quartz", tone: "Focused" },
  { id: "ripple", label: "Ripple", tone: "Lively" },
  { id: "calandria", label: "Calandria", tone: "LatAm" },
  { id: "compas", label: "Compás", tone: "LatAm" },
  { id: "hornero", label: "Hornero", tone: "LatAm" },
  { id: "luciernaga", label: "Luciérnaga", tone: "LatAm" },
];

export const ORB_SWATCHES: readonly { hex: string; name: string }[] = [
  { hex: "#7c8cff", name: "Indigo" },
  { hex: "#38e8d6", name: "Cyan" },
  { hex: "#b47cff", name: "Violet" },
  { hex: "#34d399", name: "Emerald" },
  { hex: "#f59e0b", name: "Amber" },
  { hex: "#fb7185", name: "Rose" },
  { hex: "#e5e7eb", name: "Pearl" },
  { hex: "#60a5fa", name: "Cobalt" },
];

export const AUDIO_CODECS: readonly { id: "adpcm_24k" | "ulaw_24k"; label: string }[] = [
  { id: "adpcm_24k", label: "ADPCM · 96 kbps" },
  { id: "ulaw_24k", label: "µ-law · 192 kbps" },
];
