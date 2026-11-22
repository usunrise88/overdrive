#!/usr/bin/env node
// Renders every .frame / .sheet element of design/mockups/*.html to
// design/mockups/screenshots/<page>--<id>.png, and of design/mockups/companion/*.html to
// design/mockups/companion/png/<page>--<id>.png, at device scale factor 2.
// Needs Playwright with Chromium (not a repo dependency): it is resolved from the global
// installation, e.g. NODE_PATH=$(npm root -g) node design/tools/render-mockups.mjs [page ...]
// CHROMIUM_PATH=/path/to/chrome uses that browser binary instead of Playwright's own.

import { readdirSync, readFileSync } from "node:fs";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { dirname, extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const designDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const mockDir = join(designDir, "mockups");
const types = {
  ".html": "text/html",
  ".css": "text/css",
  ".js": "text/javascript",
  ".svg": "image/svg+xml",
  ".ttf": "font/ttf",
};

const server = createServer((req, res) => {
  const path = normalize(decodeURIComponent(new URL(req.url, "http://x").pathname));
  const file = join(designDir, path);
  if (!file.startsWith(designDir)) {
    res.writeHead(403).end();
    return;
  }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { "content-type": types[extname(file)] ?? "application/octet-stream" });
    res.end(body);
  } catch {
    console.warn(`404 ${path}`);
    res.writeHead(404).end();
  }
});
await new Promise((ok) => server.listen(0, "127.0.0.1", ok));
const base = `http://127.0.0.1:${server.address().port}`;

const only = process.argv.slice(2);
const pages = [
  ...readdirSync(mockDir)
    .filter((f) => f.endsWith(".html"))
    .map((f) => ({ file: f, out: join(mockDir, "screenshots") })),
  ...readdirSync(join(mockDir, "companion"))
    .filter((f) => f.endsWith(".html"))
    .map((f) => ({ file: `companion/${f}`, out: join(mockDir, "companion", "png") })),
].filter(({ file }) => only.length === 0 || only.some((o) => file.startsWith(o)));

const browser = await chromium.launch(
  process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {},
);
const page = await browser.newPage({
  deviceScaleFactor: 2,
  viewport: { width: 1400, height: 900 },
});
for (const { file, out: outDir } of pages) {
  await page.goto(`${base}/mockups/${file}`);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(() => document.body.dataset.ready !== "false");
  const frames = await page.$$(".frame[id], .sheet[id]");
  for (const frame of frames) {
    const id = await frame.getAttribute("id");
    const name = file.split("/").at(-1).replace(".html", "");
    const out = join(outDir, `${name}--${id}.png`);
    await frame.screenshot({ path: out });
    console.log(out.slice(designDir.length + 1));
  }
}
await browser.close();
server.close();
