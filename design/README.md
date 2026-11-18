# Design

Design comes before UI code (spec §7.4). This directory holds the design system and the
mock-ups for the ride app (tablet), its overlay and the companion (phone). Nothing here ships;
`android/ui-kit` implements it. Decisions and their reasons: `docs/adr/0002-design-direction.md`.

## Files

| Path | What it is |
|---|---|
| `system.md` | **The spec for implementers.** Rules, every component with sizes and states, overlay geometry and behaviour, charts, motion, copy, Compose notes |
| `companion.md` | The phone companion (Phase 3): navigation, every screen and state, every chart, copy, accessibility, performance, the data each screen needs |
| `tokens.json` | Source of truth for colour, type, spacing, radii, strokes, sizes, overlay metrics, chart and motion values |
| `tools/build-tokens.mjs` | Writes `mockups/tokens.css`, `android/ui-kit` `OverdriveTokens.kt` and the Geist font resources from `tokens.json`; `--check` fails when any is stale |
| `tools/check-contrast.mjs` | Checks the contrast rules (zones ≥ 7:1 on the overlay over white video, text tiers on every surface) and prints the colour-vision distances; exits 1 on failure |
| `tools/render-mockups.mjs` | Serves `design/` locally and screenshots every frame of every mock-up with Playwright (`mockups/companion/` pages go to `mockups/companion/png/`) |
| `fonts/` | Geist 400, 500, 600 (TTF, ~380 KB) and its licence, SIL OFL 1.1. From npm `geist` 1.9.2 |
| `icons/` | The Lucide icons we use (SVG, stroke 1.5) and the ISC licence. From npm `lucide-static` 1.74.0 |
| `mockups/*.html` | Static mock-ups: `overlay`, `ride`, `devices` (with the component sheet), `settings` |
| `mockups/companion/` | Companion mock-ups (phone, 412 dp): `today`, `fitness`, `power`, `wellness`, `rides`, `settings` (with first run), `states`; renders in `png/` |
| `mockups/components.css`, `charts.js`, `overlay.js`, `ui.js` | Mock-up building blocks; `charts.js` holds the reference geometry of the workout profile |
| `mockups/screenshots/` | Rendered PNGs at 2× (one per frame); the companion's are in `mockups/companion/png/` (index in `companion.md` §13) |

### Mock-up index

| Screenshot | Shows |
|---|---|
| `overlay--dark`, `--bright`, `--busy` | Compact and detailed overlay over three synthetic video frames (both shown side by side for comparison; only one is on screen in use) |
| `overlay--zones` | Power in Z1–Z7 over bright and dark video |
| `overlay--states` | Free ride (gear), paused, heart-rate strap lost, ERG at 105%; detailed free ride and detailed paused |
| `ride--ready`, `--riding`, `--paused`, `--finished` | Tablet ride app, 1280 × 800 dp |
| `devices--pairing` | Devices with the Click "locked" notice, and the Click button map |
| `devices--components` | Device-row states, buttons, chips, zone chips, stat tiles, inputs |
| `settings--settings` | Overlay (live preview, mode, opacity, blur), FTP, gears, physics |

All numbers in the mock-ups are synthetic.

## Regenerate

```sh
node design/tools/build-tokens.mjs        # tokens.json -> mockups/tokens.css
node design/tools/check-contrast.mjs      # must print "All contrast rules pass."
NODE_PATH=$(npm root -g) node design/tools/render-mockups.mjs [page ...]   # needs global playwright + chromium
```

The committed app-screen PNGs (not the overlay ones, whose video backdrops would band) were then
reduced to 256 colours with Pillow (`Image.quantize(256, MEDIANCUT, dither=NONE)`) to keep the
repository small. This step is optional.

## Principles applied

The owner's order: **concision, clarity, convenience, aesthetics, consistency**; the earlier wins.

1. **Concision.** The overlay carries five numbers; detail is one tap away. No icons where a word
   is shorter, no cards around single values, no decoration.
2. **Clarity.** A plain-word label over every number; units after values; "—" for missing data;
   state as words and icons; zone colour always with "Z3" or a chart position.
3. **Convenience.** 64 dp ride targets, every Click action on screen, and the state the Click
   changes (gear or intensity %, interval, paused) always visible.
4. **Aesthetics.** Cool near-black neutrals, hairlines, Geist, generous space, one accent
   system: the seven power zones.
5. **Consistency.** One token set and one set of components across the overlay, tablet and phone.

## References

The reference sites (raycast.com, gradium.ai, vercel.com/geist) could not be fetched from the
build sandbox: its network proxy refused all three hosts. The notes below come from prior
knowledge of their public design language, not from a fresh study.

| Reference | What we take | What we leave |
|---|---|---|
| **Raycast** | Near-black, slightly cool surfaces stepped by small lightness increments; 1 px hairline borders instead of shadows; dense but calm lists with a quiet secondary line; keyboard-first, every action reachable without hunting (our Click-first parallel) | Glassy translucency, gradient glows and coloured hero lighting of the marketing site; the purple-red brand accents |
| **Gradium** | Restraint: few elements per view, large confident type for the one thing that matters, lots of empty space, near-monochrome palette | Editorial, marketing-scale whitespace that a 1280 × 800 ride screen cannot afford; any animated or atmospheric backgrounds |
| **Vercel / Geist** | The Geist typeface itself (OFL, has tabular figures); the white primary button on black; grey scale with clearly separated text tiers; sentence-case labels; hairline-bordered tile grids for metrics | The light theme, blue link/success accents and coloured status pills: our only colours are the power zones |

What we deliberately reject everywhere: gradients, shadows, glows and blur on screen during a
ride (battery and GPU), a brand colour, colour-coded status, animated numbers, all-caps labels,
and a second typeface.
