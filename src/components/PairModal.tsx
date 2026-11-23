import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { RefreshCw, RotateCcw, Shield, Smartphone, X } from "lucide-react";
import {
  getPairingPayload,
  refreshTailscale,
  rotatePairingToken,
  type PairingPayload,
  type RuntimeStatus,
} from "../lib/api";

interface PairModalProps {
  open: boolean;
  status: RuntimeStatus;
  onClose: () => void;
  onStatusChange: (status: RuntimeStatus) => void;
}

export function PairModal({ open, status, onClose, onStatusChange }: PairModalProps) {
  const [payload, setPayload] = useState<PairingPayload | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState<string>("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setBusy(true);
    setError(null);
    try {
      const p = await getPairingPayload();
      setPayload(p);
      const url = await QRCode.toDataURL(p.uri, {
        width: 260,
        margin: 2,
        color: { dark: "#f4f4f5", light: "#00000000" },
        errorCorrectionLevel: "M",
      });
      setQrDataUrl(url);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setPayload(null);
      setQrDataUrl("");
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    if (open) void load();
  }, [open, status.tailscale.ipv4]);

  if (!open) return null;

  const handleRefresh = async () => {
    setBusy(true);
    try {
      const next = await refreshTailscale();
      onStatusChange(next);
      await load();
    } finally {
      setBusy(false);
    }
  };

  const handleRotate = async () => {
    setBusy(true);
    setError(null);
    try {
      const p = await rotatePairingToken();
      setPayload(p);
      const url = await QRCode.toDataURL(p.uri, {
        width: 260,
        margin: 2,
        color: { dark: "#f4f4f5", light: "#00000000" },
        errorCorrectionLevel: "M",
      });
      setQrDataUrl(url);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card pair-card" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <span className="modal-title">
            <Shield size={15} />
            Tailscale
          </span>
          <button className="icon-btn-sm" onClick={onClose} aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="pair-body">
          {qrDataUrl && status.tailscale.available ? (
            <div className="qr-frame">
              <img src={qrDataUrl} alt="Pairing QR" width={220} height={220} />
            </div>
          ) : (
            <div className="qr-empty">
              <p>{error || status.tailscale.error || "Tailscale offline"}</p>
            </div>
          )}

          {payload && status.tailscale.available && (
            <div className="pair-meta">
              <span className="mono">{payload.host}:{payload.port}</span>
              {payload.tailnet && <span className="muted-dot">·</span>}
              {payload.tailnet && <span className="muted">{payload.tailnet}</span>}
            </div>
          )}

          <div className="pair-phone-pill" data-online={status.phone_connected ? "1" : "0"}>
            <Smartphone size={14} />
            <span>
              {status.phone_connected
                ? status.phone_device_name || "Connected"
                : "Waiting for scan"}
            </span>
          </div>
        </div>

        <div className="modal-actions">
          <button className="ghost-btn" onClick={handleRefresh} disabled={busy}>
            <RefreshCw size={14} className={busy ? "spin" : ""} />
            Sync
          </button>
          <button className="ghost-btn" onClick={handleRotate} disabled={busy || !status.tailscale.available}>
            <RotateCcw size={14} />
            New Key
          </button>
        </div>
      </div>
    </div>
  );
}
