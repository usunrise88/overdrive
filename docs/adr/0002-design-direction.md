# ADR 0002: Design direction

Status: accepted (stage M1 design pass, 2026-11-18)

## Context

Spec §7.4 asks for a design system and mock-ups before UI code. The owner ranked the interface
principles concision, clarity, convenience, aesthetics, consistency, and named raycast.com,
gradium.ai and Vercel as references. Hard constraints: dark theme, tabular figures that never
shift, power-zone colours as the only accent, no motion on the overlay at rest, an overlay that
stays legible over any video, and battery and frame rate as requirements (§4.6, §7.3).
The full rules are in `design/system.md`; the values in `design/tokens.json`.

## Decisions

**Typeface: Geist, one family, three static weights (400, 500, 600).** SIL OFL, shipped as TTF
(Android cannot use WOFF2), ~380 KB. It has a `tnum` feature with 0.600–0.624 em digits, crisp
numerals at 112 sp and at 12 sp, and it is the Vercel reference's own face. Inter was the
alternative; it is equally capable but more generic, and Geist's squarer numerals read better at a
distance. No monospace face: tabular figures already fix the width.

**Neutrals: a cool near-black ramp** (bg #090C11 → surface1/2/3 → border #2C3136, text tiers
#EEF4FC / #A9AEB6 / #81878D). The suggested tertiary #70757C was raised to #81878D so labels reach
4.5:1 on every surface (it was 4.2:1 on bg).

**Zones: seven light, saturated colours** (Z1 #BBC5D1, Z2 #5BB1FE, Z3 #61DF84, Z4 #F2DE3C,
Z5 #FFA94F, Z6 #FF7C7C, Z7 #EA8DE7). They are the starting palette, refined in OKLCH: Z1 made a
lighter, near-neutral grey (it was hard to tell from Z2), Z6 and Z7 raised in lightness (they were
6.8 and 7.2:1 on bg, below 7:1 over bright video). Every zone is now ≥ 7:1 on the overlay backing
over white video and ≥ 7.3:1 on surface1, checked by `design/tools/check-contrast.mjs`. They
fail a general chart-palette lightness band on purpose: they double as text colour for live
power. Adjacent zones are 12.9–24.5 OKLab ΔE apart in normal vision and ≥ 8.1 under simulated
protan/deutan vision; colour is never the only channel (zone number or chart position always
accompanies it). The live power number is the one place text takes a zone colour.

**No other colour.** No brand colour, no success/warning/error hues. State is word, weight, tone,
icon and the inverted chip. The primary button is near-white on near-black.

**Overlay backing: solid #06080B at 95% opacity, adjustable 85–100%.** Translucent enough to
show the window sits over the video, dark enough that every zone stays ≥ 7:1 over a white frame
(≥ 5.5:1 at 85%). Window blur is an optional setting, off by default: it costs GPU on every video
frame and does not help contrast on bright video. No shadow; a 1 dp hairline edge.

**Overlay density: one row of five label-over-value cells, 464 × 84 dp,** with fixed-width slots
so no digit change moves anything, and the same width in detailed mode (which only adds height).
The gear/target cell is the Click's feedback channel. Tap toggles mode without animation; snap
after drag is the only overlay animation.

**Ride screen density: large and sparse.** 112 sp power, 56 sp tiles, 64 dp buttons that mirror
every Click action, one workout profile with the rider's power trace. The tablet is read from the
saddle, about a metre away.

**Flat rendering everywhere a ride is visible:** solid fills, hairlines, no gradients, shadows,
glows or blur. Elapsed workout steps are pre-blended opaque colours, not alpha.

**Icons: Lucide (ISC), stroke 1.5, a small vendored subset.** Used only where they replace or
disambiguate a word.

## Rejected

- A brand accent colour, or coloured status (green "connected", red "lost"): competes with the
  zones, which must own colour.
- Rolling or fading number animations: motion on every value change, and harder to read.
- Glass, gradient and glow treatments from the reference marketing sites: battery and GPU cost, and
  they reduce contrast over video.
- A light theme: out of scope (§7.4).
- Coloured zone backgrounds behind the power number: large saturated area over video, and text on
  it would need a second colour rule.

## Consequences

- `ui-kit` reads `tokens.json` (a Kotlin generator is added to `build-tokens.mjs`); any colour or
  opacity change must keep `check-contrast.mjs` passing.
- The reference sites were not reachable from the build sandbox; the reference notes in
  `design/README.md` rest on prior knowledge. The owner may want to revisit after seeing the
  mock-ups and the overlay on the tablet (roadmap M1).
- Overlay legibility, drag smoothness and the 95% default are to be verified on the Xiaoxin Pad
  over real video in M1.
