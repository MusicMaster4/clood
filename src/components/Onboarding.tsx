import { useEffect, useState } from "react";
import { pairingQrDataUrl } from "../lib/pairing-qr";
import { ArrowRight, Check, Eye, EyeOff, RefreshCw, Shield, Smartphone } from "lucide-react";
import {
  getPairingPayload,
  refreshTailscale,
  type CloodConfig,
  type PairingPayload,
  type RuntimeStatus,
} from "../lib/api";
import { GPT_LIVE_VOICES, ORB_SWATCHES } from "../lib/voices";
import { Orb } from "./Orb";

interface OnboardingProps {
  config: CloodConfig;
  status: RuntimeStatus;
  onSave: (next: CloodConfig) => Promise<void>;
  onStatusChange: (status: RuntimeStatus) => void;
}

export function Onboarding({ config, status, onSave, onStatusChange }: OnboardingProps) {
  const [step, setStep] = useState<0 | 1 | 2>(0);
  const [draft, setDraft] = useState<CloodConfig>(config);
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [payload, setPayload] = useState<PairingPayload | null>(null);
  const [qrUrl, setQrUrl] = useState("");

  useEffect(() => {
    if (step !== 2 || !status.tailscale.available) return;
    let cancelled = false;
    void getPairingPayload()
      .then(async (p) => {
        if (cancelled) return;
        setPayload(p);
        const url = await pairingQrDataUrl(p.uri, 260);
        if (!cancelled) setQrUrl(url);
      })
      .catch(() => {
        if (!cancelled) setQrUrl("");
      });
    return () => {
      cancelled = true;
    };
  }, [step, status.tailscale.available, status.tailscale.ipv4]);

  const handleCheckTailscale = async () => {
    setBusy(true);
    try {
      const next = await refreshTailscale();
      onStatusChange(next);
    } finally {
      setBusy(false);
    }
  };

  const handleNextFromStep0 = async () => {
    setBusy(true);
    try {
      await onSave(draft);
      setStep(1);
      const next = await refreshTailscale();
      onStatusChange(next);
    } finally {
      setBusy(false);
    }
  };

  const handleFinish = async () => {
    setBusy(true);
    try {
      await onSave({ ...draft, onboarding_completed: true });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="onboarding">
      <div className="onboarding-visual">
        <div className="onboarding-orb-wrap">
          <Orb
            state={step === 2 && status.phone_connected ? "speaking" : "idle"}
            color={draft.orb_color}
            level={step === 2 && status.phone_connected ? 0.35 : 0}
            previewTint
            size={210}
          />
        </div>
        <div className="step-dots" aria-label="Setup steps">
          {[0, 1, 2].map((idx) => (
            <button
              key={idx}
              type="button"
              className="step-dot"
              data-active={step === idx ? "1" : "0"}
              data-done={step > idx ? "1" : "0"}
              onClick={() => setStep(idx as 0 | 1 | 2)}
              aria-label={`Step ${idx + 1}`}
            />
          ))}
        </div>
      </div>

      <div className="onboarding-card">
        {step === 0 && (
          <>
            <div className="onboarding-head">
              <span className="step-kicker">01 / 03</span>
              <h2>OpenAI & Voice</h2>
            </div>

            <div className="onboarding-fields">
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
                  <span>Agent Name</span>
                  <input
                    type="text"
                    value={draft.agent_name}
                    maxLength={32}
                    placeholder="Clood"
                    onChange={(e) => setDraft({ ...draft, agent_name: e.target.value })}
                  />
                </label>

                <label className="field">
                  <span>Voice (GPT Live 1.1)</span>
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
              </div>

              <div className="field">
                <span>Orb Color</span>
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
            </div>

            <div className="onboarding-foot">
              <button
                type="button"
                className="ghost-btn"
                onClick={() => void handleFinish()}
                disabled={busy}
              >
                Skip
              </button>
              <button
                type="button"
                className="primary-btn"
                onClick={() => void handleNextFromStep0()}
                disabled={busy}
              >
                Continue
                <ArrowRight size={14} />
              </button>
            </div>
          </>
        )}

        {step === 1 && (
          <>
            <div className="onboarding-head">
              <span className="step-kicker">02 / 03</span>
              <h2>Tailscale Network</h2>
            </div>

            <div className="ts-check-box" data-ok={status.tailscale.available ? "1" : "0"}>
              <div className="ts-check-row">
                <Shield size={18} />
                <span className="ts-check-title">
                  {status.tailscale.available ? "Connected" : "Tailscale Offline"}
                </span>
              </div>
              {status.tailscale.available ? (
                <div className="ts-check-meta mono">
                  {status.tailscale.ipv4}
                  {status.tailscale.tailnet_name ? ` · ${status.tailscale.tailnet_name}` : ""}
                </div>
              ) : (
                <p className="ts-check-hint">
                  Open Tailscale on this PC and sign into your private Tailnet.
                </p>
              )}
            </div>

            <label className="toggle-row">
              <span>Start with Windows (in tray)</span>
              <input
                type="checkbox"
                checked={draft.launch_at_startup}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    launch_at_startup: e.target.checked,
                    start_in_tray: true,
                  })
                }
              />
              <span className="toggle-track" />
            </label>

            <div className="onboarding-foot">
              <button
                type="button"
                className="ghost-btn"
                onClick={() => void handleCheckTailscale()}
                disabled={busy}
              >
                <RefreshCw size={14} className={busy ? "spin" : ""} />
                Re-check
              </button>
              <button
                type="button"
                className="primary-btn"
                onClick={() => void onSave(draft).then(() => setStep(2))}
                disabled={busy}
              >
                Continue
                <ArrowRight size={14} />
              </button>
            </div>
          </>
        )}

        {step === 2 && (
          <>
            <div className="onboarding-head">
              <span className="step-kicker">03 / 03</span>
              <h2>Pair Phone</h2>
            </div>

            <div className="onboarding-qr-wrap">
              {qrUrl && status.tailscale.available ? (
                <div className="qr-frame">
                  <img src={qrUrl} alt="Tailscale Pairing QR" width={196} height={196} />
                </div>
              ) : (
                <div className="qr-empty onboarding-qr-empty">
                  <p>Connect Tailscale first to generate your private QR.</p>
                </div>
              )}

              <div className="pair-phone-pill" data-online={status.phone_connected ? "1" : "0"}>
                <Smartphone size={14} />
                <span>
                  {status.phone_connected
                    ? `${status.phone_device_name || "Phone"} paired`
                    : payload
                      ? `Scan on ${payload.host}`
                      : "Waiting for Tailscale"}
                </span>
              </div>
            </div>

            <div className="onboarding-foot">
              <button type="button" className="ghost-btn" onClick={() => setStep(1)} disabled={busy}>
                Back
              </button>
              <button
                type="button"
                className="primary-btn"
                onClick={() => void handleFinish()}
                disabled={busy}
              >
                <Check size={14} />
                {status.phone_connected ? "Start Clood" : "Finish"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
