import { useCallback, useEffect, useRef, useState } from "react";

import { appVersion, checkForUpdate, type AvailableUpdate } from "../lib/update";
import { channelOf, type Channel } from "../lib/version";

const TAURI_RUNTIME = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

export type UpdateStatus =
  | { kind: "idle" }
  | { kind: "checking" }
  | { kind: "current" }
  | { kind: "available"; update: AvailableUpdate }
  | { kind: "installing"; version: string; fraction: number | null }
  | { kind: "failed"; message: string };

export interface Updater {
  version: string;
  channel: Channel;
  status: UpdateStatus;
  dialogOpen: boolean;
  check: () => void;
  install: () => void;
  close: () => void;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return typeof error === "string" && error ? error : "Update check failed.";
}

export function useUpdater(): Updater {
  const [version, setVersion] = useState("0.1.0");
  const [status, setStatus] = useState<UpdateStatus>({ kind: "idle" });
  const [dialogOpen, setDialogOpen] = useState(false);

  const statusRef = useRef(status);
  statusRef.current = status;
  const inFlight = useRef<Promise<void> | null>(null);
  const visibleCheck = useRef(false);

  useEffect(() => {
    if (!TAURI_RUNTIME) return;
    let cancelled = false;
    void appVersion().then((v) => {
      if (!cancelled) setVersion(v);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const run = useCallback((quiet: boolean): Promise<void> => {
    if (!TAURI_RUNTIME) {
      if (!quiet) setStatus({ kind: "current" });
      return Promise.resolve();
    }
    if (!quiet) {
      visibleCheck.current = true;
      setStatus({ kind: "checking" });
    }
    if (inFlight.current) return inFlight.current;

    const request = (async () => {
      try {
        const update = await checkForUpdate();
        setStatus(update ? { kind: "available", update } : { kind: "current" });
      } catch (error) {
        setStatus(
          visibleCheck.current
            ? { kind: "failed", message: errorMessage(error) }
            : { kind: "idle" },
        );
      } finally {
        inFlight.current = null;
        visibleCheck.current = false;
      }
    })();

    inFlight.current = request;
    return request;
  }, []);

  useEffect(() => {
    if (!TAURI_RUNTIME) return;
    const id = window.setTimeout(() => void run(true), 1200);
    return () => window.clearTimeout(id);
  }, [run]);

  const check = useCallback(() => {
    setDialogOpen(true);
    const current = statusRef.current;
    if (current.kind === "available" || current.kind === "installing") return;
    void run(false);
  }, [run]);

  const install = useCallback(async () => {
    const current = statusRef.current;
    if (current.kind !== "available") return;
    const { update } = current;
    setStatus({ kind: "installing", version: update.version, fraction: null });
    await update
      .install((fraction) =>
        setStatus((prev) => (prev.kind === "installing" ? { ...prev, fraction } : prev)),
      )
      .catch((error) => setStatus({ kind: "failed", message: errorMessage(error) }));
  }, []);

  const close = useCallback(() => setDialogOpen(false), []);

  return { version, channel: channelOf(version), status, dialogOpen, check, install, close };
}
