import { useEffect, useState } from "react";
import { Check, Copy, Trash2, X } from "lucide-react";
import { deleteCall, listCalls, type CallRecord } from "../lib/api";

interface HistoryDrawerProps {
  open: boolean;
  onClose: () => void;
}

function formatDuration(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

export function HistoryDrawer({ open, onClose }: HistoryDrawerProps) {
  const [calls, setCalls] = useState<CallRecord[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const load = async () => {
    const items = await listCalls();
    setCalls(items);
  };

  useEffect(() => {
    if (open) void load();
  }, [open]);

  if (!open) return null;

  const selected = calls.find((c) => c.call_id === selectedId) ?? calls[0] ?? null;

  const handleCopy = async (call: CallRecord) => {
    await navigator.clipboard.writeText(call.transcript_text || "");
    setCopiedId(call.call_id);
    window.setTimeout(() => setCopiedId(null), 1500);
  };

  const handleDelete = async (callId: string) => {
    await deleteCall(callId);
    setCalls((prev) => prev.filter((c) => c.call_id !== callId));
    if (selectedId === callId) setSelectedId(null);
  };

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <aside className="drawer-panel history-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <span className="modal-title">Transcripts</span>
          <button className="icon-btn-sm" onClick={onClose} aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="drawer-scroll">
          {calls.length === 0 ? (
            <div className="empty-state">No calls yet</div>
          ) : (
            <div className="history-list">
              {calls.map((c) => {
                const active = selected?.call_id === c.call_id;
                return (
                  <div
                    key={c.call_id}
                    className="history-item"
                    data-active={active ? "1" : "0"}
                    onClick={() => setSelectedId(c.call_id)}
                  >
                    <div className="history-row">
                      <span className="history-name">{c.caller_name}</span>
                      <span className="history-meta mono">
                        {c.status} · {formatDuration(c.duration_sec)}
                      </span>
                    </div>
                    {active && (
                      <div className="history-detail">
                        {c.error && <div className="call-error">{c.error}</div>}
                        <div className="transcript-box">
                          {c.transcript.length > 0 ? (
                            c.transcript.map((entry, idx) => (
                              <div key={idx} className="transcript-line" data-speaker={entry.speaker}>
                                <span className="speaker-tag">
                                  {entry.speaker === "agent" ? c.caller_name : "You"}
                                </span>
                                <span>{entry.text}</span>
                              </div>
                            ))
                          ) : (
                            <span className="muted">Empty transcript</span>
                          )}
                        </div>
                        <div className="history-actions">
                          <button
                            type="button"
                            className="ghost-btn-sm"
                            onClick={(e) => {
                              e.stopPropagation();
                              void handleCopy(c);
                            }}
                          >
                            {copiedId === c.call_id ? <Check size={12} /> : <Copy size={12} />}
                            Copy
                          </button>
                          <button
                            type="button"
                            className="ghost-btn-sm danger"
                            onClick={(e) => {
                              e.stopPropagation();
                              void handleDelete(c.call_id);
                            }}
                          >
                            <Trash2 size={12} />
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}
