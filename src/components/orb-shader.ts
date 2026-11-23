// Glass-silk glowing orb shader adapted from yt-max.
// Accelerates and brightens smoothly while the agent speaks; rests calm and neutral while silent.

export const EXTENT = 1.6;
export const GLOW_OVERFLOW = (EXTENT - 1) / 2;
export const MAX_DPR = 1.5;
export const MAX_BACKING = 1600;

export const STATES = {
  idle: { speed: 0.16, spin: 0.06, turb: 0, energy: 0, pulse: 0 },
  ringing: { speed: 0.42, spin: 0.36, turb: 0.45, energy: 0.55, pulse: 1 },
  speaking: { speed: 0.92, spin: 0.96, turb: 1.0, energy: 1.0, pulse: 0 },
} as const;

export const TEMPO_LARGE = 0.65;
export const TEMPO_LARGE_PX = 420;
export const EASE_OMEGA = 7;
export const COLOR_OMEGA = 5.5;
export const PULSE_PERIOD = 1.5;
export const PULSE_SCALE = 0.025;

export const FLOW_SCALE = 0.95;
export const SMALL_SCALE_MUL = 0.75;
export const WARP = 1.2;
export const WARP_TURB = 0.24;
export const WARP_FALLOFF = 1.35;
export const OCTAVES = 5;
export const SILK_SHARPNESS = 4;
export const SILK_BANDS = 2.8;
export const DEEP_BANDS = 1.6;
export const SILK_CREASE = 0.4;
export const SILK_SCATTER = 0.5;
export const SILK_AMOUNT = 1.2;
export const DEEP_AMOUNT = 0.42;
export const DEEP_DEPTH = 0.8;
export const THICK_MIN = 0.12;
export const CORE_STRENGTH = 0.1;
export const CORE_ENERGY = 0.18;

export const VOLUME_SHADOW = 0.45;
export const BODY_BASE = 0.18;
export const RIM_POWER = 7;
export const RIM_STRENGTH = 0.5;
export const ARC_STRENGTH = 0.55;
export const SPEC_POWER = 120;
export const SPEC_STRENGTH = 0.32;
export const CAUSTIC_STRENGTH = 0.45;
export const RAMP_MID_FROM = 0.08;
export const RAMP_MID_TO = 0.65;
export const RAMP_LIGHT_FROM = 0.5;
export const EXPOSURE = 1.4;
export const ENERGY_GAIN = 0.14;
export const LIGHT_GAIN = 1.5;

export const HALO_TIGHT = 0.22;
export const HALO_TIGHT_FALLOFF = 16;
export const HALO_WIDE = 0.14;
export const HALO_WIDE_FALLOFF = 4.5;
export const HALO_ENERGY = 0.75;

const f = (n: number) => (Number.isInteger(n) ? n.toFixed(1) : String(n));
const consts = {
  EXTENT,
  PULSE_PERIOD,
  PULSE_SCALE,
  FLOW_SCALE,
  SMALL_SCALE_MUL,
  WARP,
  WARP_TURB,
  WARP_FALLOFF,
  SILK_SHARPNESS,
  SILK_BANDS,
  DEEP_BANDS,
  SILK_CREASE,
  SILK_SCATTER,
  SILK_AMOUNT,
  DEEP_AMOUNT,
  DEEP_DEPTH,
  THICK_MIN,
  CORE_STRENGTH,
  CORE_ENERGY,
  VOLUME_SHADOW,
  BODY_BASE,
  RIM_POWER,
  RIM_STRENGTH,
  ARC_STRENGTH,
  SPEC_POWER,
  SPEC_STRENGTH,
  CAUSTIC_STRENGTH,
  RAMP_MID_FROM,
  RAMP_MID_TO,
  RAMP_LIGHT_FROM,
  EXPOSURE,
  ENERGY_GAIN,
  LIGHT_GAIN,
  HALO_TIGHT,
  HALO_TIGHT_FALLOFF,
  HALO_WIDE,
  HALO_WIDE_FALLOFF,
  HALO_ENERGY,
};

const glslConsts = Object.entries(consts)
  .map(([k, v]) => `const float ${k} = ${f(v)};`)
  .join("\n");

export const VERTEX_SHADER = `
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
`;

export const FRAGMENT_SHADER = `
precision highp float;
uniform vec2 uRes;
uniform float uPhase;
uniform float uSpin;
uniform float uTime;
uniform float uTurb;
uniform float uEnergy;
uniform float uPulse;
uniform float uDetail;
uniform float uSeed;
uniform vec3 uDeep;
uniform vec3 uMid;
uniform vec3 uLight;
uniform vec3 uGlow;

${glslConsts}

const vec3 KEY = vec3(-0.48, 0.62, 0.62);

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

vec3 warp(vec3 q, float ph, float amp) {
  for (int i = 1; i <= ${OCTAVES}; i++) {
    float fi = float(i);
    q += (amp / pow(fi, WARP_FALLOFF)) * sin(q.yzx * (1.17 * fi) + ph * vec3(0.71, -0.53, 0.43) * (0.8 + 0.35 * fi) + vec3(1.7, 4.1, 2.9) * fi + uSeed);
  }
  return q;
}

mat3 spin(float a) {
  vec3 k = normalize(vec3(0.25, 1.0, 0.18));
  float c = cos(a), s = sin(a), t = 1.0 - c;
  return mat3(
    c + k.x*k.x*t,       k.y*k.x*t + k.z*s,  k.z*k.x*t - k.y*s,
    k.x*k.y*t - k.z*s,   c + k.y*k.y*t,      k.z*k.y*t + k.x*s,
    k.x*k.z*t + k.y*s,   k.y*k.z*t - k.x*s,  c + k.z*k.z*t
  );
}

vec3 toSrgb(vec3 c) {
  c = clamp(c, 0.0, 1.0);
  return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

vec3 ramp(float t) {
  vec3 c = mix(uDeep, uMid, smoothstep(RAMP_MID_FROM, RAMP_MID_TO, t));
  return mix(c, uLight, smoothstep(RAMP_LIGHT_FROM, 1.0, t));
}

void main() {
  float breathe = 1.0 + uPulse * PULSE_SCALE * sin(uTime * 6.2831853 / PULSE_PERIOD);
  vec2 p = (gl_FragCoord.xy - 0.5 * uRes) / (0.5 * uRes.y) * EXTENT / breathe;
  float px = 2.0 * EXTENT / uRes.y / breathe;
  float r = length(p);
  float energy = uEnergy * (1.0 + 0.25 * uPulse * sin(uTime * 6.2831853 / PULSE_PERIOD));

  vec3 col = vec3(0.0);
  float a = 0.0;

  if (r < 1.0 + 2.0 * px) {
    float nz = sqrt(max(1.0 - r * r, 0.0));
    vec3 n = vec3(p, nz);
    vec2 dir = p / max(r, 1e-4);
    mat3 R = spin(uSpin);
    float scale = FLOW_SCALE * mix(SMALL_SCALE_MUL, 1.0, uDetail);
    float amp = WARP * (1.0 + WARP_TURB * uTurb);

    vec3 qf = warp(R * n * scale, uPhase, amp);
    float F = (qf.x + qf.y * 0.7 - qf.z * 0.4) * SILK_BANDS;
    float sf = 0.5 + 0.5 * sin(F);
    float sh = sin(F * 0.5);
    float crease = pow(max(1.0 - sqrt(sh * sh + 0.002), 0.0), SILK_SHARPNESS);
    float scatter = pow(1.0 - abs(sh), 2.5);
    float silk = mix(pow(sf, 5.0), crease, SILK_CREASE) + SILK_SCATTER * scatter;

    vec3 pd = vec3(p, nz * (1.0 - DEEP_DEPTH));
    vec3 qd = warp(R * pd * scale * 0.8 + vec3(3.1, -1.7, 0.6), uPhase * 0.7 + 11.0, amp * 0.8);
    float deep = 0.5 + 0.5 * sin((qd.x * 0.9 - qd.y * 0.6 + qd.z * 0.5) * DEEP_BANDS);

    vec3 key = normalize(KEY);
    float lambert = 0.5 + 0.5 * dot(n, key);
    float body = mix(VOLUME_SHADOW, 1.0, lambert);
    float thick = mix(THICK_MIN, 1.0, nz);
    vec2 cp = p - vec2(-0.08, 0.1);
    float core = exp(-dot(cp, cp) * 2.6) * (CORE_STRENGTH + CORE_ENERGY * energy);
    float emit = (SILK_AMOUNT * silk + DEEP_AMOUNT * deep) * thick * (0.55 + 0.45 * lambert) + core;
    float tone = BODY_BASE * body + emit;
    tone *= 1.0 + ENERGY_GAIN * energy;
    col = ramp(clamp(tone, 0.0, 1.0)) * tone * LIGHT_GAIN;

    float fres = pow(1.0 - nz, RIM_POWER);
    col += uLight * fres * RIM_STRENGTH * (0.5 + 0.5 * lambert);
    float edge = smoothstep(0.7, 0.93, r) * smoothstep(1.0, 0.95, r);
    float arcDir = max(dot(dir, normalize(vec2(-0.62, 0.78))), 0.0);
    col += vec3(1.0) * edge * pow(arcDir, 6.0) * ARC_STRENGTH;
    float causticDir = max(dot(dir, normalize(vec2(0.55, -0.83))), 0.0);
    float causticBand = smoothstep(0.5, 0.92, r) * smoothstep(1.0, 0.9, r);
    col += uLight * causticBand * pow(causticDir, 2.5) * CAUSTIC_STRENGTH * (0.6 + 0.6 * emit);
    vec3 h = normalize(key + vec3(0.0, 0.0, 1.0));
    float ndh = max(dot(n, h), 0.0);
    col += vec3(1.0) * pow(ndh, SPEC_POWER * mix(0.35, 1.0, uDetail)) * SPEC_STRENGTH;

    col = 1.0 - exp(-col * EXPOSURE);
    a = smoothstep(1.0 + px, 1.0 - px, r);
  }

  float d = max(r - 1.0, 0.0);
  float halo = (HALO_TIGHT * exp(-d * HALO_TIGHT_FALLOFF) + HALO_WIDE * exp(-d * HALO_WIDE_FALLOFF)) * (1.0 + HALO_ENERGY * energy);
  halo *= smoothstep(EXTENT, EXTENT - 0.4, r);
  halo = clamp(halo, 0.0, 1.0);

  vec3 cs = toSrgb(col);
  vec3 gs = toSrgb(uGlow);
  float outA = a + halo * (1.0 - a);
  vec3 outC = cs * a + gs * halo * (1.0 - a);
  float dither = (hash(gl_FragCoord.xy) + hash(gl_FragCoord.yx + 17.0) - 1.0) / 255.0 * step(0.004, outA);
  outA = clamp(outA + dither, 0.0, 1.0);
  outC = clamp(outC + dither, 0.0, outA);
  gl_FragColor = vec4(outC, outA);
}
`;
