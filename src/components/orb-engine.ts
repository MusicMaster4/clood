// Shared WebGL orb renderer with critically damped springs and audio-reactive acceleration.

import {
  COLOR_OMEGA,
  EASE_OMEGA,
  EXTENT,
  FRAGMENT_SHADER,
  MAX_BACKING,
  MAX_DPR,
  PULSE_PERIOD,
  STATES,
  TEMPO_LARGE,
  TEMPO_LARGE_PX,
  VERTEX_SHADER,
} from "./orb-shader";
import { hexToTones, labToLinear, mixLab, PEARL_TONES, type Lab } from "./orb-color";

export type OrbState = "idle" | "ringing" | "speaking";

export type OrbTarget = {
  state: OrbState;
  color: string;
  /** 0..1 audio amplitude from agent voice */
  level: number;
  /** Always keep color visible (e.g. settings preview) vs relax toward neutral when silent */
  previewTint?: boolean;
};

export type OrbHandle = { set(t: OrbTarget): void; detach(): void };

type Spring = { x: number; v: number };
type Key = "speed" | "spin" | "turb" | "energy" | "pulse" | "tint";

type Inst = {
  el: HTMLElement;
  canvas: HTMLCanvasElement;
  ctx: CanvasRenderingContext2D | null;
  cssSize: number;
  w: number;
  h: number;
  visible: boolean;
  target: OrbTarget;
  tones: [Lab, Lab, Lab, Lab];
  targetTones: [Lab, Lab, Lab, Lab];
  s: Record<Key, Spring>;
  phase: number;
  spinA: number;
  time: number;
  seed: number;
  drawn: boolean;
  dirty: boolean;
  onReady: () => void;
  onLost: () => void;
};

const insts = new Set<Inst>();
const byEl = new Map<Element, Inst>();

type U = Record<
  "uRes" | "uPhase" | "uSpin" | "uTime" | "uTurb" | "uEnergy" | "uPulse" | "uDetail" | "uSeed" | "uDeep" | "uMid" | "uLight" | "uGlow",
  WebGLUniformLocation | null
>;

let glCanvas: HTMLCanvasElement | null = null;
let gl: WebGLRenderingContext | null = null;
let uni: U | null = null;
let glLost = false;
let glFailed = false;
let releaseTimer: ReturnType<typeof setTimeout> | null = null;

function compile(g: WebGLRenderingContext): boolean {
  const sh = (type: number, src: string) => {
    const s = g.createShader(type)!;
    g.shaderSource(s, src);
    g.compileShader(s);
    if (!g.getShaderParameter(s, g.COMPILE_STATUS) && !g.isContextLost()) {
      console.error("orb shader:", g.getShaderInfoLog(s));
      return null;
    }
    return s;
  };
  const vs = sh(g.VERTEX_SHADER, VERTEX_SHADER);
  const fs = sh(g.FRAGMENT_SHADER, FRAGMENT_SHADER);
  if (!vs || !fs) return false;
  const prog = g.createProgram()!;
  g.attachShader(prog, vs);
  g.attachShader(prog, fs);
  g.bindAttribLocation(prog, 0, "aPos");
  g.linkProgram(prog);
  if (!g.getProgramParameter(prog, g.LINK_STATUS) && !g.isContextLost()) {
    console.error("orb program:", g.getProgramInfoLog(prog));
    return false;
  }
  g.useProgram(prog);
  const buf = g.createBuffer();
  g.bindBuffer(g.ARRAY_BUFFER, buf);
  g.bufferData(g.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), g.STATIC_DRAW);
  g.enableVertexAttribArray(0);
  g.vertexAttribPointer(0, 2, g.FLOAT, false, 0, 0);
  const names = [
    "uRes", "uPhase", "uSpin", "uTime", "uTurb", "uEnergy",
    "uPulse", "uDetail", "uSeed", "uDeep", "uMid", "uLight", "uGlow",
  ] as const;
  uni = Object.fromEntries(names.map((n) => [n, g.getUniformLocation(prog, n)])) as U;
  g.disable(g.BLEND);
  g.clearColor(0, 0, 0, 0);
  return true;
}

function onContextLost(e: Event) {
  e.preventDefault();
  glLost = true;
  for (const i of insts) {
    i.drawn = false;
    i.onLost();
  }
}

function onContextRestored() {
  if (!gl) return;
  glLost = false;
  if (!compile(gl)) {
    glFailed = true;
    return;
  }
  for (const i of insts) i.dirty = true;
  schedule();
}

function ensureGl(): WebGLRenderingContext | null {
  if (glFailed) return null;
  if (gl) return glLost ? null : gl;
  try {
    glCanvas = document.createElement("canvas");
    glCanvas.width = glCanvas.height = 256;
    const g = glCanvas.getContext("webgl", {
      alpha: true,
      premultipliedAlpha: true,
      antialias: false,
      depth: false,
      stencil: false,
      preserveDrawingBuffer: false,
      powerPreference: "low-power",
    });
    if (!g || !compile(g)) {
      glFailed = true;
      glCanvas = null;
      return null;
    }
    gl = g;
    glCanvas.addEventListener("webglcontextlost", onContextLost);
    glCanvas.addEventListener("webglcontextrestored", onContextRestored);
    return gl;
  } catch {
    glFailed = true;
    return null;
  }
}

function releaseGl() {
  if (!gl || !glCanvas) return;
  glCanvas.removeEventListener("webglcontextlost", onContextLost);
  glCanvas.removeEventListener("webglcontextrestored", onContextRestored);
  gl.getExtension("WEBGL_lose_context")?.loseContext();
  glCanvas.width = glCanvas.height = 1;
  gl = null;
  glCanvas = null;
  uni = null;
  glLost = false;
}

let reduced = false;
let mq: MediaQueryList | null = null;

function spring(s: Spring, target: number, omega: number, dt: number): boolean {
  const n = Math.max(1, Math.ceil(dt * 120));
  const h = dt / n;
  for (let k = 0; k < n; k++) {
    const a = omega * omega * (target - s.x) - 2 * omega * s.v;
    s.v += a * h;
    s.x += s.v * h;
  }
  if (Math.abs(target - s.x) < 1e-4 && Math.abs(s.v) < 1e-4) {
    s.x = target;
    s.v = 0;
    return false;
  }
  return true;
}

function targets(i: Inst): Record<Key, number> {
  const base = STATES[i.target.state] ?? STATES.idle;
  const lvl = Math.min(1, Math.max(0, i.target.level || 0));
  const speaking = i.target.state === "speaking";
  const ringing = i.target.state === "ringing";
  const tint = speaking || ringing ? 1.0 : i.target.previewTint ? 0.85 : 0.72;
  return {
    speed: base.speed + (speaking ? lvl * 0.85 : 0),
    spin: base.spin + (speaking ? lvl * 0.7 : 0),
    turb: Math.min(1.5, base.turb + (speaking ? lvl * 0.5 : 0)),
    energy: Math.min(1.4, base.energy + (speaking ? lvl * 0.45 : 0)),
    pulse: base.pulse,
    tint,
  };
}

function step(i: Inst, dt: number): boolean {
  const t = targets(i);
  let moving = false;
  for (const k of Object.keys(i.s) as Key[]) {
    const omega = k === "tint" ? COLOR_OMEGA : EASE_OMEGA;
    if (spring(i.s[k], t[k], omega, dt)) moving = true;
  }
  const colorAlpha = 1 - Math.exp(-COLOR_OMEGA * dt);
  for (let k = 0; k < 4; k++) {
    i.tones[k] = mixLab(i.tones[k], i.targetTones[k], colorAlpha);
  }
  if (reduced) return moving;
  const big = Math.min(1, Math.max(0, (i.cssSize - 120) / (TEMPO_LARGE_PX - 120)));
  const tempo = 1 + (TEMPO_LARGE - 1) * big * big * (3 - 2 * big);
  i.phase += i.s.speed.x * tempo * dt;
  i.spinA = (i.spinA + i.s.spin.x * tempo * dt) % (Math.PI * 2);
  i.time = (i.time + dt) % PULSE_PERIOD;
  return true;
}

function snap(i: Inst) {
  const t = targets(i);
  for (const k of Object.keys(i.s) as Key[]) {
    i.s[k].x = t[k];
    i.s[k].v = 0;
  }
  i.tones = i.targetTones.map((lab) => [...lab] as Lab) as [Lab, Lab, Lab, Lab];
}

function render(i: Inst): boolean {
  const g = ensureGl();
  if (!g || !uni || !glCanvas || !i.ctx || i.w === 0) return false;
  if (glCanvas.width < i.w || glCanvas.height < i.h) {
    const side = Math.ceil(Math.max(i.w, i.h, glCanvas.width) / 128) * 128;
    glCanvas.width = glCanvas.height = side;
  }
  g.viewport(0, 0, i.w, i.h);
  g.uniform2f(uni.uRes, i.w, i.h);
  g.uniform1f(uni.uPhase, i.phase);
  g.uniform1f(uni.uSpin, i.spinA);
  g.uniform1f(uni.uTime, i.time);
  g.uniform1f(uni.uTurb, i.s.turb.x);
  g.uniform1f(uni.uEnergy, i.s.energy.x);
  g.uniform1f(uni.uPulse, reduced ? 0 : i.s.pulse.x);
  g.uniform1f(uni.uDetail, Math.min(1, Math.max(0, (i.cssSize - 24) / 136)));
  g.uniform1f(uni.uSeed, i.seed);
  const tint = Math.min(1, Math.max(0, i.s.tint.x));
  const locs = [uni.uDeep, uni.uMid, uni.uLight, uni.uGlow];
  for (let k = 0; k < 4; k++) {
    const c = labToLinear(mixLab(PEARL_TONES[k], i.tones[k], tint));
    g.uniform3f(locs[k], Math.max(0, c[0]), Math.max(0, c[1]), Math.max(0, c[2]));
  }
  g.drawArrays(g.TRIANGLES, 0, 3);
  i.ctx.clearRect(0, 0, i.w, i.h);
  i.ctx.drawImage(glCanvas, 0, glCanvas.height - i.h, i.w, i.h, 0, 0, i.w, i.h);
  if (!i.drawn) {
    i.drawn = true;
    i.onReady();
  }
  return true;
}

let raf = 0;
let last = 0;

function schedule() {
  if (raf || glFailed || typeof document === "undefined" || document.hidden) return;
  raf = requestAnimationFrame(frame);
}

function frame(now: number) {
  raf = 0;
  const dt = last ? Math.min((now - last) / 1000, 0.1) : 1 / 60;
  last = now;
  let again = false;
  for (const i of insts) {
    if (!i.visible) continue;
    measure(i, false);
    if (i.w === 0) continue;
    const active = step(i, dt);
    if ((active || i.dirty || !i.drawn) && !glLost) {
      if (render(i)) i.dirty = false;
    }
    if (active) again = true;
  }
  if (again) schedule();
  else last = 0;
}

function onVisibility() {
  last = 0;
  if (!document.hidden) schedule();
}

let ro: ResizeObserver | null = null;
let io: IntersectionObserver | null = null;

function measure(i: Inst, fromObserver = true) {
  const r = i.el.getBoundingClientRect();
  const cssSize = Math.min(r.width, r.height);
  if (!(cssSize > 0)) return;
  const dpr = Math.min(window.devicePixelRatio || 1, MAX_DPR);
  const side = Math.max(16, Math.min(MAX_BACKING, Math.ceil((cssSize * EXTENT * dpr) / 16) * 16));
  i.cssSize = cssSize;
  if (side !== i.w) {
    i.w = i.h = side;
    i.canvas.width = i.canvas.height = side;
    i.dirty = true;
    if (fromObserver && i.visible && !glLost) render(i);
  }
  if (fromObserver) schedule();
}

function observers() {
  if (!ro) {
    ro = new ResizeObserver((entries) => {
      for (const e of entries) {
        const i = byEl.get(e.target);
        if (i) measure(i);
      }
    });
  }
  if (!io) {
    io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          const i = byEl.get(e.target);
          if (i) i.visible = e.isIntersecting;
        }
        schedule();
      },
      { rootMargin: "80px" },
    );
  }
  if (!mq) {
    mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    reduced = mq.matches;
    mq.addEventListener("change", () => {
      reduced = !!mq?.matches;
      for (const i of insts) i.dirty = true;
      schedule();
    });
    document.addEventListener("visibilitychange", onVisibility);
  }
}

export function attachOrb(
  el: HTMLElement,
  canvas: HTMLCanvasElement,
  initial: OrbTarget,
  cb: { onReady: () => void; onLost: () => void },
): OrbHandle {
  if (releaseTimer) {
    clearTimeout(releaseTimer);
    releaseTimer = null;
  }
  observers();
  const sp = (): Spring => ({ x: 0, v: 0 });
  const initialTones = hexToTones(initial.color);
  const i: Inst = {
    el,
    canvas,
    ctx: null,
    cssSize: 0,
    w: 0,
    h: 0,
    visible: false,
    target: initial,
    tones: initialTones,
    targetTones: initialTones,
    s: { speed: sp(), spin: sp(), turb: sp(), energy: sp(), pulse: sp(), tint: sp() },
    phase: Math.random() * 40,
    spinA: Math.random() * Math.PI * 2,
    time: 0,
    seed: Math.random() * 6.28,
    drawn: false,
    dirty: true,
    onReady: cb.onReady,
    onLost: cb.onLost,
  };
  snap(i);
  ensureGl();
  i.ctx = canvas.getContext("2d");
  insts.add(i);
  byEl.set(el, i);
  ro!.observe(el);
  io!.observe(el);
  return {
    set(t) {
      if (t.color !== i.target.color) {
        i.targetTones = hexToTones(t.color);
      }
      i.target = { ...t };
      i.dirty = true;
      schedule();
    },
    detach() {
      insts.delete(i);
      byEl.delete(el);
      ro?.unobserve(el);
      io?.unobserve(el);
      if (insts.size === 0) {
        if (releaseTimer) clearTimeout(releaseTimer);
        releaseTimer = setTimeout(() => {
          releaseTimer = null;
          if (insts.size === 0) releaseGl();
        }, 3000);
      }
    },
  };
}
