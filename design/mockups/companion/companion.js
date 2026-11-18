// Companion mock-up helpers: the phone shell, the headers, and the chart geometry that
// design/companion.md specifies for android/ui-kit. Every number is synthetic.

import { flatten, rng, SWEET_SPOT, VO2, zoneOf } from "../charts.js";

export { flatten, rng, SWEET_SPOT, VO2, zoneOf };

export const FTP = 250;
export const TODAY = new Date(Date.UTC(2026, 10, 18)); // Wednesday 18 November 2026

// ---------- Formatting (design/system.md §3) ----------
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const DOW = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
export const minus = (v) => String(v).replace("-", "−");
export const signed = (v) => (v > 0 ? `+${v}` : minus(v));
export const dayOf = (offset) => new Date(TODAY.getTime() - offset * 86400000);
export const fmtDay = (d) => `${DOW[d.getUTCDay()]} ${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
export const fmtShort = (d) => `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
export const monthName = (d) => MONTHS[d.getUTCMonth()];
export const hm = (h) => {
  const t = Math.round(h * 60);
  return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, "0")}`;
};
export const clock = (mins) => {
  const m = ((Math.round(mins) % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
};
export const dur = (s) => {
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(Math.round(s % 60)).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
};

// ---------- Shell ----------
export const ic = (name, cls = "") =>
  `<span class="i ${cls}" style="--src:url(../../icons/${name}.svg)"></span>`;

const NAV = [
  ["house", "Today"],
  ["activity", "Fitness"],
  ["zap", "Power"],
  ["moon", "Wellness"],
  ["list", "Rides"],
];
export const nav = (active) =>
  `<div class="nav">${NAV.map(
    ([i, l]) => `<span class="item${l === active ? " on" : ""}">${ic(i, "i-24")}${l}</span>`,
  ).join("")}</div>`;

/** Fills <div class="frame phone" id=…> with status bar, body and (optionally) the bottom nav. */
export const shell = (id, { tab = null, time = "07:42", body, top = "", overlay = "" }) => {
  const node = document.getElementById(id);
  node.innerHTML = `<div class="status"><span class="num">${time}</span><span class="num">82%</span></div>${top}<div class="screen">${body}</div>${tab ? nav(tab) : ""}${overlay}`;
  return node;
};

export const head = (title, sub = "", { settings = false } = {}) =>
  `<div class="head"><div class="grow"><div class="t-title">${title}</div>${sub ? `<div class="t-label c2 fresh num">${sub}</div>` : ""}</div>${
    settings ? `<span class="iconbtn">${ic("settings")}</span>` : ""
  }</div>`;

export const backbar = (title, sub = "") =>
  `<div class="backbar"><span class="iconbtn">${ic("chevron-left")}</span><div class="t"><span class="t-heading">${title}</span>${sub ? `<span class="t-label c2 num">${sub}</span>` : ""}</div></div>`;

/** The problem line under a header: stale/offline/refused. */
export const statusLine = (title, text, action = "") =>
  `<div class="statusline">${ic("triangle-alert")}<div class="txt"><span class="t-strong num">${title}</span><span class="t-label c2">${text}</span></div>${action}</div>`;

export const range = (opts, on) =>
  `<div class="range">${opts.map((o) => `<span class="${o === on ? "on" : ""}">${o}</span>`).join("")}</div>`;

export const tile = (label, value, unit = "", sub = "", extra = "") =>
  `<div class="tile" style="padding:12px 14px"><div class="lbl"><span>${label}</span>${extra}</div><div class="num t-num-s">${value}${unit ? `<span class="unit">${unit}</span>` : ""}</div>${sub ? `<div class="sub num">${sub}</div>` : ""}</div>`;

/** Readout row: the selected x and each series' value, keyed by a line/dot swatch. */
export const readout = (when, keys, { notToday = false } = {}) =>
  `<div class="readout"><span class="when">${when}</span>${keys
    .map(
      ([kind, color, name, value]) =>
        `<span class="k">${kind ? `<i class="key-${kind}" style="background:${color}"></i>` : ""}${name}${value !== undefined ? ` <b>${value}</b>` : ""}</span>`,
    )
    .join("")}${notToday ? `<span class="today">Today</span>` : ""}</div>`;

// ---------- Scales ----------
export const niceTicks = (min, max, count = 4) => {
  const raw = (max - min) / count;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw);
  const lo = Math.floor(min / step) * step;
  const hi = Math.ceil(max / step) * step;
  const ticks = [];
  for (let v = lo; v <= hi + 1e-9; v += step) ticks.push(Math.round(v * 100) / 100);
  return { min: lo, max: hi, ticks, step };
};

const S = (prop, v) => `${prop}:${v}`;
const fill = (v) => `style="${S("fill", v)}"`;

// ---------- Line chart ----------
/**
 * o: { w, h, n, xs?:[0..1], padL=32, padR=6, padT=10,
 *      y:{min,max,ticks,fmt?, base?}, xTicks:[{i,text,anchor}],
 *      bands:[{lo,hi,fill,label?}] (numbers or per-index arrays),
 *      refs:[{v,label}], series:[{v:[num|null], color, sw=2, step?, area?, dots?:"outside"|"all", r?}],
 *      sel: index | null, selSeries?: which series get a dot at sel }
 */
export const lineChart = (o) => {
  const W = o.w;
  const H = o.h;
  const padL = o.padL ?? 32;
  const padR = o.padR ?? 6;
  const padT = o.padT ?? 10;
  const axisH = o.xTicks?.length ? 20 : (o.padB ?? 4);
  const plotB = H - axisH;
  const n = o.n;
  const x = o.xs
    ? (i) => padL + o.xs[i] * (W - padL - padR)
    : (i) => padL + (n === 1 ? 0.5 : i / (n - 1)) * (W - padL - padR);
  const y = (v) => padT + (1 - (v - o.y.min) / (o.y.max - o.y.min)) * (plotB - padT);
  const fmt = o.y.fmt ?? ((v) => minus(v));
  const out = [];

  for (const b of o.bands ?? []) {
    const lo = (i) => (Array.isArray(b.lo) ? b.lo[i] : b.lo);
    const hi = (i) => (Array.isArray(b.hi) ? b.hi[i] : b.hi);
    const top = [];
    const bot = [];
    for (let i = 0; i < n; i++) {
      if (hi(i) == null) continue;
      top.push(`${x(i).toFixed(1)},${y(hi(i)).toFixed(1)}`);
      bot.unshift(`${x(i).toFixed(1)},${y(lo(i)).toFixed(1)}`);
    }
    if (!Array.isArray(b.lo)) {
      out.push(
        `<rect x="${padL}" y="${y(b.hi)}" width="${W - padL - padR}" height="${y(b.lo) - y(b.hi)}" ${fill(b.fill)}/>`,
      );
    } else {
      out.push(`<polygon points="${top.concat(bot).join(" ")}" ${fill(b.fill)}/>`);
    }
    if (b.label) {
      out.push(`<text x="${padL + 6}" y="${y(lo(0)) - 6}" class="axis">${b.label}</text>`);
    }
  }
  for (const v of o.y.ticks) {
    const yy = Math.round(y(v)) + 0.5;
    const strong = o.y.base === v;
    out.push(
      `<line x1="${padL}" x2="${W - padR}" y1="${yy}" y2="${yy}" style="stroke:var(${strong ? "--color-border-strong" : "--color-border"});stroke-width:1"/>`,
      `<text x="${padL - 8}" y="${yy + 4}" text-anchor="end" class="axis">${fmt(v)}</text>`,
    );
  }
  for (const r of o.refs ?? []) {
    const yy = Math.round(y(r.v)) + 0.5;
    out.push(
      `<line x1="${padL}" x2="${W - padR}" y1="${yy}" y2="${yy}" style="stroke:var(--color-border-strong);stroke-width:1"/>`,
      `<text x="${W - padR}" y="${yy - 5}" text-anchor="end" class="axis">${r.label}</text>`,
    );
  }
  for (const s of o.series) {
    let d = "";
    let pen = false;
    let prev = null;
    s.v.forEach((v, i) => {
      if (v == null) {
        if (!s.connect) pen = false;
        return;
      }
      const px = x(i).toFixed(1);
      const py = y(v).toFixed(1);
      if (!pen) d += `M${px},${py}`;
      else if (s.step) d += `H${px}V${py}`;
      else d += `L${px},${py}`;
      pen = true;
      prev = i;
    });
    if (s.area) {
      const first = s.v.findIndex((v) => v != null);
      out.push(
        `<path d="${d}L${x(prev).toFixed(1)},${plotB}L${x(first).toFixed(1)},${plotB}Z" style="fill:${s.color};fill-opacity:0.1"/>`,
      );
    }
    if (s.sw !== 0) {
      out.push(
        `<path d="${d}" style="fill:none;stroke:${s.color};stroke-width:${s.sw ?? 2};stroke-linejoin:round;stroke-linecap:round"/>`,
      );
    }
    if (s.dots) {
      s.v.forEach((v, i) => {
        if (v == null) return;
        if (s.dots === "outside") {
          const lo = Array.isArray(s.band.lo) ? s.band.lo[i] : s.band.lo;
          const hi = Array.isArray(s.band.hi) ? s.band.hi[i] : s.band.hi;
          if (v >= lo && v <= hi) return;
        }
        out.push(
          `<circle cx="${x(i).toFixed(1)}" cy="${y(v).toFixed(1)}" r="${s.r ?? 3}" style="fill:${s.dotColor ?? s.color};stroke:var(--color-surface1);stroke-width:${s.ring ?? 1.5}"/>`,
        );
      });
    }
  }
  if (o.sel != null) {
    const sx = Math.round(x(o.sel)) + 0.5;
    if (o.crosshair) {
      out.push(
        `<line x1="${sx}" x2="${sx}" y1="${padT - 6}" y2="${plotB}" style="stroke:var(--color-text-secondary);stroke-width:1"/>`,
      );
    }
    for (const s of o.series) {
      const v = s.v[o.sel];
      if (v == null || s.noSelDot) continue;
      out.push(
        `<circle cx="${x(o.sel).toFixed(1)}" cy="${y(v).toFixed(1)}" r="4" style="fill:${s.selColor ?? s.color};stroke:var(--color-surface1);stroke-width:2"/>`,
      );
    }
  }
  for (const t of o.xTicks ?? []) {
    out.push(
      `<text x="${x(t.i).toFixed(1)}" y="${H - 4}" text-anchor="${t.anchor ?? "middle"}" class="axis">${t.text}</text>`,
    );
  }
  return `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${out.join("")}</svg>`;
};

// ---------- Columns (weekly load) ----------
/** o: { w, h, values, sel, padL=32, y:{min:0,max,ticks}, label:[indices], xTicks } */
export const columns = (o) => {
  const W = o.w;
  const H = o.h;
  const padL = o.padL ?? 32;
  const padR = 6;
  const padT = 18;
  const axisH = 20;
  const plotB = H - axisH;
  const n = o.values.length;
  const slot = (W - padL - padR) / n;
  const bw = Math.min(20, Math.max(3, slot - 2));
  const rad = Math.min(4, bw / 2);
  const y = (v) => padT + (1 - v / o.y.max) * (plotB - padT);
  const out = [];
  for (const v of o.y.ticks) {
    if (v === 0) continue;
    const yy = Math.round(y(v)) + 0.5;
    out.push(
      `<line x1="${padL}" x2="${W - padR}" y1="${yy}" y2="${yy}" style="stroke:var(--color-border);stroke-width:1"/>`,
      `<text x="${padL - 8}" y="${yy + 4}" text-anchor="end" class="axis">${v}</text>`,
    );
  }
  o.values.forEach((v, i) => {
    if (!v) return;
    const cx = padL + slot * i + slot / 2;
    const x0 = cx - bw / 2;
    const top = y(v);
    const hgt = plotB - top;
    const r = Math.min(rad, hgt);
    const on = i === o.sel;
    const color = on ? "var(--color-text-primary)" : "var(--color-text-tertiary)";
    out.push(
      `<path d="M${x0},${plotB}V${top + r}q0,${-r} ${r},${-r}h${bw - 2 * r}q${r},0 ${r},${r}V${plotB}Z" ${fill(color)}/>`,
    );
    if ((o.label ?? []).includes(i)) {
      out.push(
        `<text x="${cx}" y="${top - 5}" text-anchor="middle" class="axis${on ? " axis-strong" : ""}">${v}</text>`,
      );
    }
  });
  out.push(
    `<line x1="${padL}" x2="${W - padR}" y1="${plotB + 0.5}" y2="${plotB + 0.5}" style="stroke:var(--color-border-strong);stroke-width:1"/>`,
    `<text x="${padL - 8}" y="${plotB + 4}" text-anchor="end" class="axis">0</text>`,
  );
  for (const t of o.xTicks ?? []) {
    const cx = padL + slot * t.i + slot / 2;
    out.push(
      `<text x="${cx}" y="${H - 4}" text-anchor="${t.anchor ?? "middle"}" class="axis">${t.text}</text>`,
    );
  }
  return `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${out.join("")}</svg>`;
};

// ---------- Sleep timing (floating bars on a clock axis) ----------
/** nights: [{bed, wake}] in minutes after 18:00; usual: {bed:[lo,hi], wake:[lo,hi]} */
export const sleepTiming = (o) => {
  const W = o.w;
  const H = o.h;
  const padL = o.padL ?? 40;
  const padR = 6;
  const padT = 6;
  const axisH = o.xTicks?.length ? 20 : 4;
  const plotB = H - axisH;
  const n = o.nights.length;
  const slot = (W - padL - padR) / n;
  const bw = Math.min(8, slot - 3);
  const t0 = 21 * 60; // 21:00 at the top
  const t1 = 33 * 60; // 09:00 at the bottom
  const y = (m) => padT + ((m - t0) / (t1 - t0)) * (plotB - padT);
  const out = [];
  for (const [lo, hi] of [o.usual.bed, o.usual.wake]) {
    out.push(
      `<rect x="${padL}" y="${y(lo)}" width="${W - padL - padR}" height="${y(hi) - y(lo)}" ${fill("var(--color-surface3)")}/>`,
    );
  }
  for (const m of [22 * 60, 24 * 60, 26 * 60, 28 * 60, 30 * 60, 32 * 60]) {
    const yy = Math.round(y(m)) + 0.5;
    out.push(
      `<line x1="${padL}" x2="${W - padR}" y1="${yy}" y2="${yy}" style="stroke:var(--color-border);stroke-width:1"/>`,
      `<text x="${padL - 8}" y="${yy + 4}" text-anchor="end" class="axis">${clock(m)}</text>`,
    );
  }
  o.nights.forEach((nt, i) => {
    if (!nt) return;
    const cx = padL + slot * i + slot / 2;
    const on = i === o.sel;
    out.push(
      `<rect x="${cx - bw / 2}" y="${y(nt.bed)}" width="${bw}" height="${y(nt.wake) - y(nt.bed)}" rx="${bw / 2}" ${fill(on ? "var(--color-text-primary)" : "var(--color-text-tertiary)")}/>`,
    );
  });
  for (const t of o.xTicks ?? []) {
    const cx = padL + slot * t.i + slot / 2;
    out.push(
      `<text x="${cx}" y="${H - 4}" text-anchor="${t.anchor ?? "middle"}" class="axis">${t.text}</text>`,
    );
  }
  return `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${out.join("")}</svg>`;
};

// ---------- Workout steps as a background (ride detail power chart) ----------
const ZONE_EDGES = [55, 75, 90, 105, 120, 150];
const pieces = (seg) => {
  const a = seg.from * 100;
  const b = seg.to * 100;
  const cuts = [0, 1];
  for (const edge of ZONE_EDGES) {
    const e = edge + 0.5;
    if ((a - e) * (b - e) < 0) cuts.push((e - a) / (b - a));
  }
  cuts.sort((p, q) => p - q);
  const res = [];
  for (let i = 0; i + 1 < cuts.length; i++) {
    res.push({
      u: cuts[i],
      v: cuts[i + 1],
      zone: zoneOf(a + (b - a) * ((cuts[i] + cuts[i + 1]) / 2)),
    });
  }
  return res;
};
/** Planned steps drawn as blended zone fills: lerp(surface1, zone, 0.35), opaque. */
export const planSteps = (workout, { x, y, plotB, ftp, gap = 1 }) => {
  const out = [];
  for (const s of flatten(workout.steps)) {
    const x0 = x(s.start);
    const x1 = Math.max(x0 + 1, x(s.start + s.duration) - gap);
    if (s.type === "free") {
      out.push(
        `<rect x="${x0}" y="${y(0.5 * ftp)}" width="${x1 - x0}" height="${plotB - y(0.5 * ftp)}" ${fill("color-mix(in srgb, var(--color-surface3) 35%, var(--color-surface1))")}/>`,
      );
      continue;
    }
    for (const p of pieces(s)) {
      const px0 = x0 + (x1 - x0) * p.u;
      const px1 = x0 + (x1 - x0) * p.v;
      const fAt = (px) => s.from + (s.to - s.from) * ((px - x0) / (x1 - x0));
      out.push(
        `<polygon points="${px0},${plotB} ${px0},${y(fAt(px0) * ftp)} ${px1},${y(fAt(px1) * ftp)} ${px1},${plotB}" ${fill(`color-mix(in srgb, var(--zone-${p.zone}) 35%, var(--color-surface1))`)}/>`,
      );
    }
  }
  return out.join("");
};

/** Mini profile for the planned card (current colours, nothing past). */
export const miniProfile = (workout, w, h) => {
  const segs = flatten(workout.steps);
  const total = segs.at(-1).start + segs.at(-1).duration;
  const x = (t) => (t / total) * w;
  const y = (f) => h - (Math.min(f, 1.5) / 1.5) * h;
  const out = [];
  for (const s of segs) {
    const x0 = x(s.start);
    const x1 = Math.max(x0 + 1, x(s.start + s.duration) - 1);
    if (s.type === "free") {
      out.push(
        `<rect x="${x0}" y="${y(0.5)}" width="${x1 - x0}" height="${h - y(0.5)}" ${fill("var(--color-surface3)")}/>`,
      );
      continue;
    }
    for (const p of pieces(s)) {
      const px0 = x0 + (x1 - x0) * p.u;
      const px1 = x0 + (x1 - x0) * p.v;
      const fAt = (px) => s.from + (s.to - s.from) * ((px - x0) / (x1 - x0));
      out.push(
        `<polygon points="${px0},${h} ${px0},${y(fAt(px0))} ${px1},${y(fAt(px1))} ${px1},${h}" ${fill(`var(--zone-${p.zone})`)}/>`,
      );
    }
  }
  return `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${out.join("")}</svg>`;
};

// ---------- Synthetic data ----------
/** One year of daily load ending today (today's ride has not happened yet at 07:42). */
export const fitnessData = () => {
  const r = rng(7);
  const N = 365;
  const pattern = { 1: 118, 2: 0, 3: 92, 4: 80, 5: 0, 6: 140, 0: 62 };
  const days = [];
  let ctl = 34;
  let atl = 34;
  for (let i = 0; i < N; i++) {
    const date = dayOf(N - 1 - i);
    const dow = date.getUTCDay();
    const week = Math.floor(i / 7);
    let scale = 0.6 + 0.4 * (i / (N - 1)) + 0.07 * Math.sin(i / 23);
    if (week % 4 === 1) scale *= 0.55;
    let ride = pattern[dow] * scale * (0.85 + 0.3 * r());
    if (r() < 0.07) ride = 0;
    let other = 0;
    if ((dow === 0 || dow === 3) && r() < 0.35) other = 22 + 18 * r();
    // The last nine days match the Rides list (days ago -> [ride load, watch load]).
    const fixed = {
      0: [0, 0],
      1: [0, 0],
      2: [97, 0],
      3: [0, 38],
      4: [81, 0],
      5: [0, 0],
      6: [68, 0],
      7: [0, 27],
      8: [74, 0],
    };
    const ago = N - 1 - i;
    if (fixed[ago]) [ride, other] = fixed[ago];
    const load = Math.round(ride + other);
    ctl += (load - ctl) / 42;
    atl += (load - atl) / 7;
    days.push({
      date,
      load,
      ride: Math.round(ride),
      other: Math.round(other),
      ctl,
      atl,
      tsb: ctl - atl,
    });
  }
  return days;
};

export const formWord = (tsb) =>
  tsb > 5 ? "Fresh" : tsb >= -10 ? "Neutral" : tsb >= -30 ? "Training hard" : "Very fatigued";

/** Weeks (Monday first) over the given days. */
export const weeksOf = (days) => {
  const weeks = [];
  for (const d of days) {
    const dow = (d.date.getUTCDay() + 6) % 7;
    if (dow === 0 || weeks.length === 0)
      weeks.push({ start: d.date, load: 0, ride: 0, other: 0, rides: 0 });
    const w = weeks.at(-1);
    w.load += d.load;
    w.ride += d.ride;
    w.other += d.other;
    if (d.ride) w.rides += 1;
  }
  return weeks;
};

/** Month-start ticks for a day range. */
export const monthTicks = (days, every = 1) => {
  const t = [];
  days.forEach((d, i) => {
    if (
      d.date.getUTCDate() === 1 &&
      d.date.getUTCMonth() % every === 0 &&
      i > 3 &&
      i < days.length - 6
    ) {
      t.push({ i, text: monthName(d.date) });
    }
  });
  return t;
};

// Power curve: a three-parameter critical-power shape with a fade past 20 minutes.
export const DURATIONS = [
  1, 2, 3, 5, 8, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300, 420, 600, 900, 1200, 1800, 2400,
  3600, 5400, 7200,
];
export const curve = (cp, wp, k, maxDur) =>
  DURATIONS.map((t) => {
    if (t > maxDur) return null;
    let p = cp + wp / (t + k);
    if (t > 1200) p *= 1 - 0.045 * Math.log(t / 1200);
    return Math.round(p);
  });
export const logX = (t) => Math.log(t) / Math.log(7200);
export const durLabel = (t) => (t < 60 ? `${t} s` : t < 3600 ? `${t / 60} min` : `${t / 3600} h`);
