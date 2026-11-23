import {
  BETA_CHANNEL,
  INITIAL_STABLE_VERSION,
  MINOR_MAX,
  PATCH_MAX,
  baseOf as baseOfCore,
  bump as bumpCore,
  channelForBranch as channelForBranchCore,
  channelOf as channelOfCore,
  compareVersions as compareVersionsCore,
  formatVersion as formatVersionCore,
  isUpdateFor as isUpdateForCore,
  latestIteration as latestIterationCore,
  latestStable as latestStableCore,
  parseTags as parseTagsCore,
  parseVersion as parseVersionCore,
  resolveVersion as resolveVersionCore,
} from "./version-core.mjs";

export { BETA_CHANNEL, INITIAL_STABLE_VERSION, MINOR_MAX, PATCH_MAX };

export type Channel = "stable" | "testing";
export type BumpLevel = "patch" | "minor" | "major";

export interface Version {
  major: number;
  minor: number;
  patch: number;
  channel: Channel;
  iteration: number;
}

export interface ResolveOptions {
  channel: Channel;
  tags: readonly string[];
  packageVersion: string;
  level?: BumpLevel;
}

export const parseVersion = parseVersionCore as (input: string) => Version | null;
export const formatVersion = formatVersionCore as (version: Version) => string;
export const channelOf = channelOfCore as (version: string) => Channel;
export const baseOf = baseOfCore as (version: Version) => Version;
export const bump = bumpCore as (version: Version, level?: BumpLevel) => Version;
export const compareVersions = compareVersionsCore as (a: Version, b: Version) => number;
export const isUpdateFor = isUpdateForCore as (currentVersion: string, candidateVersion: string) => boolean;
export const parseTags = parseTagsCore as (tags: readonly string[]) => Version[];
export const latestStable = latestStableCore as (tags: readonly string[]) => Version | null;
export const latestIteration = latestIterationCore as (tags: readonly string[], base: Version) => number;
export const resolveVersion = resolveVersionCore as (opts: ResolveOptions) => string;
export const channelForBranch = channelForBranchCore as (branch: string) => Channel | null;
