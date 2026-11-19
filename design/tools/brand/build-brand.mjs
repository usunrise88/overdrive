#!/usr/bin/env node
// Builds the Overdrive brand assets from the mark (mark.mjs) and the accents below:
//   design/logo/        the mark as SVG, the app icons, the lockups, the bot avatar and the cover
//   android/<app>/src/main/res/mipmap-*/   the adaptive launcher layers (background, foreground, monochrome)
//   android/ui-kit/.../OverdriveMarkPath.kt   the mark's outline for the in-app OverdriveMark composable
// Needs a Chromium (not a repo dependency): CHROMIUM_PATH=/path/to/chrome, else Playwright's own in ~/.cache/ms-playwright.
//   node design/tools/brand/build-brand.mjs [icons] [android] [logos] [kotlin]      (no argument builds everything)
// The icons are rendered once at 1024 and made smaller by repeated halving, so the grain does not alias. Only the 512 px
// icons are kept in the repository (the grain makes a 1024 px PNG 2 MB); the tool rebuilds the large ones.

import { execFileSync } from "node:child_process";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { glyph } from "./mark.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..", "..");
const logoDir = join(root, "design", "logo");
const fontsDir = join(root, "design", "fonts");

/** The accents: each app has its own pair of colours and how strongly they glow. The mark itself is always white. */
export const ACCENTS = {
  tablet: { a: "ffb020", b: "ff5a1f", gain: 1.4, app: "app-ride" }, // gold
  phone: { a: "b6ff00", b: "00ffa3", gain: 1.8, app: "app-companion" }, // lime
  server: { a: "b6ff00", b: "ffb020", gain: 1.6, app: null }, // lime and gold: the bot and the service
};

function chromium() {
  if (process.env.CHROMIUM_PATH) return process.env.CHROMIUM_PATH;
  const base = join(homedir(), ".cache", "ms-playwright");
  const dirs = existsSync(base)
    ? readdirSync(base)
        .filter((d) => /^chromium-\d+$/.test(d))
        .sort()
        .reverse()
    : [];
  for (const d of dirs) {
    const p = join(base, d, "chrome-linux64", "chrome");
    if (existsSync(p)) return p;
  }
  throw new Error("No Chromium found: set CHROMIUM_PATH");
}
const chrome = chromium();
const work = mkdtempSync(join(tmpdir(), "overdrive-brand-"));
process.on("exit", () => rmSync(work, { recursive: true, force: true }));
writeFileSync(join(work, "glyph.js"), `const GLYPH=${JSON.stringify(glyph())};`);
for (const f of ["icon.html", "lockup.html"]) copyFileSync(join(here, f), join(work, f));

function shot(url, out, w, h, transparent = false) {
  execFileSync(
    chrome,
    [
      "--headless=new",
      "--no-sandbox",
      "--disable-gpu",
      "--hide-scrollbars",
      "--allow-file-access-from-files",
      `--window-size=${w},${h}`,
      ...(transparent ? ["--default-background-color=00000000"] : []),
      `--screenshot=${out}`,
      url,
    ],
    { stdio: "ignore" },
  );
}
const page = (file, params) => `file://${join(work, file)}?${new URLSearchParams(params)}`;

/** 1024 -> size by repeated halving (each step is a plain 2x reduction, so nothing aliases). */
function shrink(src, out, size, transparent = false) {
  let cur = src;
  let px = 1024;
  let i = 0;
  while (px > size) {
    const next = Math.max(size, Math.floor(px / 2));
    const html = join(work, `s${i}.html`);
    writeFileSync(
      html,
      `<!doctype html><body style="margin:0;background:transparent"><img src="file://${cur}" style="width:${next}px;height:${next}px;display:block">`,
    );
    const dst = join(work, `s${i}.png`);
    shot(`file://${html}`, dst, next, next, transparent);
    cur = dst;
    px = next;
    i++;
  }
  copyFileSync(cur, out);
}

const icon = (accent, extra) => {
  const c = ACCENTS[accent];
  return page("icon.html", { accent: c.a, accent2: c.b, gain: c.gain, size: 1024, ...extra });
};
const want = (name) => process.argv.length <= 2 || process.argv.includes(name);
mkdirSync(logoDir, { recursive: true });

if (want("icons")) {
  for (const name of Object.keys(ACCENTS)) {
    const big = join(work, `icon-${name}.png`);
    shot(icon(name, { mode: "full", scale: 0.72 }), big, 1024, 1024);
    shrink(big, join(logoDir, `icon-${name}-512.png`), 512);
  }
  // The bot avatar (Telegram crops it to a circle, so the mark is smaller) and the service's own.
  const av = join(work, "avatar.png");
  shot(icon("server", { mode: "full", scale: 0.6 }), av, 1024, 1024);
  shrink(av, join(logoDir, "bot-avatar.png"), 640);
}

if (want("android")) {
  const sizes = { "mipmap-xhdpi": 216, "mipmap-xxhdpi": 324, "mipmap-xxxhdpi": 432 }; // 108 dp at each density
  for (const [name, c] of Object.entries(ACCENTS)) {
    if (c.app === null) continue;
    for (const [layer, params, transparent] of [
      ["background", { mode: "bg" }, false],
      ["foreground", { mode: "fg", scale: 0.52 }, true],
      ["monochrome", { mode: "mono", scale: 0.52 }, true],
    ]) {
      const big = join(work, `${name}-${layer}.png`);
      shot(icon(name, params), big, 1024, 1024, transparent);
      for (const [dir, px] of Object.entries(sizes)) {
        const res = join(root, "android", c.app, "src", "main", "res", dir);
        mkdirSync(res, { recursive: true });
        shrink(big, join(res, `ic_launcher_${layer}.png`), px, transparent);
      }
    }
  }
}

if (want("logos")) {
  const g = glyph();
  for (const [file, ink] of [
    ["white", "#ffffff"],
    ["black", "#05070a"],
  ]) {
    writeFileSync(
      join(logoDir, `overdrive-mark-${file}.svg`),
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000" role="img"><title>Overdrive</title><path d="${g.d}" fill="${ink}"/></svg>\n`,
    );
    shot(
      page("lockup.html", {
        layout: "lockup",
        ink: ink.slice(1),
        fonts: fontsDir,
        w: 1500,
        h: 400,
      }),
      join(logoDir, `overdrive-logo-${file}.png`),
      1500,
      400,
      true,
    );
  }
  // The cover (README, GitHub social preview): the tablet's background, the mark and the name.
  const bg = join(work, "cover-bg.png");
  shot(icon("tablet", { mode: "bg", size: 1280 }), bg, 1280, 1280);
  shot(
    page("lockup.html", {
      layout: "cover",
      ink: "ffffff",
      fonts: fontsDir,
      w: 1280,
      h: 640,
      bgimg: `file://${bg}`,
      tag: "Your trainer, your data, your server.",
    }),
    join(logoDir, "cover.png"),
    1280,
    640,
  );
}
if (want("kotlin")) {
  // 360 points are plenty on a screen (a segment of a 400 px ring is 8 px and deviates 0.04 px from the circle).
  const d = glyph(360).d;
  const parts = d.match(/.{1,100}/g).map((p) => `    "${p}"`);
  const file = join(
    root,
    "android",
    "ui-kit",
    "src",
    "main",
    "kotlin",
    "app",
    "overdrive",
    "uikit",
    "OverdriveMarkPath.kt",
  );
  writeFileSync(
    file,
    `package app.overdrive.uikit

// Generated by design/tools/brand/build-brand.mjs from the mark's geometry (design/brand.md). Do not edit.

/** The outline of the Overdrive mark as an SVG path in a 1000-unit box, its longest side exactly 1000. */
internal const val MARK_PATH =\n${parts.join(" +\n")}\n`,
  );
}
console.log("brand assets built");
