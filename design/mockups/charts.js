// Chart geometry for the mock-ups. The rules here are the ones design/system.md specifies for
// android/ui-kit: workout profile, power trace, zone bars, line charts. Synthetic data only.

const SVG = "http://www.w3.org/2000/svg";
const ZONE_MAX = [55, 75, 90, 105, 120, 150, Number.POSITIVE_INFINITY];

export const zoneOf = (pct) => ZONE_MAX.findIndex((max) => Math.round(pct) <= max) + 1;
const zoneVar = (z) => `var(--zone-${z})`;

const el = (name, attrs, parent) => {
  const node = document.createElementNS(SVG, name);
  for (const [k, v] of Object.entries(attrs)) {
    // Presentation attributes do not resolve var(); custom properties go through style.
    if (String(v).includes("var(")) node.style.setProperty(k, String(v));
    else node.setAttribute(k, String(v));
  }
  parent?.appendChild(node);
  return node;
};

// The spec §8.1 example workout: Sweet spot 3x10.
export const SWEET_SPOT = {
  name: "Sweet spot 3×10",
  steps: [
    { type: "ramp", duration: 600, from: 0.45, to: 0.7, label: "Warm-up" },
    {
      type: "repeat",
      count: 3,
      steps: [
        { type: "steady", duration: 600, target: 0.88, label: "Sweet spot" },
        { type: "steady", duration: 300, target: 0.5, label: "Recover" },
      ],
    },
    { type: "free", duration: 300, label: "Free ride" },
    { type: "ramp", duration: 300, from: 0.55, to: 0.4, label: "Cool-down" },
  ],
};

export const VO2 = {
  name: "VO2 5×3",
  steps: [
    { type: "ramp", duration: 600, from: 0.5, to: 0.75, label: "Warm-up" },
    { type: "steady", duration: 300, target: 0.95, label: "Opener" },
    { type: "steady", duration: 180, target: 0.55, label: "Easy" },
    {
      type: "repeat",
      count: 5,
      steps: [
        { type: "steady", duration: 180, target: 1.15, label: "VO2" },
        { type: "steady", duration: 180, target: 0.5, label: "Recover" },
      ],
    },
    { type: "steady", duration: 60, target: 1.6, label: "Sprint" },
    { type: "ramp", duration: 420, from: 0.6, to: 0.4, label: "Cool-down" },
  ],
};

/** Flattens repeats into segments with absolute start times (seconds). */
export const flatten = (steps, out = [], t0 = { t: 0 }) => {
  for (const s of steps) {
    if (s.type === "repeat") {
      for (let i = 0; i < s.count; i++) flatten(s.steps, out, t0);
      continue;
    }
    const from = s.type === "ramp" ? s.from : s.type === "steady" ? s.target : null;
    const to = s.type === "ramp" ? s.to : from;
    out.push({ start: t0.t, duration: s.duration, from, to, type: s.type, label: s.label });
    t0.t += s.duration;
  }
  return out;
};

/** Splits a ramp at zone boundaries so every piece has exactly one zone colour. */
const zonePieces = (seg) => {
  const a = seg.from * 100;
  const b = seg.to * 100;
  const cuts = [0, 1];
  for (const edge of ZONE_MAX.slice(0, 6)) {
    const e = edge + 0.5;
    if ((a - e) * (b - e) < 0) cuts.push((e - a) / (b - a));
  }
  cuts.sort((x, y) => x - y);
  const pieces = [];
  for (let i = 0; i + 1 < cuts.length; i++) {
    const [u, v] = [cuts[i], cuts[i + 1]];
    const mid = a + (b - a) * ((u + v) / 2);
    pieces.push({ u, v, zone: zoneOf(mid) });
  }
  return pieces;
};

/**
 * Workout profile. x = time, whole workout fits the width; y = % FTP from 0 to yMax.
 * opts: { width, height, now (s), yMax (fraction), ftpLine, gap, ticks (minutes), power: [{t, w}], ftp }
 */
export const profile = (svg, workout, opts) => {
  const { width, height, now = null, yMax = 1.5, ftpLine = false, gap = 1, ticks = 0 } = opts;
  const base = opts.surface ?? "var(--color-surface1)";
  const axisH = ticks ? 20 : 0;
  const plotH = height - axisH;
  svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
  svg.setAttribute("width", width);
  svg.setAttribute("height", height);
  const segs = flatten(workout.steps);
  const total = segs.at(-1).start + segs.at(-1).duration;
  const x = (t) => (t / total) * width;
  const y = (f) => plotH - (Math.min(f, yMax) / yMax) * plotH;

  if (ftpLine) {
    el("line", { x1: 0, x2: width, y1: y(1) + 0.5, y2: y(1) + 0.5, class: "grid" }, svg);
  }
  const marks = el("g", {}, svg);
  for (const s of segs) {
    const x0 = x(s.start);
    const x1 = Math.max(x0 + 1, x(s.start + s.duration) - gap);
    const past = now !== null && s.start + s.duration <= now;
    const partial = now !== null && s.start < now && now < s.start + s.duration;
    // Elapsed parts are pre-blended with the surface behind them (opaque), never drawn with
    // alpha: on the translucent overlay alpha would let the video show through.
    const tone = (color, isPast) =>
      isPast ? `color-mix(in srgb, ${color} calc(var(--zone-past-alpha) * 100%), ${base})` : color;
    const draw = (isPast, clipFrom, clipTo) => {
      const g = el("g", {}, marks);
      if (s.type === "free") {
        const rx0 = Math.max(x0, clipFrom);
        const rx1 = Math.min(x1, clipTo);
        if (rx1 > rx0) {
          el(
            "rect",
            {
              x: rx0,
              y: y(0.5),
              width: rx1 - rx0,
              height: plotH - y(0.5),
              fill: tone("var(--color-surface3)", isPast),
            },
            g,
          );
        }
        return;
      }
      for (const p of zonePieces(s)) {
        const px0 = Math.max(x0 + (x1 - x0) * p.u, clipFrom);
        const px1 = Math.min(x0 + (x1 - x0) * p.v, clipTo);
        if (px1 <= px0) continue;
        const fAt = (px) => s.from + (s.to - s.from) * ((px - x0) / (x1 - x0));
        const pts = [
          [px0, plotH],
          [px0, y(fAt(px0))],
          [px1, y(fAt(px1))],
          [px1, plotH],
        ];
        el(
          "polygon",
          { points: pts.map((q) => q.join(",")).join(" "), fill: tone(zoneVar(p.zone), isPast) },
          g,
        );
      }
    };
    if (partial) {
      draw(true, Number.NEGATIVE_INFINITY, x(now));
      draw(false, x(now), Number.POSITIVE_INFINITY);
    } else {
      draw(past, Number.NEGATIVE_INFINITY, Number.POSITIVE_INFINITY);
    }
  }
  if (opts.power && opts.ftp) {
    const pts = opts.power.map((p) => `${x(p.t).toFixed(1)},${y(p.w / opts.ftp).toFixed(1)}`);
    el(
      "polyline",
      {
        points: pts.join(" "),
        fill: "none",
        stroke: "var(--color-text-primary)",
        "stroke-width": 1.5,
        "stroke-linejoin": "round",
      },
      svg,
    );
  }
  if (ftpLine) {
    const t = el("text", { x: width - 2, y: y(1) - 4, "text-anchor": "end", class: "axis" }, svg);
    t.textContent = "FTP";
  }
  if (now !== null) {
    el(
      "rect",
      { x: x(now) - 1, y: 0, width: 2, height: plotH, fill: "var(--color-text-primary)" },
      svg,
    );
  }
  if (ticks) {
    for (let m = 0; m * 60 <= total; m += ticks) {
      const tx = x(m * 60);
      const anchor = m === 0 ? "start" : tx > width - 20 ? "end" : "middle";
      const t = el("text", { x: tx, y: height - 4, "text-anchor": anchor, class: "axis" }, svg);
      t.textContent = m === 0 ? "0" : `${m}`;
    }
  }
  return { total, segs };
};

/** Deterministic pseudo-random for synthetic traces. */
export const rng = (seed) => {
  let s = seed;
  return () => {
    s = (s * 1664525 + 1013904223) % 4294967296;
    return s / 4294967296;
  };
};

/** Synthetic power trace that follows the workout up to `now`, sampled every `step` s. */
export const tracePower = (workout, ftp, now, step = 15, seed = 3) => {
  const r = rng(seed);
  const segs = flatten(workout.steps);
  const out = [];
  for (let t = 0; t <= now; t += step) {
    const s = segs.find((g) => t >= g.start && t < g.start + g.duration);
    const f = s.from === null ? 0.62 : s.from + (s.to - s.from) * ((t - s.start) / s.duration);
    out.push({ t, w: ftp * f + (r() - 0.5) * 18 });
  }
  return out;
};

/**
 * Minimal line chart. series: [{ values:[number], stroke, width, label }]
 * opts: { width, height, yMin, yMax, yTicks:[n], xLabels:[{i,text}], pad:{l,r,t,b}, zeroLine }
 */
export const lines = (svg, series, opts) => {
  const { width, height, yMin, yMax, yTicks = [], xLabels = [] } = opts;
  const pad = { l: 32, r: 72, t: 8, b: 24, ...opts.pad };
  svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
  svg.setAttribute("width", width);
  svg.setAttribute("height", height);
  const n = series[0].values.length;
  const x = (i) => pad.l + (i / (n - 1)) * (width - pad.l - pad.r);
  const y = (v) => pad.t + (1 - (v - yMin) / (yMax - yMin)) * (height - pad.t - pad.b);
  for (const v of yTicks) {
    const yy = Math.round(y(v)) + 0.5;
    el("line", { x1: pad.l, x2: width - pad.r, y1: yy, y2: yy, class: "grid" }, svg);
    const t = el("text", { x: pad.l - 8, y: yy + 4, "text-anchor": "end", class: "axis" }, svg);
    t.textContent = String(v).replace("-", "−");
  }
  for (const xl of xLabels) {
    const t = el(
      "text",
      { x: x(xl.i), y: height - 6, "text-anchor": xl.anchor ?? "middle", class: "axis" },
      svg,
    );
    t.textContent = xl.text;
  }
  for (const s of series) {
    const d = s.values
      .map((v, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`)
      .join("");
    el(
      "path",
      {
        d,
        fill: "none",
        stroke: s.stroke,
        "stroke-width": s.width ?? 2,
        "stroke-linejoin": "round",
        "stroke-linecap": "round",
      },
      svg,
    );
    const lx = x(n - 1);
    const ly = y(s.values.at(-1));
    el(
      "circle",
      { cx: lx, cy: ly, r: 4, fill: s.stroke, stroke: "var(--color-bg)", "stroke-width": 2 },
      svg,
    );
    if (s.label) {
      const t = el(
        "text",
        { x: lx + 10, y: ly + 4 + (s.labelDy ?? 0), class: "axis axis-strong" },
        svg,
      );
      t.textContent = s.label;
    }
  }
  return { x, y };
};

export const el$ = el;
