# Overdrive design system

The implementer's spec for `android/ui-kit`, the ride app and the companion. Values come from
`design/tokens.json`; this file says how to use them. Units: dp, sp, ms. The mock-ups in
`design/mockups/` are the visual reference; where a mock-up and this file disagree, this file wins.

Contents: 1 Principles as rules · 2 Colour · 3 Type and numbers · 4 Space, shape, icons ·
5 Overlay · 6 Ride app screens · 7 Components · 8 Charts · 9 Motion · 10 Companion ·
11 Copy · 12 Compose notes.

## 1. Principles as rules

The owner's order: concision, clarity, convenience, aesthetics, consistency. Earlier wins.

| Principle | Rule |
|---|---|
| Concision | Every element answers "what does the rider need now?". No decorative icons, no card around a single value, no explanatory text on the ride screen except the paused banner. A value nobody acts on in a ride is not on the overlay. |
| Clarity | Every number has a plain-word label above it (or a unit after it). State is a word ("Paused", "Connection lost"), never colour alone. Zone colour always comes with "Z3" or a chart position. |
| Convenience | Ride-screen targets are 64 dp; elsewhere 48 dp. Every Click action has an on-screen button. The screen shows the state the Click changes (gear or intensity %, interval, paused) so the rider can use the Click without looking down. |
| Aesthetics | Near-black cool neutrals, hairline borders, one type family, generous space, the zone colours as the only colour. Flat fills: no gradients, shadows, glows or blur in anything visible during a ride. |
| Consistency | One token set and one component set for overlay, tablet and phone. The label-over-value pattern, the zone chip and the workout profile look the same everywhere. |

## 2. Colour

Dark theme only. Use roles, never raw hex.

| Role | Token | Use |
|---|---|---|
| Background | `color.neutral.bg` #090C11 | Screen background |
| Surface 1 | `surface1` #11151A | Cards, tile groups, top bars inherit bg |
| Surface 2 | `surface2` #1A1E24 | Secondary buttons, inputs, chips, notices, icon wells |
| Surface 3 | `surface3` #252A30 | Pressed/selected control fill, slider track, free-ride step fill, inactive chart columns |
| Border | `border` #2C3136 | 1 dp hairlines: card edges, dividers, gridlines, overlay edge |
| Border strong | `borderStrong` #41474E | Selected segment ring, pressed button edge, chart baseline |
| Text 1 | `text.primary` #EEF4FC | Values, titles, list titles |
| Text 2 | `text.secondary` #A9AEB6 | Units, subtitles, descriptions |
| Text 3 | `text.tertiary` #81878D | Labels above numbers, axis ticks, empty-value dash, disabled text |
| On primary | `text.onPrimary` #090C11 | Text on the primary button and on zone fills |
| Overlay backing | `overlay.backing` #06080B at 95% | The overlay window fill |

**No other hues.** There is no brand colour and no success/warning/error colour. Good/bad and
attention are carried by: weight (600 vs 400), tone (text 1 vs 3), an icon (`triangle-alert`,
`check`, `info`, `pause`), the inverted chip (`chip.on`: text 1 fill, dark text) and the word.

**Zones** (Coggan, % of FTP, `pct = round(100 × power / FTP)`):

| Zone | Name | Range | Colour | On overlay backing (95%, white video) | On surface1 |
|---|---|---|---|---|---|
| Z1 | Recovery | ≤ 55 | #BBC5D1 | 10.6 | 10.5 |
| Z2 | Endurance | 56–75 | #5BB1FE | 8.1 | 8.0 |
| Z3 | Tempo | 76–90 | #61DF84 | 10.9 | 10.8 |
| Z4 | Threshold | 91–105 | #F2DE3C | 13.4 | 13.3 |
| Z5 | VO2 max | 106–120 | #FFA94F | 9.7 | 9.6 |
| Z6 | Anaerobic | 121–150 | #FF7C7C | 7.4 | 7.4 |
| Z7 | Neuromuscular | > 150 | #EA8DE7 | 8.3 | 8.3 |

Zone colour rules:

- **As text** only for the live power value (overlay and ride screen) and only on `bg`,
  `surface1` or the overlay backing. Nowhere else does text take a zone colour.
- **As fill** for chart marks and the zone chip, at 100%. Text on a zone fill is `onPrimary`.
- **Past/elapsed** marks use the zone colour pre-blended with the surface behind:
  `lerp(surface, zone, 0.35)`, drawn opaque. Never draw with alpha on the overlay; the video
  would show through.
- **Always paired** with a zone number ("Z3") or a position on a chart with an axis.
- Colour-vision check (`node design/tools/check-contrast.mjs`): adjacent zones are 12.9–24.5
  ΔE apart in normal vision and ≥ 8.1 under simulated protanopia, ≥ 9.0 under deuteranopia.
  Z4–Z5 and Z5–Z6 are the closest pairs; the zone number and the step height carry them.
- The zones are lighter than a typical chart palette on purpose: they must read as text at
  ≥ 7:1 over a bright video frame. Do not darken them for charts; one colour per zone.

**Contrast floor (enforced by `check-contrast.mjs`):** numbers on the overlay ≥ 7:1 against
the backing over white video at the default 95% opacity; ≥ 5.5:1 at the 85% minimum. Secondary
text ≥ 4.5:1 on every surface. Re-run the script after any colour or opacity change.

## 3. Type and numbers

One family: **Geist** (SIL OFL), static TTFs 400, 500, 600 in `design/fonts/` (~380 KB). No
italics, no other weights, no monospace face: tabular figures do that job.

| Style | sp / line | Weight | Tracking | Use |
|---|---|---|---|---|
| numHero | 112 / 112 | 600 | −0.03 em | Ride-screen power |
| numL | 56 / 56 | 600 | −0.02 em | Ride-screen tiles, time left in interval |
| numM | 36 / 40 | 600 | −0.02 em | Overlay power, companion headline figures |
| numS | 26 / 32 | 600 | −0.01 em | Overlay metrics, summary tiles, settings preview |
| numXS | 17 / 24 | 500 | 0 | Values in list rows, steppers, tables |
| title | 22 / 28 | 600 | −0.01 em | Screen titles, interval name on ride screen |
| heading | 17 / 24 | 600 | 0 | Top-bar title, card headings |
| body | 15 / 22 | 400 | 0 | Running text, list subtitles |
| bodyStrong | 15 / 22 | 500 | 0 | List titles, button labels |
| label | 13 / 18 | 500 | 0 | Labels above numbers, chips, units |
| caption | 12 / 16 | 500 | +0.01 em | Overlay labels, chart ticks and direct labels |

Sentence case everywhere. No all-caps labels.

**Numbers**

- Every value that can change uses `fontFeatureSettings = "tnum"` (Geist digits are 0.600–0.624
  em wide with it; proportional without). Static numbers in running text may stay proportional.
- A live value sits in a **slot of fixed width** = width of its widest string at its style
  (`overlay.compact.slots` in tokens). Text is start-aligned in the slot. Nothing reflows when a
  digit count changes.
- **Unit** follows the value: `label` style (13 sp/500), `text.secondary`, 4 dp gap, same
  baseline, `lineHeight = 1em` so it never enlarges the line box. Units: W, bpm, rpm, km/h,
  km, kJ, %, kg. Ride-screen hero unit "W" is 22 sp.
- **Empty value** (no sensor data yet, or signal lost): an em dash "—" in `text.tertiary`, in
  the same slot. Never "0", never "--", never blank.
- Formats: power, HR, cadence integers. Speed one decimal. Distance two decimals below 100 km,
  one above. Elapsed time `m:ss` below one hour, `h:mm:ss` from one hour (the slot is sized for
  `h:mm:ss`, so the switch causes no reflow). Interval time left `m:ss`. Percentages integer
  with "%". Negative numbers use the true minus "−" (U+2212), not a hyphen.

## 4. Space, shape, icons

- **Grid:** 4 dp. Spacing scale `s1…s16` = 4, 8, 12, 16, 20, 24, 32, 48, 64. Screen gutter 24 dp
  on the tablet, 16 dp on the phone. Gap between cards 16 dp (tablet) / 16 dp (phone).
- **Radii:** 4 (chart bar ends, zone chip), 8 (buttons, inputs, notices, icon wells), 12
  (cards, overlay), full (chips, switch, slider thumb).
- **Strokes:** hairline 1 dp; focus ring 2 dp `text.primary`, 2 dp offset; chart line 2 dp;
  secondary chart line 1.5 dp; icon stroke 1.5 dp.
- **Elevation:** none. Separation is by surface step and hairline. No shadows anywhere.
- **Icons:** Lucide (ISC), the subset in `design/icons/` with stroke set to 1.5. Convert to
  VectorDrawable for Android. Sizes 20 dp (default), 24 dp (bottom nav), 16 dp (inline in
  chips and status text). Icons take the text colour of their context. An icon is used only
  where it replaces or disambiguates a word (play, pause, volume, device type, alert); never
  as decoration next to a heading.

## 5. Overlay

A `TYPE_APPLICATION_OVERLAY` window owned by the ride foreground service, flags
`FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`, `WRAP_CONTENT` size, gravity top-start with x/y
from saved state. It never takes focus.

### 5.1 Frame

| Property | Value |
|---|---|
| Width | 464 dp, both modes (fixed; mode changes only the height) |
| Height | compact 84 dp; detailed 282 dp in a workout, 159 dp in free ride |
| Backing | `overlay.backing` #06080B at the opacity setting (default 95%, range 85–100%, step 5) |
| Edge | 1 dp `border`, radius 12 dp |
| Padding | 12 dp vertical, 16 dp horizontal |
| Section gap | 12 dp; sections separated by a full-width 1 dp `border` rule |

### 5.2 Main row (both modes)

Five cells on one grid. Each cell is a **label** (caption 12/16, `text.tertiary`) over a
**value**; labels share one line, values share one baseline (align the value row by
`LastBaseline`). Label-to-value gap 2 dp. Cell gap 16 dp. Slot widths:

| Cell | Label | Value style | Slot |
|---|---|---|---|
| Power | "Power" + zone tag "Z3" (caption, `text.secondary`, 6 dp after the label) | numM, zone colour | 92 |
| Heart rate | "Heart" | numS, text 1 | 50 |
| Cadence | "Cadence" | numS, text 1 | 50 |
| Elapsed | "Time" | numS, text 1 | 96 |
| Gear or target | free-ride step: "Gear" → gear number. ERG step: "Target" + intensity "100%" (caption, `text.secondary`) → target watts | numS, text 1 | 80 |

The gear/target cell is the Click's feedback: a gear press changes the number (free ride), an
intensity press changes the percentage and the target (ERG).

### 5.3 Detailed additions

1. **Speed and distance row**, same label/value pattern, numS with unit: Speed in the power
   slot (`34.6 km/h`), Distance spanning the heart-rate and cadence slots (`18.42 km`).
2. **Workout section** (only while a workout plays):
   - Head line: interval name (bodyStrong, text 1) + " 2 of 3" (body 400, text 2) for repeats;
     right-aligned: time left (numS) + unit "left".
   - Next line (label 13): "Next" (text 3) + "Recover · 5:00 · 125 W" (text 2). For the last
     step: "Next  Finish". For a free-ride next step: "Free ride · 5:00".
   - **Mini profile**, 432 × 36 dp (inner width), 4 dp above it. Geometry in §8.1 with: no axis,
     no FTP line, step gap 1 dp, y max 150% FTP, past blended with the backing, now marker
     2 dp `text.primary` full height.

### 5.4 States

| State | What changes |
|---|---|
| Riding | As above |
| No data / signal lost for a device | Its values show "—" in text 3; the label stays. Power lost: power "—", zone tag hidden (the ride auto-pauses, see Paused) |
| Paused (manual or auto) | All values except elapsed turn text 3; power loses its zone colour and tag; the "Time" label becomes "Paused" in text 1. Nothing blinks |
| Ready (before the first pedal stroke) | Elapsed "0:00"; power, HR and cadence show live values or "—" |
| Finished | The overlay closes; the app comes to the front with the summary |

### 5.5 Interaction

- **Tap** (up within 8 dp of down, before 500 ms) toggles compact/detailed. The window resizes
  once, with no animation. Keep the edge the window is snapped to fixed: snapped to the bottom,
  it grows upward.
- **Long press** 500 ms opens the ride app (no haptic needed; the app appearing is the feedback).
- **Drag** after 8 dp of movement; the window follows the finger 1:1 via
  `WindowManager.updateViewLayout`, with no content recomposition during the drag.
- **Snap** on release: horizontally to the nearer side edge, 16 dp margin; vertically clamped
  inside the screen with 16 dp margin (letterbox bars are allowed). Animate x only, 180 ms,
  decelerate. Persist position (as fraction of screen) and mode.
- **Redraw:** only when a displayed value changes, at most once per second (the engine tick).
  The mini profile is drawn into a cached layer; only the now-marker moves, and only when its
  x changes by ≥ 1 px.
- **Blur (optional setting, off by default):** Android 12+ `FLAG_BLUR_BEHIND`,
  `blurBehindRadius` 24 dp. The backing keeps its opacity: blur calms busy video but does not
  darken bright video. Hide the setting where cross-window blur is unsupported.

## 6. Ride app screens (tablet, 1280 × 800 dp landscape)

All screens: top bar 64 dp (24 dp side padding, 1 dp bottom border): title (heading), a state
chip, then right-aligned: device status icons (16 dp, see §7.7), overlay button
(`picture-in-picture-2`), settings button.

### 6.1 Ready (`ride--ready.png`)

- Left column 360 dp, right border hairline: "Ride" label, selectable list of Free ride and
  loaded workouts (two-line rows, selected row `surface2` + check icon), "Open workout file"
  ghost button pinned at the bottom.
- Right: workout title (title) + description (body, text 2); a stat row (Duration, FTP, Hardest
  step, Mode; label over numS); the profile (§8.1, height 168 dp, FTP line, 10-minute ticks);
  a step table (zone chip, name, duration, % FTP, watts); primary **Start** button 64 dp tall,
  240 dp wide, with the hint "Or start pedalling. The ride begins on the first pedal stroke."

### 6.2 Riding (`ride--riding.png`)

Body padding 20 dp top, 24 dp sides; 16 dp gaps.

- **Row 1, 312 dp tall.** Power card 424 dp wide: label "Power" with zone chip + "Tempo · 89%"
  at the right; numHero value in zone colour with unit "W"; bottom row Target / Intensity /
  Lap average (label over numS). Tile group (3 × 2): Heart rate, Cadence, Time, Speed,
  Distance, Average power; label over numL + unit.
- **Row 2, workout card:** "Interval" label over interval name (title) + "2 of 3"; "Next" block
  right-aligned; "Left in interval" over numL. Below: the profile, height 156 dp, with FTP line,
  minute ticks every 10, past blended with `surface1`, power trace (§8.2), now marker.
  In free ride without a workout, this card shows the gear instead: "Gear" over numL "12 / 24",
  and the profile is omitted.
- **Control bar, 96 dp, top hairline.** Left group (64 dp buttons): Previous, −, intensity
  readout (numS "100%" over caption "Intensity"; in free ride "12" over "Gear"), +, Next.
  Right: Media group (ghost icon buttons play/pause, volume down, volume up, 56 dp, with caption
  "Media"), a 40 dp hairline separator, **Pause** (secondary, pause icon).
  These map 1:1 to the Click actions; volume buttons repeat every 150 ms while held.

### 6.3 Paused (`ride--paused.png`)

Same layout. Chip becomes the inverted "Paused" chip with pause icon. A 48 dp banner
(`surface2`, radius 8) under the top bar: pause icon, "Paused at 32:17" (bodyStrong), "Pedal or
press Resume to continue. Target power ramps back in over 3 s." (text 2). All live values except
Time turn text 3; power loses its zone colour and chip. Control bar: **Finish ride**
(secondary, flag icon) left, **Resume** (primary, 200 dp) right. Finish asks for confirmation in
a bottom sheet ("Finish and save this ride?" Finish / Keep riding).

### 6.4 Finished (`ride--finished.png`)

Top bar title "Ride saved". Left: workout name (title) + date and clock range; tile group 4 × 2
(Duration, Moving time, Average power, Normalised power, Average heart rate, Max heart rate,
Work, Load; label over numS); "Time in power zones" card (§8.3); bottom row: **Share FIT file**
(secondary), sync line "Saved on this tablet. It uploads once sync is set up." (label, text 2),
**Done** (primary). Right, 440 dp: laps table (Lap, name, Time, Watts, HR), 36 dp rows,
hairline separators, numbers right-aligned and tabular.

### 6.5 Devices (`devices--pairing.png`)

Two columns. Left: hint "Paired devices connect on their own when the app starts." and the
device rows (§7.7) for Trainer, Heart rate, Click. Right: "Click buttons" card with the
press/hold mapping table and the ERG note, and "Change button mapping" (secondary).

### 6.6 Settings (`settings--settings.png`)

Two columns of grouped cards; group label (label, text 3) above each card; setting rows 64 dp
(title bodyStrong + one-line explanation in label/text 2, control at the right).

- **Overlay:** live preview of the compact overlay on a bright frame (200 dp), Mode
  (segmented Compact / Detailed), Opacity (slider 85–100%, value at the right), Blur the video
  behind (switch, "Uses more battery"), Position (Reset, ghost).
- **Rider and gears:** FTP (stepper, ±5 W, long-press repeats), Gears (stepper, 10–30), Step per
  gear (stepper, ±0.1%, 0.5–2.0%; caption says the total stays within 15%).
- **Physics for speed and distance:** mass (kg, ±1), CdA (m², ±0.01), Crr (±0.0005).
- Later groups (Click mapping, Sync URL and token, About) use the same row.

## 7. Components

### 7.1 Buttons

| Variant | Fill | Border | Text | Use |
|---|---|---|---|---|
| Primary | text 1 | — | onPrimary | The one main action of a screen (Start, Resume, Done) |
| Secondary | surface2 | border | text 1 | Everything else |
| Ghost | none | — | text 2 | Tertiary actions, icon-only media buttons |

Height 48 dp (ride screens 64 dp), padding 0 × 20 dp, radius 8, label bodyStrong, optional 20
dp leading icon with 8 dp gap. States: pressed = surface3 fill + borderStrong edge (90 ms);
disabled = surface1 fill, text 3; focused = 2 dp text-1 ring, 2 dp offset. No ripple.

### 7.2 Chips

28 dp tall, full radius, padding 0 × 12, label style, surface2 fill, text 2. Optional 6 dp dot
or 16 dp icon, 6 dp gap. **On** chip (state that needs attention: Paused): text-1 fill,
onPrimary text. Chips are read-only state; they are not buttons.

**Zone chip:** 20 dp tall, min 28 dp wide, radius 4, zone fill, "Z4" caption 600 tabular in
onPrimary.

### 7.3 Stat tile and tile group

Tile: label (label, text 3) over value + unit; optional sub-line (label, text 2); padding 16 ×
20 (20 × 24 on the ride screen). A **tile group** is one `surface1` container (radius 12, 1 dp
border) split by 1 dp `border` lines; tiles have no own border or fill. Optional right-aligned
item in the label row (e.g. "No signal", "Z4"). Value style by context: numL ride screen, numS
summaries and companion.

### 7.4 Card

surface1, 1 dp border, radius 12, padding 16 (phone) / 16–24 (tablet). Use only to group
several related elements; a single value is a tile, not a card.

### 7.5 List row

Two-line 64 dp, single-line 56 dp; padding 10 × 16; 16 dp gap. Title bodyStrong, subtitle label
text 2. Trailing: value numXS (tabular, right-aligned) over caption text 3, or an icon/control.
Rows in a card are separated by 1 dp `border`, full width. Selected row: surface2, radius 8,
no dividers around it (selection lists use no dividers).

### 7.6 Notice

surface2, radius 8, padding 12 × 16, 12 dp gap: 20 dp icon (text 1) · title (bodyStrong) over
text (label, text 2) · optional secondary button (40 dp). Used for the Click unlock notice and
similar recoverable problems. No colour, no border.

### 7.7 Device row

72 dp min, padding 14 × 20. Icon well 40 dp (surface2, radius 8) with the device icon
(`bike` trainer, `heart` strap, `gamepad-2` Click). Title (bodyStrong): device role. Subtitle
(label, text 2): model and detail.

| State | Icon well | Subtitle | Trailing |
|---|---|---|---|
| Not paired | icon text 3 | "Not paired" | **Pair** (secondary) |
| Searching | icon text 1 | "Searching for heart-rate straps…" | 20 dp spinner ring (2 dp, track surface3, arc text 2, 1000 ms per turn) |
| Connected | icon text 1 | "Coospo HR7 · battery 80%" | `check` 16 dp + "Connected" (label, text 2) |
| Connection lost | icon text 1 | `triangle-alert` 16 dp + "Connection lost · retrying in 8 s" in text 1 | **Retry now** (secondary) |
| Click locked | icon text 1 | "Zwift Click v2 · connected" | "No presses" (label, text 1) + the notice below the row: title "Click is locked", text "It is connected but sends no button presses. Unlock it once in the Zwift app, then check again. You can ride without it; every action is on screen.", button **Check again** |

**Top-bar device status:** three 16 dp icons in the same order, text 2 when connected, text 3
when not paired, text 1 + `triangle-alert` when lost or locked. Tapping opens Devices.

### 7.8 Inputs

- **Segmented control:** surface2, 1 dp border, radius 8, 2 dp inner padding; segments 40 dp,
  label style; selected = surface3 + 1 dp borderStrong inset ring, text 1; others text 2.
- **Stepper:** 48 dp, surface2, border, radius 8; − and + are 48 dp icon buttons (text 2); value
  numXS tabular, min 96 dp, centred. Long press repeats every 150 ms.
- **Switch:** 44 × 26, full radius. Off: surface3 track, borderStrong edge, 18 dp thumb text 3.
  On: text-1 track, onPrimary thumb. Thumb moves in 150 ms standard.
- **Slider:** 4 dp track surface3, filled part text 1, 20 dp thumb text 1, discrete steps; value
  shown as numXS at the right, not in a bubble.

### 7.9 Bottom navigation (companion)

80 dp including 16 dp gesture inset, top hairline, 5 equal items: 24 dp icon over caption.
Active text 1, inactive text 3. No pill, no indicator bar.

## 8. Charts

General: no chart junk. Hairline gridlines in `border`, only where a value must be read. Tick
labels caption text 3, tabular. Direct labels on line ends; a legend (line keys in the card
header) only when line ends collide. Text never takes a series colour. Series colours are
neutrals (text 1, text 3, surface3) except where the data is power, which uses zone colours.
No tooltips on the ride screen (no hover); the companion may show a value on press.

### 8.1 Workout profile

- x = time, the whole workout fits the width; y = % FTP, 0 to 150% (values above clip at the
  top), baseline at the bottom.
- Steady step: a rectangle from the baseline to its target, zone colour. Ramp: a trapezoid
  split at zone boundaries (boundary at the half percent, e.g. 55.5%) into pieces, each in its
  own zone colour. Free-ride step: rectangle to 50% in surface3. Repeats are unrolled.
- 1 dp gap between steps (2 dp on charts wider than 600 dp) in the surface colour, cut from the
  right of each step. No border on marks.
- Past: blended with the surface (§2). Now marker: 2 dp text-1 rectangle, full plot height.
- Tablet: FTP line (1 dp `border`) at 100% with "FTP" caption right-aligned above it; minute
  ticks every 10 min under the baseline ("0", "10", …), 20 dp axis band. Overlay: no line,
  no ticks.

### 8.2 Power trace (ride screen only)

The rider's actual power over the past part of the profile: 1.5 dp polyline, text 1, round
joins, one point per 10 s average (smooths ERG noise; updates once per 10 s). Not on the
overlay.

### 8.3 Time in power zones

Seven rows, 28 dp: "Z1" (label, text 2, tabular) · zone name · bar · time (label, text 1,
right-aligned). Bar 12 dp thick, zone fill, starts at a shared left edge, square at the start,
4 dp radius at the end, length proportional to time (longest = full track). A zone with zero
time shows a 1 dp stub. Times sum to the moving time.

### 8.4 Companion charts

- **Load (fitness and fatigue):** two lines over 90 days, y gridlines every 10. Fitness 2 dp
  text 1, fatigue 1.5 dp text 3, end dots 8 dp with 2 dp bg ring, direct labels to the right.
  Three date ticks (start, middle, "Today").
- **Form:** one 2 dp line, gridlines at −10, 0, 10, end label = today's value with true minus.
- **Weekly load:** 12 columns, 20 dp wide, top corners 4 dp; past weeks surface3, current week
  text 1; labels: the current week's value and the highest week's value only; baseline 1 dp
  borderStrong; "12 weeks ago" / "This week" under the ends.
- **Power curve:** x is log duration (5 s, 1 min, 5 min, 20 min, 1 h ticks), y watts; 90 days
  2 dp text 1, best ever 1.5 dp text 3; legend keys in the card header because the lines
  converge at long durations.
- **Wellness sparkline:** 120 × 32, 28 days, the usual range as a surface3 band, 1.5 dp text 2
  line, today as a 6 dp text-1 dot. Value and "usual 50–56" are text beside it.

## 9. Motion

| What | Duration | Easing |
|---|---|---|
| Value change (any number) | 0 (instant swap; no rolling digits, no fade) | — |
| Overlay mode toggle | 0 (one resize) | — |
| Overlay snap after drag | 180 ms, x only | decelerate `(0, 0, 0, 1)` |
| Button press feedback | 90 ms | standard `(0.2, 0, 0, 1)` |
| Switch thumb, segmented selection | 150 ms | standard |
| Screen change | 200 ms crossfade | standard |
| Bottom sheet | in 200 ms decelerate, out 150 ms accelerate `(0.3, 0, 1, 1)` | |
| Device search spinner | 1000 ms per turn, linear, only while scanning (max 30 s) | linear |

Nothing pulses, blinks or loops on the overlay or the riding screen. Respect the system
"remove animations" setting by making all of the above instant.

## 10. Companion (phone, 412 × 915 dp)

Same tokens and components. Status bar inset, then screen padding 12 dp top, 16 dp sides, 16 dp
gaps; header = title (title) + optional subtitle (label, text 2) with 4 dp extra side inset.

- **Today:** Recovery card (label "Recovery", a 3-segment meter 96 × 6 dp: segments filled text 1
  for Ready 3, Caution 2, Rest 1, the rest surface3; the state word in title; one-line reason;
  three key/value rows: metric, value, "usual …" in text 3). Planned card (time, name, duration
  · load · hardest step, mini profile 44 dp). Morning note card (body text).
- **Fitness:** tile group Fitness / Fatigue / Form with sub-lines; Load, Form and Weekly load
  cards (§8.4).
- **Power:** FTP card (current vs estimated; "Zones change only when you confirm"; secondary
  button "Use 256 W"); power curve card; tile group of best 5 s, 1 min, 5 min, 20 min.
- **Wellness:** one card of rows: label, value + unit, "usual …", sparkline at the right.
- **Rides:** month label (label, text 3) with a column hint at the right ("Load · avg power"),
  then a card of two-line rows: name; "Wed 18 · 1:05:00"; trailing load (numXS) over average
  power (caption, text 3).

The recovery state uses no colour: "Ready", "Caution" and "Rest" differ by word and meter fill.

## 11. Copy

- Labels are plain words: Power, Heart, Cadence, Time, Gear, Target, Speed, Distance (the
  overlay uses "Heart" for width; the ride screen uses "Heart rate").
- Sentence case. No trailing colons. No exclamation marks.
- Buttons say what happens: Start, Pause, Resume, Finish ride, Pair, Retry now, Check again,
  Share FIT file, Done.
- Problems say what happened and what to do, in two short sentences, without apology.
- "·" (middle dot with spaces) separates facts in one line; "–" (en dash) for ranges; "×" for
  repeats ("3×10").

## 12. Compose implementation notes

- `FontFamily(Font(R.font.geist_regular, W400), Font(R.font.geist_medium, W500),
  Font(R.font.geist_semibold, W600))`; numeric styles add `fontFeatureSettings = "tnum"`.
  Set `PlatformTextStyle(includeFontPadding = false)` and `LineHeightStyle(Center, Trim.None)`
  so the line heights above are exact.
- Generate a `Tokens.kt` from `tokens.json` (extend `build-tokens.mjs`); do not hand-copy hex.
- Overlay: one `ComposeView` in the WindowManager window, with its own lifecycle, saved-state
  and view-model-store owners from the service. Keep each displayed value a separate `State`
  so a heart-rate change recomposes only its cell. Use fixed-width `Box`es for slots
  (`Modifier.width(slot.dp)`), not `wrapContentWidth`. Align the main row with
  `Modifier.alignByBaseline()`.
- Draw charts with `Canvas` + `drawWithCache`; cache the profile path per workout and width, and
  redraw only the now-marker. Pre-blend past colours with `lerp(surface, zone, 0.35)`.
- Window drag: update `LayoutParams.x/y` on the main thread from raw touch events; do not route
  drag positions through Compose state.
- Ride screen buttons fire the same actions as the Click gesture layer; one action dispatcher.
