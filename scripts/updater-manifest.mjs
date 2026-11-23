/**
 * Builds `latest.json` for Tauri's Windows NSIS updater from signed release assets.
 */
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { parseVersion } from "../src/lib/version-core.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const WINDOWS_ARTIFACT = /-setup\.exe(\.zip)?$|\.nsis\.zip$/;
export const REQUIRED_PLATFORMS = ["windows-x86_64", "windows-x86_64-nsis"];

export function downloadUrl(repo, tag, assetName) {
  return `https://github.com/${repo}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(assetName)}`;
}

export function collectAssets(dir) {
  const entries = readdirSync(dir, { recursive: true, withFileTypes: false });
  return entries
    .map((entry) => String(entry).split(path.sep).join("/"))
    .filter((entry) => entry.endsWith(".sig"))
    .map((entry) => ({
      name: entry.slice(entry.lastIndexOf("/") + 1, -".sig".length),
      signature: readFileSync(path.join(dir, entry), "utf8").trim(),
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

export function platformsForAsset(name) {
  if (WINDOWS_ARTIFACT.test(name)) return ["windows-x86_64", "windows-x86_64-nsis"];
  return [];
}

export function buildManifest({ version, repo, tag, notes = "", pubDate = new Date().toISOString(), assets }) {
  if (!parseVersion(version)) throw new Error(`not a Clood version: ${version}`);
  const platforms = {};
  for (const asset of assets) {
    const entry = { signature: asset.signature, url: downloadUrl(repo, tag, asset.name) };
    for (const platform of platformsForAsset(asset.name)) {
      if (platforms[platform]) throw new Error(`multiple signed updater artifacts for ${platform}`);
      platforms[platform] = entry;
    }
  }
  const missing = REQUIRED_PLATFORMS.filter((platform) => !platforms[platform]);
  if (missing.length) {
    throw new Error(
      `missing signed updater artifacts for ${missing.join(", ")} among: ${
        assets.map((asset) => asset.name).join(", ") || "(none)"
      }`,
    );
  }
  return { version, notes, pub_date: pubDate, platforms };
}

function parseArgs(argv) {
  const args = {
    repo: process.env.GITHUB_REPOSITORY || "MusicMaster4/clood",
    bundleDir: "release-assets",
    out: "latest.json",
    notes: "",
  };
  const keys = {
    "--version": "version",
    "--tag": "tag",
    "--repo": "repo",
    "--notes": "notes",
    "--bundle-dir": "bundleDir",
    "--out": "out",
  };
  for (let i = 0; i < argv.length; i += 1) {
    const key = keys[argv[i]];
    if (!key) throw new Error(`unknown argument: ${argv[i]}`);
    args[key] = argv[++i];
  }
  if (!args.version) throw new Error("--version is required");
  if (!args.tag) args.tag = `v${args.version}`;
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const dir = path.isAbsolute(args.bundleDir) ? args.bundleDir : path.join(ROOT, args.bundleDir);
  const assets = collectAssets(dir);
  const manifest = buildManifest({ ...args, assets });
  const out = path.isAbsolute(args.out) ? args.out : path.join(ROOT, args.out);
  writeFileSync(out, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`${args.out} -> ${REQUIRED_PLATFORMS.length} updater platforms`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
