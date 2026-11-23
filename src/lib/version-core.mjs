/**
 * Channel-isolated odometer semver arithmetic for Clood's two release channels:
 *   - `main`    -> stable  X.Y.Z
 *   - `testing` -> beta    X.Y.Z-testing.N
 *
 * Patch rolls 0..99 into minor; minor rolls 0..99 into major.
 */

export const BETA_CHANNEL = "testing";
export const INITIAL_STABLE_VERSION = "1.0.0";
export const PATCH_MAX = 99;
export const MINOR_MAX = 99;

const VERSION_RE = new RegExp(`^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-${BETA_CHANNEL}\\.(\\d+))?$`);

export function parseVersion(input) {
  if (typeof input !== "string") return null;
  const match = VERSION_RE.exec(input.trim());
  if (!match) return null;
  const [, major, minor, patch, iteration] = match;
  return {
    major: Number(major),
    minor: Number(minor),
    patch: Number(patch),
    channel: iteration === undefined ? "stable" : "testing",
    iteration: iteration === undefined ? 0 : Number(iteration),
  };
}

export function formatVersion(version) {
  const base = `${version.major}.${version.minor}.${version.patch}`;
  return version.channel === "stable" ? base : `${base}-${BETA_CHANNEL}.${version.iteration}`;
}

export function channelOf(version) {
  return parseVersion(version)?.channel ?? "stable";
}

export function baseOf(version) {
  return {
    major: version.major,
    minor: version.minor,
    patch: version.patch,
    channel: "stable",
    iteration: 0,
  };
}

export function bump(version, level = "patch") {
  let { major, minor, patch } = version;
  if (level === "major") {
    major += 1;
    minor = 0;
    patch = 0;
  } else if (level === "minor") {
    minor += 1;
    patch = 0;
  } else {
    patch += 1;
  }
  if (patch > PATCH_MAX) {
    patch = 0;
    minor += 1;
  }
  if (minor > MINOR_MAX) {
    minor = 0;
    major += 1;
  }
  return { major, minor, patch, channel: "stable", iteration: 0 };
}

export function compareVersions(a, b) {
  if (a.major !== b.major) return a.major - b.major;
  if (a.minor !== b.minor) return a.minor - b.minor;
  if (a.patch !== b.patch) return a.patch - b.patch;
  if (a.channel !== b.channel) return a.channel === "stable" ? 1 : -1;
  return a.iteration - b.iteration;
}

export function isUpdateFor(currentVersion, candidateVersion) {
  const current = parseVersion(currentVersion);
  const candidate = parseVersion(candidateVersion);
  if (!current || !candidate) return false;
  if (current.channel !== candidate.channel) return false;
  return compareVersions(candidate, current) > 0;
}

export function parseTags(tags) {
  return tags.map((tag) => parseVersion(tag)).filter((v) => v !== null);
}

function highest(versions) {
  return versions.reduce((best, v) => (best === null || compareVersions(v, best) > 0 ? v : best), null);
}

export function latestStable(tags) {
  return highest(parseTags(tags).filter((v) => v.channel === "stable"));
}

export function latestIteration(tags, base) {
  const betas = parseTags(tags).filter(
    (v) =>
      v.channel === "testing" &&
      v.major === base.major &&
      v.minor === base.minor &&
      v.patch === base.patch,
  );
  return betas.reduce((max, v) => Math.max(max, v.iteration), 0);
}

export function resolveVersion({ channel, tags, packageVersion, level = "patch" }) {
  const parsedPackage = parseVersion(packageVersion);
  if (!parsedPackage) throw new Error(`package version is not a Clood version: ${packageVersion}`);
  const pkg = baseOf(parsedPackage);
  const stable = latestStable(tags);

  let base;
  if (stable === null && channel === "stable") {
    base = baseOf(parseVersion(INITIAL_STABLE_VERSION));
  } else if (stable === null) {
    base = pkg;
  } else if (compareVersions(pkg, stable) > 0) {
    base = pkg;
  } else {
    base = bump(stable, level);
  }

  if (channel === "stable") return formatVersion(base);

  return formatVersion({
    ...base,
    channel: "testing",
    iteration: latestIteration(tags, base) + 1,
  });
}

export function channelForBranch(branch) {
  const name = branch.replace(/^refs\/heads\//, "");
  if (name === "main") return "stable";
  if (name === BETA_CHANNEL) return "testing";
  return null;
}
