#!/usr/bin/env node
// Checks the contrast and colour-vision rules of design/tokens.json. No dependencies.
// Run: node design/tools/check-contrast.mjs   (exit code 1 if a hard rule fails)
//
// Hard rules (design/system.md, "Colour"):
//   - every zone colour >= 7:1 on the overlay backing at its default opacity over white video
//   - every zone colour >= 5.5:1 on the backing at the minimum opacity over white video
//   - every zone colour >= 7:1 on bg and surface1
//   - text.primary >= 7:1 and text.secondary >= 4.5:1 on every surface and on the backing
//   - text.tertiary >= 4.5:1 on bg, surface1, surface2
// Reported, not enforced: OKLab distance of adjacent zones under normal vision and simulated
// protanopia / deuteranopia (Machado et al. 2009, severity 1). Zones always carry a number or a
// position, so colour is never the only channel.

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const tokens = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), "..", "tokens.json"), "utf8"),
);

const rgb = (h) => [1, 3, 5].map((i) => Number.parseInt(h.slice(i, i + 2), 16) / 255);
const lin = (c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const hex = (c) =>
  `#${c
    .map((v) =>
      Math.round(v * 255)
        .toString(16)
        .padStart(2, "0"),
    )
    .join("")}`;
const luminance = (h) => {
  const [r, g, b] = rgb(h).map(lin);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a, b) => {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};
// Android composites a translucent window in gamma-encoded sRGB.
const over = (top, alpha, under) => {
  const u = rgb(under);
  return hex(rgb(top).map((c, i) => alpha * c + (1 - alpha) * u[i]));
};
const oklab = ([r, g, b]) => {
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
};
const CVD = {
  protan: [
    [0.152286, 1.052583, -0.204868],
    [0.114503, 0.786281, 0.099216],
    [-0.003882, -0.048116, 1.051998],
  ],
  deutan: [
    [0.367322, 0.860646, -0.227968],
    [0.280085, 0.672501, 0.047413],
    [-0.01182, 0.04294, 0.968881],
  ],
};
const simulate = (h, kind) => {
  const c = rgb(h).map(lin);
  if (!kind) return c;
  return CVD[kind].map((row) =>
    Math.min(1, Math.max(0, row[0] * c[0] + row[1] * c[1] + row[2] * c[2])),
  );
};
const deltaE = (a, b, kind) => {
  const p = oklab(simulate(a, kind));
  const q = oklab(simulate(b, kind));
  return 100 * Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]);
};

const n = tokens.color.neutral;
const t = tokens.color.text;
const zones = Object.entries(tokens.color.zone)
  .filter(([k]) => /^z\d$/.test(k))
  .map(([k, v]) => [k.toUpperCase(), v.value]);
const backing = tokens.color.overlay.backing.value;
const op = tokens.overlay.opacity;
const backDefault = over(backing, op.default, "#FFFFFF");
const backMin = over(backing, op.min, "#FFFFFF");

const failures = [];
const check = (label, ratio, min) => {
  const ok = ratio >= min;
  if (!ok) failures.push(`${label}: ${ratio.toFixed(2)} < ${min}`);
  return `${ratio.toFixed(2)}${ok ? "" : " FAIL"}`;
};

console.log(
  `Overlay backing ${backing} over white video: ${backDefault} at ${op.default * 100}%, ${backMin} at ${op.min * 100}%\n`,
);
console.log("zone  colour   backing@default  backing@min  bg     surface1");
for (const [name, c] of zones) {
  console.log(
    [
      name.padEnd(5),
      c,
      check(`${name} on backing@default`, contrast(c, backDefault), 7).padEnd(16),
      check(`${name} on backing@min`, contrast(c, backMin), 5.5).padEnd(12),
      check(`${name} on bg`, contrast(c, n.bg.value), 7).padEnd(6),
      check(`${name} on surface1`, contrast(c, n.surface1.value), 7),
    ].join("  "),
  );
}

console.log("\ntext       bg     surface1  surface2  backing@default");
const textRules = [
  ["primary", t.primary.value, 7, true],
  ["secondary", t.secondary.value, 4.5, true],
  ["tertiary", t.tertiary.value, 4.5, false],
];
for (const [name, c, min, onBacking] of textRules) {
  console.log(
    [
      name.padEnd(9),
      check(`text.${name} on bg`, contrast(c, n.bg.value), min).padEnd(5),
      check(`text.${name} on surface1`, contrast(c, n.surface1.value), min).padEnd(8),
      check(`text.${name} on surface2`, contrast(c, n.surface2.value), min).padEnd(8),
      onBacking
        ? check(`text.${name} on backing`, contrast(c, backDefault), min)
        : contrast(c, backDefault).toFixed(2),
    ].join("  "),
  );
}

console.log("\nadjacent zones, OKLab ΔE×100   normal  protan  deutan");
for (let i = 0; i + 1 < zones.length; i++) {
  const [[an, a], [bn, b]] = [zones[i], zones[i + 1]];
  console.log(
    `${an}–${bn}`.padEnd(32),
    [undefined, "protan", "deutan"].map((k) => deltaE(a, b, k).toFixed(1).padStart(6)).join("  "),
  );
}

if (failures.length) {
  console.error(`\n${failures.length} rule(s) failed:\n  ${failures.join("\n  ")}`);
  process.exit(1);
}
console.log("\nAll contrast rules pass.");
