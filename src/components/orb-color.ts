// OKLCH/OKLab glass-orb color mapping adapted from yt-max, generalized for any user-selected hex color.

export type Lch = [number, number, number]; // OKLCH: lightness 0–1, chroma, hue in degrees
export type Rgb = [number, number, number]; // linear sRGB 0–1
export type Lab = [number, number, number]; // OKLab

export const PEARL: { deep: Lch; mid: Lch; light: Lch; glow: Lch } = {
  deep: [0.24, 0.008, 255],
  mid: [0.64, 0.014, 250],
  light: [0.985, 0.008, 235],
  glow: [0.80, 0.014, 245],
};

export const TINT = {
  midL: -0.08,
  lightL: 0.14,
  lightC: 0.55,
  lightHue: 10,
  deepL: 0.24,
  deepC: 0.04,
  deepHue: -12,
  glowC: 0.85,
  yellowHue: 100,
  yellowWidth: 22,
};

const clamp01 = (n: number) => Math.min(1, Math.max(0, n));
const yellowness = (h: number) => Math.exp(-(((h - TINT.yellowHue) / TINT.yellowWidth) ** 2));

export function lchToLab([l, c, h]: Lch): Lab {
  const r = (h * Math.PI) / 180;
  return [l, c * Math.cos(r), c * Math.sin(r)];
}

export function labToLinear([L, a, b]: Lab): Rgb {
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ];
}

function linearToLab(rgb: Rgb): Lab {
  const l = Math.cbrt(0.4122214708 * rgb[0] + 0.5363325363 * rgb[1] + 0.0514459929 * rgb[2]);
  const m = Math.cbrt(0.2119034982 * rgb[0] + 0.6806995451 * rgb[1] + 0.1073969566 * rgb[2]);
  const s = Math.cbrt(0.0883024619 * rgb[0] + 0.2817188376 * rgb[1] + 0.6299787005 * rgb[2]);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

const inGamut = (c: Rgb) => c.every((v) => v >= -1e-4 && v <= 1 + 1e-4);

export function lchToLinear(lch: Lch): Rgb {
  let rgb = labToLinear(lchToLab(lch));
  if (inGamut(rgb)) return [clamp01(rgb[0]), clamp01(rgb[1]), clamp01(rgb[2])];
  let lo = 0;
  let hi = lch[1];
  for (let i = 0; i < 18; i++) {
    const mid = (lo + hi) / 2;
    if (inGamut(labToLinear(lchToLab([lch[0], mid, lch[2]])))) lo = mid;
    else hi = mid;
  }
  rgb = labToLinear(lchToLab([lch[0], lo, lch[2]]));
  return [clamp01(rgb[0]), clamp01(rgb[1]), clamp01(rgb[2])];
}

export function lchToLabMapped(lch: Lch): Lab {
  return linearToLab(lchToLinear(lch));
}

function decodeSrgb(v: number): number {
  const c = clamp01(v);
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

export function parseHex(hex: string): Rgb {
  const clean = hex.trim().replace(/^#/, "");
  const full = clean.length === 3
    ? clean.split("").map((ch) => ch + ch).join("")
    : clean.padEnd(6, "0").slice(0, 6);
  const n = Number.parseInt(full, 16);
  if (Number.isNaN(n)) return [0.5, 0.5, 0.9];
  return [
    decodeSrgb(((n >> 16) & 255) / 255),
    decodeSrgb(((n >> 8) & 255) / 255),
    decodeSrgb((n & 255) / 255),
  ];
}

export function hexToLch(hex: string): Lch {
  const [L, a, b] = linearToLab(parseHex(hex));
  const c = Math.hypot(a, b);
  let h = (Math.atan2(b, a) * 180) / Math.PI;
  if (h < 0) h += 360;
  return [L, c, h];
}

export function hexToTones(hex: string): [Lab, Lab, Lab, Lab] {
  const [rawL, rawC, h] = hexToLch(hex);
  // Keep luminance within the luminous glass sweet spot so even very dark picks still glow nicely.
  const l = Math.min(0.86, Math.max(0.56, rawL));
  const c = Math.min(0.26, rawC);
  const y = yellowness(h);
  return [
    lchToLabMapped([TINT.deepL, Math.min(c * 0.4, TINT.deepC) * (1 - 0.8 * y), h + TINT.deepHue - 25 * y]),
    lchToLabMapped([l + TINT.midL * (1 - y), c, h]),
    lchToLabMapped([Math.min(0.97, l + TINT.lightL), c * TINT.lightC, h + TINT.lightHue]),
    lchToLabMapped([Math.min(0.88, l + 0.04), c * TINT.glowC, h]),
  ];
}

export const PEARL_TONES: [Lab, Lab, Lab, Lab] = [
  lchToLabMapped(PEARL.deep),
  lchToLabMapped(PEARL.mid),
  lchToLabMapped(PEARL.light),
  lchToLabMapped(PEARL.glow),
];

export function mixLab(a: Lab, b: Lab, t: number): Lab {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}
