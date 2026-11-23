import { useEffect, useState } from "react";
import { Check, Copy, Eye, EyeOff, X } from "lucide-react";
import type { AudioCodec, CloodConfig } from "../lib/api";
import { AUDIO_CODECS, GPT_LIVE_VOICES, ORB_SWATCHES } from "../lib/voices";
import { Orb } from "./Orb";

interface SettingsDrawerProps {
  open: boolean;
  config: CloodConfig;
  apiUrl: string;
  onClose: () => void;
  onSave: (next: CloodConfig) => Promise<void>;
}

export function SettingsDrawer({ open, config, apiUrl, onClose, onSave }: SettingsDrawerProps) {
  const [draft, setDraft] = useState<CloodConfig>(config);
  const [showKey, setShowKey] = useState(false);
  const [saving, setSaving] = useState(false);
  const [copiedApi, setCopiedApi] = useState(false);

  useEffect(() => {
    if (open) setDraft(config);
  }, [open, config]);

  if (!open) return null;

  const commit = async (next: CloodConfig) => {
    setSaving(true);
    try {
      await onSave(next);
    } finally {
      setSaving(false);
    }
  };

  const handleCopyApi = async () => {
    await navigator.clipboard.writeText(apiUrl);
    setCopiedApi(true);
    window.setTimeout(() => setCopiedApi(false), 1500);
  };

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <aside className="drawer-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <span className="modal-title">Config</span>
          <button className="icon-btn-sm" onClick={onClose} aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="drawer-scroll">
          <div className="settings-preview">
            <Orb state="idle" color={draft.orb_color} previewTint size={64} />
            <div className="swatch-row">
              {ORB_SWATCHES.map((s) => (
                <button
                  key={s.hex}
                  type="button"
                  className="swatch"
                  data-active={draft.orb_color.toLowerCase() === s.hex.toLowerCase() ? "1" : "0"}
                  style={{ ["--swatch" as string]: s.hex }}
                  title={s.name}
                  onClick={() => setDraft({ ...draft, orb_color: s.hex })}
                />
              ))}
              <label className="swatch swatch-custom" title="Custom">
                <input
                  type="color"
                  value={draft.orb_color}
                  onChange={(e) => setDraft({ ...draft, orb_color: e.target.value })}
                />
              </label>
            </div>
          </div>

          <label className="field">
            <span>Name</span>
            <input
              type="text"
              value={draft.agent_name}
              maxLength={32}
              placeholder="Clood"
              onChange={(e) => setDraft({ ...draft, agent_name: e.target.value })}
            />
          </label>

          <label className="field">
            <span>OpenAI API Key</span>
            <div className="input-wrap">
              <input
                type={showKey ? "text" : "password"}
                value={draft.openai_api_key}
                placeholder="sk-..."
                autoComplete="off"
                onChange={(e) => setDraft({ ...draft, openai_api_key: e.target.value.trim() })}
              />
              <button
                type="button"
                className="input-action"
                onClick={() => setShowKey((v) => !v)}
                aria-label="Toggle key"
              >
                {showKey ? <EyeOff size={14} /> : <Eye size={14} />}
              </button>
            </div>
          </label>

          <div className="field-row">
            <label className="field">
              <span>Voice</span>
              <select
                value={draft.voice}
                onChange={(e) => setDraft({ ...draft, voice: e.target.value })}
              >
                {GPT_LIVE_VOICES.map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.label} · {v.tone}
                  </option>
                ))}
              </select>
            </label>

            <label className="field">
              <span>Audio</span>
              <select
                value={draft.audio_codec}
                onChange={(e) => setDraft({ ...draft, audio_codec: e.target.value as AudioCodec })}
              >
                {AUDIO_CODECS.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <label className="field">
            <span>Base Prompt</span>
            <textarea
              rows={3}
              value={draft.base_instructions}
              onChange={(e) => setDraft({ ...draft, base_instructions: e.target.value })}
            />
          </label>

          <div className="field-row">
            <label className="field">
              <span>Tailscale Port</span>
              <input
                type="number"
                min={1024}
                max={65535}
                value={draft.tailscale_port}
                onChange={(e) =>
                  setDraft({ ...draft, tailscale_port: Number(e.target.value) || 19480 })
                }
              />
            </label>

            <label className="field">
              <span>Agent API Port</span>
              <input
                type="number"
                min={1024}
                max={65535}
                value={draft.api_port}
                onChange={(e) =>
                  setDraft({ ...draft, api_port: Number(e.target.value) || 19481 })
                }
              />
            </label>
          </div>

          <div className="api-pill">
            <span className="mono">{apiUrl}</span>
            <button type="button" className="icon-btn-sm" onClick={handleCopyApi} aria-label="Copy API URL">
              {copiedApi ? <Check size={13} /> : <Copy size={13} />}
            </button>
          </div>
        </div>

        <div className="modal-actions">
          <button
            className="primary-btn"
            disabled={saving}
            onClick={() => void commit(draft).then(onClose)}
          >
            {saving ? "Saving…" : "Save"}
          </button>
        </div>
      </aside>
    </div>
  );
}
