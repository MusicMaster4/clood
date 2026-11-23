import { useEffect, useRef, useState, type CSSProperties } from "react";
import { attachOrb, type OrbHandle, type OrbState } from "./orb-engine";
import { GLOW_OVERFLOW } from "./orb-shader";

export type { OrbState };

export type OrbProps = {
  state?: OrbState;
  color?: string;
  level?: number;
  previewTint?: boolean;
  size?: number;
  className?: string;
  style?: CSSProperties;
  label?: string;
};

export function Orb({
  state = "idle",
  color = "#7c8cff",
  level = 0,
  previewTint = false,
  size,
  className,
  style,
  label,
}: OrbProps) {
  const rootRef = useRef<HTMLSpanElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const handle = useRef<OrbHandle | null>(null);
  const latest = useRef({ state, color, level, previewTint });
  latest.current = { state, color, level, previewTint };
  const [gl, setGl] = useState(false);

  useEffect(() => {
    const el = rootRef.current;
    const canvas = canvasRef.current;
    if (!el || !canvas) return;
    const h = attachOrb(el, canvas, latest.current, {
      onReady: () => setGl(true),
      onLost: () => setGl(false),
    });
    handle.current = h;
    return () => {
      h.detach();
      handle.current = null;
      setGl(false);
    };
  }, []);

  useEffect(() => {
    handle.current?.set({ state, color, level, previewTint });
  }, [state, color, level, previewTint]);

  const overflow = `${-GLOW_OVERFLOW * 100}%`;
  const extent = `${(1 + 2 * GLOW_OVERFLOW) * 100}%`;

  return (
    <span
      ref={rootRef}
      className={"orb" + (className ? " " + className : "")}
      data-state={state}
      data-gl={gl ? "on" : "off"}
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      style={{
        ...(size ? { width: size, height: size } : null),
        ["--orb-tint" as string]: color,
        ...style,
      }}
    >
      <span className="orb-css" />
      <canvas
        ref={canvasRef}
        className="orb-gl"
        style={{ left: overflow, top: overflow, width: extent, height: extent }}
      />
    </span>
  );
}
