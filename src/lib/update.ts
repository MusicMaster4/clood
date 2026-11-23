import { getVersion } from "@tauri-apps/api/app";
import { relaunch } from "@tauri-apps/plugin-process";
import { check } from "@tauri-apps/plugin-updater";

import { channelOf, isUpdateFor, type Channel } from "./version";

export type { Channel };

export function channelLabel(version: string): string {
  return channelOf(version) === "testing" ? "beta" : "stable";
}

let cachedVersion: string | null = null;

export async function appVersion(): Promise<string> {
  if (cachedVersion === null) cachedVersion = await getVersion();
  return cachedVersion;
}

export interface AvailableUpdate {
  version: string;
  notes: string | null;
  date: string | null;
  install: (onProgress?: (fraction: number | null) => void) => Promise<void>;
}

export async function checkForUpdate(): Promise<AvailableUpdate | null> {
  const current = await appVersion();
  const update = await check();
  if (!update) return null;
  if (!isUpdateFor(current, update.version)) return null;

  return {
    version: update.version,
    notes: update.body ?? null,
    date: update.date ?? null,
    install: async (onProgress) => {
      let total = 0;
      let received = 0;
      await update.downloadAndInstall((event) => {
        if (event.event === "Started") {
          total = event.data.contentLength ?? 0;
          onProgress?.(total > 0 ? 0 : null);
        } else if (event.event === "Progress") {
          received += event.data.chunkLength;
          onProgress?.(total > 0 ? Math.min(1, received / total) : null);
        } else if (event.event === "Finished") {
          onProgress?.(1);
        }
      });
      await relaunch();
    },
  };
}
