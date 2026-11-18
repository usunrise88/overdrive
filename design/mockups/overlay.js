// Builds overlay mock-ups from a state object, exactly as design/system.md "Overlay" specifies.
import { profile } from "./charts.js";

const DASH = "—";
const val = (v, cls) =>
  v === null || v === undefined
    ? `<div class="val num ${cls} empty">${DASH}</div>`
    : `<div class="val num ${cls}">${v}</div>`;

/**
 * s: { power, zone, hr, cad, time, gear, target, intensity, paused, detailed,
 *      speed, dist, interval: { name, of, left, next }, workout, now, ftp }
 */
export const overlayHtml = (s) => {
  const paused = Boolean(s.paused);
  const powerCls = paused || s.power === null ? "t-num-m c3" : `t-num-m zc z${s.zone}`;
  const dim = paused ? " c3" : "";
  const zoneTag = s.power === null || paused ? "" : `<b>Z${s.zone}</b>`;
  const last =
    s.target !== undefined
      ? `<div class="cell w-target"><div class="lbl"><span>Target</span><b>${s.intensity}%</b></div>${val(s.target, `t-num-s${dim}`)}</div>`
      : `<div class="cell w-target"><div class="lbl"><span>Gear</span></div>${val(s.gear, `t-num-s${dim}`)}</div>`;
  const timeLabel = paused
    ? `<div class="lbl"><span style="color:var(--color-text-primary)">Paused</span></div>`
    : `<div class="lbl"><span>Time</span></div>`;
  let html = `<div class="ov-row">
    <div class="cell w-power"><div class="lbl"><span>Power</span>${zoneTag}</div>${val(s.power, powerCls)}</div>
    <div class="cell w-hr"><div class="lbl"><span>Heart</span></div>${val(s.hr, `t-num-s${dim}`)}</div>
    <div class="cell w-cad"><div class="lbl"><span>Cadence</span></div>${val(s.cad, `t-num-s${dim}`)}</div>
    <div class="cell w-time">${timeLabel}${val(s.time, "t-num-s")}</div>
    ${last}
  </div>`;
  if (s.detailed) {
    html += `<div class="ov-rule"></div>
    <div class="ov-row sec">
      <div class="cell w-power"><div class="lbl"><span>Speed</span></div><div class="val num t-num-s${dim}">${s.speed}<span class="unit">km/h</span></div></div>
      <div class="cell"><div class="lbl"><span>Distance</span></div><div class="val num t-num-s${dim}">${s.dist}<span class="unit">km</span></div></div>
    </div>`;
    if (s.interval) {
      const i = s.interval;
      html += `<div class="ov-rule"></div>
      <div class="ov-interval">
        <div class="head">
          <div class="t-strong">${i.name}<span class="c2" style="font-weight:400"> ${i.of}</span></div>
          <div class="num t-num-s">${i.left}<span class="unit">left</span></div>
        </div>
        <div class="t-label"><span class="c3">Next</span>&nbsp;&nbsp;<span class="c2 num">${i.next}</span></div>
        <svg class="ov-profile" data-now="${s.now}"></svg>
      </div>`;
    }
  }
  return html;
};

export const mountOverlay = (node, s, workout) => {
  node.classList.add("ov");
  node.innerHTML = overlayHtml(s);
  const svg = node.querySelector(".ov-profile");
  if (svg && workout) {
    const width = node.clientWidth - 2 - 32;
    profile(svg, workout, {
      width,
      height: 36,
      now: s.now,
      yMax: 1.5,
      gap: 1,
      surface: "var(--color-overlay-backing)",
    });
  }
};
