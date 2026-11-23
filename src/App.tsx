import { useEffect, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import {
  ArrowUpCircle,
  History,
  Minus,
  Phone,
  PhoneOff,
  QrCode,
  SlidersHorizontal,
  X,
} from "lucide-react";
import { Orb, type OrbState } from "./components/Orb";
import { Onboarding } from "./components/Onboarding";
import { PairModal } from "./components/PairModal";
import { SettingsDrawer } from "./components/SettingsDrawer";
import { HistoryDrawer } from "./components/HistoryDrawer";
import { UpdateModal } from "./components/UpdateModal";
import { useUpdater } from "./hooks/useUpdater";
import {
  getConfig,
  getStatus,
  hangupActiveCall,
  onAudioLevelEvent,
  onStatusEvent,
  saveConfig,
  startTestCall,
  type CloodConfig,
  type RuntimeStatus,
} from "./lib/api";

const TAURI = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

function formatTimer(startedAtMs: number | null): string {
  if (!startedAtMs) return "0:00";
  const sec = Math.max(0, Math.floor((Date.now() - startedAtMs) / 1000));
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

export function App() {
  const [config, setConfig] = useState<CloodConfig | null>(null);
  const [status, setStatus] = useState<RuntimeStatus | null>(null);
  const [audio, setAudio] = useState({ speaking: false, level: 0 });
  const [pairOpen, setPairOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [, setTick] = useState(0);
  const updater = useUpdater();

  useEffect(() => {
    let disposed = false;
    let gotStatus = false;
    let gotAudio = false;
    void getConfig().then((next) => {
      if (!disposed) setConfig(next);
    });

    const statusListener = onStatusEvent((next) => {
      if (disposed) return;
      gotStatus = true;
      gotAudio = true;
      setStatus(next);
      setAudio({ speaking: next.agent_speaking, level: next.audio_level });
    });
    const audioListener = onAudioLevelEvent((level) => {
      if (disposed) return;
      gotAudio = true;
      setAudio(level);
    });
    void Promise.all([statusListener, audioListener]).then(async () => {
      if (disposed) return;
      const next = await getStatus();
      if (disposed) return;
      if (!gotStatus) setStatus(next);
      if (!gotAudio) setAudio({ speaking: next.agent_speaking, level: next.audio_level });
    });

    return () => {
      disposed = true;
      void statusListener.then((unlisten) => unlisten());
      void audioListener.then((unlisten) => unlisten());
    };
  }, []);

  const activeCall = status?.active_call ?? null;
  const isRinging = activeCall?.status === "ringing";
  const isActive = activeCall?.status === "active";

  useEffect(() => {
    if (!isActive) return;
    const id = window.setInterval(() => setTick((t) => t + 1), 1000);
    return () => window.clearInterval(id);
  }, [isActive]);

  const showToast = (msg: string) => {
    setToast(msg);
    window.setTimeout(() => setToast(null), 2600);
  };

  const handleSaveConfig = async (next: CloodConfig) => {
    const saved = await saveConfig(next);
    setConfig(saved);
    const st = await getStatus();
    setStatus(st);
  };

  const handleCallToggle = async () => {
    if (isRinging || isActive) {
      await hangupActiveCall();
      return;
    }
    if (!config?.openai_api_key) {
      setSettingsOpen(true);
      showToast("Add OpenAI key");
      return;
    }
    if (!status?.phone_connected) {
      setPairOpen(true);
      showToast("Pair phone first");
      return;
    }
    try {
      await startTestCall();
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const orbState: OrbState = isRinging ? "ringing" : audio.speaking ? "speaking" : "idle";
  const orbColor = config?.orb_color ?? "#7c8cff";
  const updateAvailable = updater.status.kind === "available";
  const showOnboarding = Boolean(config && status && !config.onboarding_completed);

  return (
    <div className="shell">
      <header className="titlebar" data-tauri-drag-region>
        <div className="titlebar-left" data-tauri-drag-region>
          <span
            className="status-dot"
            data-online={status?.phone_connected ? "1" : status?.tailscale.available ? "ts" : "0"}
          />
          <span className="brand">{config?.agent_name || "Clood"}</span>
        </div>
        <div className="titlebar-right">
          {TAURI && (
            <>
              <button
                className="win-btn"
                onClick={() => void getCurrentWindow().minimize()}
                aria-label="Minimize"
              >
                <Minus size={14} />
              </button>
              <button
                className="win-btn win-close"
                onClick={() => void getCurrentWindow().close()}
                aria-label="Close"
              >
                <X size={14} />
              </button>
            </>
          )}
        </div>
      </header>

      {showOnboarding && config && status ? (
        <Onboarding
          config={config}
          status={status}
          onSave={handleSaveConfig}
          onStatusChange={setStatus}
        />
      ) : (
        <>
          <main className="stage">
            <div className="orb-wrap">
              {config && status && (
                <Orb
                  state={orbState}
                  color={orbColor}
                  level={audio.level}
                  previewTint={!isActive}
                  size={256}
                  label={config.agent_name || "Clood"}
                />
              )}
            </div>

            <div className="stage-cue">
              {isActive && <span className="mono">{formatTimer(activeCall?.answered_at_ms ?? null)}</span>}
              {isRinging && <span className="cue-pulse">Ringing</span>}
              {!isActive && !isRinging && toast && <span className="cue-toast">{toast}</span>}
            </div>
          </main>

          <nav className="dock" aria-label="Controls">
            <button
              className={isRinging || isActive ? "dock-btn dock-danger" : "dock-btn"}
              onClick={() => void handleCallToggle()}
              title={isRinging || isActive ? "End call" : "Test call"}
              aria-label={isRinging || isActive ? "End call" : "Test call"}
            >
              {isRinging || isActive ? <PhoneOff size={18} /> : <Phone size={18} />}
            </button>

            <button
              className="dock-btn"
              onClick={() => setPairOpen(true)}
              title="Pair"
              aria-label="Pair"
              data-indicator={status?.phone_connected ? "on" : undefined}
            >
              <QrCode size={18} />
            </button>

            <button
              className="dock-btn"
              onClick={() => setHistoryOpen(true)}
              title="Transcripts"
              aria-label="Transcripts"
            >
              <History size={18} />
            </button>

            <button
              className="dock-btn"
              onClick={() => setSettingsOpen(true)}
              title="Settings"
              aria-label="Settings"
            >
              <SlidersHorizontal size={18} />
            </button>

            <button
              className="dock-btn"
              onClick={updater.check}
              title="Updates"
              aria-label="Updates"
              data-badge={updateAvailable ? "1" : undefined}
            >
              <ArrowUpCircle size={18} />
            </button>
          </nav>
        </>
      )}

      {status && (
        <PairModal
          open={pairOpen}
          status={status}
          onClose={() => setPairOpen(false)}
          onStatusChange={setStatus}
        />
      )}

      {config && (
        <SettingsDrawer
          open={settingsOpen}
          config={config}
          apiUrl={status?.api_url ?? `http://127.0.0.1:${config.api_port}`}
          onClose={() => setSettingsOpen(false)}
          onSave={handleSaveConfig}
        />
      )}

      <HistoryDrawer open={historyOpen} onClose={() => setHistoryOpen(false)} />
      <UpdateModal updater={updater} />
    </div>
  );
}
