/**
 * Stamps a resolved version and channel-isolated updater endpoint onto the workspace
 * before building a release (never committed back to git).
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { channelOf, parseVersion } from "../src/lib/version-core.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const CRATE = "clood";
export const DEFAULT_REPO = "MusicMaster4/clood";
export const BETA_POINTER_TAG = "channel-testing";

export function endpointFor(channel, repo = DEFAULT_REPO) {
  return channel === "testing"
    ? `https://github.com/${repo}/releases/download/${BETA_POINTER_TAG}/latest.json`
    : `https://github.com/${repo}/releases/latest/download/latest.json`;
}

export function withPackageVersion(pkg, version) {
  return { ...pkg, version };
}

export function withTauriConfig(config, { version, channel, repo = DEFAULT_REPO }) {
  const updater = {
    ...(config.plugins?.updater ?? {}),
    endpoints: [endpointFor(channel, repo)],
  };
  return {
    ...config,
    version,
    plugins: { ...(config.plugins ?? {}), updater },
  };
}

export function withCargoVersion(text, version) {
  let inPackage = false;
  let replaced = false;
  const lines = text.split("\n").map((line) => {
    const section = /^\s*\[([^\]]+)\]/.exec(line);
    if (section) inPackage = section[1] === "package";
    else if (inPackage && !replaced && /^\s*version\s*=/.test(line)) {
      replaced = true;
      return `version = "${version}"`;
    }
    return line;
  });
  if (!replaced) throw new Error("no [package] version found in Cargo.toml");
  return lines.join("\n");
}

export function withCargoLockVersion(text, crate, version) {
  const pattern = new RegExp(`(name = "${crate}"\\r?\\n)version = "[^"]*"`);
  if (!pattern.test(text)) throw new Error(`crate ${crate} not found in Cargo.lock`);
  return text.replace(pattern, `$1version = "${version}"`);
}

export function parseArgs(argv) {
  const args = { repo: process.env.GITHUB_REPOSITORY || DEFAULT_REPO };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--version" || arg === "--channel" || arg === "--repo") {
      args[arg.slice(2)] = argv[++i];
    } else {
      throw new Error(`unknown argument: ${arg}`);
    }
  }
  if (!args.version || !parseVersion(args.version)) {
    throw new Error(`--version must be X.Y.Z or X.Y.Z-testing.N, got: ${args.version}`);
  }
  const implied = channelOf(args.version);
  if (!args.channel) args.channel = implied;
  if (args.channel !== implied) {
    throw new Error(`version ${args.version} belongs to the ${implied} channel, not ${args.channel}`);
  }
  return args;
}

function editJson(file, edit) {
  const abs = path.join(ROOT, file);
  const value = edit(JSON.parse(readFileSync(abs, "utf8")));
  writeFileSync(abs, `${JSON.stringify(value, null, 2)}\n`);
}

function editText(file, edit) {
  const abs = path.join(ROOT, file);
  writeFileSync(abs, edit(readFileSync(abs, "utf8")));
}

function main() {
  const { version, channel, repo } = parseArgs(process.argv.slice(2));
  editJson("package.json", (pkg) => withPackageVersion(pkg, version));
  editJson("src-tauri/tauri.conf.json", (config) => withTauriConfig(config, { version, channel, repo }));
  editText("src-tauri/Cargo.toml", (text) => withCargoVersion(text, version));
  if (existsSync(path.join(ROOT, "src-tauri/Cargo.lock"))) {
    editText("src-tauri/Cargo.lock", (text) => withCargoLockVersion(text, CRATE, version));
  }
  console.log(`version ${version} applied (channel: ${channel}, endpoint: ${endpointFor(channel, repo)})`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
