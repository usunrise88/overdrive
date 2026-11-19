#!/usr/bin/env node
// Renders the launch animation of both apps (design/brand.md, "The launch animation"): a white road bike rides in from the bottom
// edge seen from above, the camera flies round it to a side view, and the app's colour, the mark and the name rise from below.
// The scene is intro.html (three.js, stepped frame by frame in headless Chromium); the frames are encoded to WebM (VP8) with
// the ffmpeg that Playwright installs, so the apps play the result with Android's own player and no library.
//
//   THREE_DIR=/path/to/three MODEL=/path/to/bike.glb node design/tools/intro/build-intro.mjs [tablet] [phone]
//
// THREE_DIR holds three r160: build/three.module.js, examples/jsm/loaders/GLTFLoader.js, examples/jsm/utils/BufferGeometryUtils.js
// (from the npm package `three@0.160.0`). MODEL is the bike as a GLB (made with Meshy from a picture of a white road bike; the
// 44 MB file is not in the repository). CHROMIUM_PATH and FFMPEG_PATH override the Playwright locations.
// Output: android/app-ride/src/main/res/raw/overdrive_intro.webm and android/app-companion/.../raw/overdrive_intro.webm.

import { spawn } from "node:child_process";
import { createReadStream, existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync, copyFileSync } from "node:fs";
import { createServer } from "node:http";
import { homedir, tmpdir } from "node:os";
import { dirname, extname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { glyph } from "../brand/mark.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..", "..");
const THREE = process.env.THREE_DIR;
const MODEL = process.env.MODEL;
if (!THREE || !MODEL) throw new Error("Set THREE_DIR and MODEL (see the header of this file)");

/** The two videos. speed 1.3 is the owner's "30% faster"; fit is the bike's share of the width at the end. */
const VIDEOS = {
  tablet: { app: "app-ride", w: 1600, h: 1000, query: { accent: "ffb020", accent2: "ff5a1f", speed: 1.3 } },
  phone: { app: "app-companion", w: 720, h: 1600, query: { accent: "b6ff00", accent2: "00ffa3", sub: "Companion", fit: 0.86, lift: 0.1, speed: 1.3 } },
};

const pw = join(homedir(), ".cache", "ms-playwright");
const find = (prefix, ...tail) => {
  const d = existsSync(pw) ? readdirSync(pw).filter((n) => n.startsWith(prefix)).sort().reverse()[0] : undefined;
  return d ? join(pw, d, ...tail) : undefined;
};
const chrome = process.env.CHROMIUM_PATH ?? find("chromium-", "chrome-linux64", "chrome");
const ffmpeg = process.env.FFMPEG_PATH ?? find("ffmpeg-", "ffmpeg-linux");
if (!chrome || !ffmpeg) throw new Error("No Chromium or ffmpeg found: set CHROMIUM_PATH and FFMPEG_PATH");

// A directory the page loads everything from, served over HTTP (module scripts do not load from file://).
const site = mkdtempSync(join(tmpdir(), "overdrive-intro-"));
symlinkSync(THREE, join(site, "three"));
mkdirSync(join(site, "bike3d"));
symlinkSync(MODEL, join(site, "bike3d", "bwhite.glb"));
symlinkSync(join(root, "design", "fonts"), join(site, "fonts"));
copyFileSync(join(here, "intro.html"), join(site, "intro.html"));
writeFileSync(join(site, "brand-glyph.js"), `const GLYPH=${JSON.stringify(glyph())};`);
const types = { ".html": "text/html", ".js": "text/javascript", ".ttf": "font/ttf", ".glb": "model/gltf-binary" };
const server = createServer((req, res) => {
  const file = join(site, decodeURIComponent(new URL(req.url, "http://x").pathname));
  if (!file.startsWith(site) || !existsSync(file) || !statSync(file).isFile()) return res.writeHead(404).end();
  res.writeHead(200, { "content-type": types[extname(file)] ?? "application/octet-stream" });
  createReadStream(file).pipe(res);
});
await new Promise((ok) => server.listen(0, "127.0.0.1", ok));
const base = `http://127.0.0.1:${server.address().port}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function render(name, { app, w, h, query }, fps = 30) {
  const port = 9300 + Math.floor(Math.random() * 500);
  const proc = spawn(chrome, ["--headless=new", "--no-sandbox", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist",
    `--remote-debugging-port=${port}`, `--window-size=${w},${h}`, "about:blank"], { stdio: "ignore" });
  let target;
  for (let i = 0; i < 50 && !target; i++) {
    try { target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((t) => t.type === "page"); } catch {}
    if (!target) await sleep(200);
  }
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r) => (ws.onopen = r));
  let id = 0;
  const pending = new Map();
  ws.onmessage = (m) => { const d = JSON.parse(m.data); if (pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } };
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  await send("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 1, mobile: false });
  await send("Page.navigate", { url: `${base}/intro.html?${new URLSearchParams(query)}` });
  let state = "";
  for (let i = 0; i < 800 && !state; i++) {
    state = (await send("Runtime.evaluate", { expression: 'document.body && document.body.getAttribute("data-state") || ""' })).result?.result?.value ?? "";
    if (!state) await sleep(250);
  }
  if (state !== "done") throw new Error(`the page did not load: ${state}`);
  const dur = (await send("Runtime.evaluate", { expression: "window.DUR" })).result.result.value;
  const dir = join(root, "android", app, "src", "main", "res", "raw");
  mkdirSync(dir, { recursive: true });
  const out = join(dir, "overdrive_intro.webm");
  const ff = spawn(ffmpeg, ["-hide_banner", "-loglevel", "error", "-y", "-f", "image2pipe", "-framerate", String(fps), "-c:v", "mjpeg", "-i", "pipe:0",
    "-c:v", "libvpx", "-b:v", "3M", "-crf", "10", "-quality", "good", "-cpu-used", "1", "-pix_fmt", "yuv420p", "-an", out], { stdio: ["pipe", "inherit", "inherit"] });
  const frames = Math.round(dur * fps);
  for (let f = 0; f < frames; f++) {
    await send("Runtime.evaluate", { expression: `setT(${Math.min(dur, f / fps)}); new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))`, awaitPromise: true });
    const shot = await send("Page.captureScreenshot", { format: "jpeg", quality: 93, optimizeForSpeed: true });
    ff.stdin.write(Buffer.from(shot.result.data, "base64"));
  }
  ff.stdin.end();
  await new Promise((r) => ff.on("close", r));
  ws.close();
  proc.kill();
  console.log(`${name}: ${out} (${frames} frames, ${dur.toFixed(2)} s)`);
}

try {
  const want = process.argv.slice(2);
  for (const [name, v] of Object.entries(VIDEOS)) if (want.length === 0 || want.includes(name)) await render(name, v);
} finally {
  server.close();
  rmSync(site, { recursive: true, force: true });
}
