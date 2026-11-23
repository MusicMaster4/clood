import { Download, RefreshCw, X } from "lucide-react";
import type { Updater } from "../hooks/useUpdater";
import { channelLabel } from "../lib/update";

export function UpdateModal({ updater }: { updater: Updater }) {
  if (!updater.dialogOpen) return null;

  const { status, version } = updater;
  const label = channelLabel(version);

  return (
    <div className="modal-backdrop" onClick={updater.close}>
      <div className="modal-card update-card" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <span className="modal-title">
            v{version} <span className="channel-badge">{label}</span>
          </span>
          <button className="icon-btn-sm" onClick={updater.close} aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="update-body">
          {status.kind === "checking" && <p className="muted">Checking…</p>}
          {status.kind === "current" && <p className="muted">Up to date</p>}
          {status.kind === "idle" && <p className="muted">Ready</p>}
          {status.kind === "failed" && <p className="error-text">{status.message}</p>}
          {status.kind === "available" && (
            <>
              <p className="update-ver">v{status.update.version}</p>
              {status.update.notes && <p className="update-notes">{status.update.notes}</p>}
            </>
          )}
          {status.kind === "installing" && (
            <div className="progress-wrap">
              <p className="muted">Installing v{status.version}…</p>
              <div className="progress-track">
                <div
                  className="progress-bar"
                  style={{ width: `${Math.round((status.fraction ?? 0.35) * 100)}%` }}
                />
              </div>
            </div>
          )}
        </div>

        <div className="modal-actions">
          {status.kind === "available" ? (
            <button className="primary-btn" onClick={updater.install}>
              <Download size={14} />
              Install
            </button>
          ) : (
            <button
              className="ghost-btn"
              onClick={updater.check}
              disabled={status.kind === "checking" || status.kind === "installing"}
            >
              <RefreshCw size={14} className={status.kind === "checking" ? "spin" : ""} />
              Check
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
