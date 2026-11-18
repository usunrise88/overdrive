# Overdrive design system

The implementer's spec for `android/ui-kit`, the ride app and the companion. Values come from
`design/tokens.json`; this file says how to use them. Units: dp, sp, ms. The mock-ups in
`design/mockups/` are the visual reference; where a mock-up and this file disagree, this file wins.

Contents: 1 Principles as rules · 2 Colour · 3 Type and numbers · 4 Space, shape, icons ·
5 Overlay · 6 Ride app screens · 7 Components · 8 Charts · 9 Motion · 10 Companion ·
11 Copy · 12 Compose notes · 13 Companion components.

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
  as decoration next to a heading. `bluetooth` and `bluetooth-off` (M3-2) lead the Bluetooth Notices and the **Pair a trainer**
  button.

## 5. Overlay

A `TYPE_APPLICATION_OVERLAY` window owned by the ride foreground service, flags
`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN`, `WRAP_CONTENT` size, gravity
top-start with x/y from saved state. It never takes focus (ADR 0006).

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
- **Devices on the Ready screen (stage M3-2).** While **Start** is off, one sentence in body, text 2, sits beside it and says
  why: "No trainer is paired." (with a secondary **Pair a trainer** button with the `bluetooth` icon, which opens Devices),
  "Waiting for Bluetooth.", or "Waiting for the trainer to connect."; the chip beside the title says "No trainer" or "Waiting
  for trainer". **Nothing simulated ever takes a missing trainer's place.** Bluetooth that cannot be used is one Notice (§7.6)
  at the top of the right column, above the overlay-permission Notice: "Allow nearby devices" with **Allow** (**Open settings**
  once refused), "Bluetooth is off" with **Turn on**, or "No Bluetooth on this tablet"; it is re-read on every resume and goes
  by itself.
- **Rides on disk (stage M2b-2).** At the top of the right column, above the title, in this order, each only when it
  applies: a Notice for an interrupted ride ("An interrupted ride was found", the start and length of the ride, **Save** and
  **Discard**; while it is stored "Saving the ride…" with no buttons; after a failure "The ride could not be saved" with
  **Retry** and **Discard**), the overlay permission Notice, a workout-file error Notice, and the **Last ride** card: surface 1,
  radius 12, padding 16 × 12, "Last ride" (caption, text 3) over "title · duration" (bodyStrong), and two 40 dp buttons,
  **Share** (`share-2`) and **Save to folder** (`folder-open`); a line of label text 2 under the title says what the last
  share or save did. **Discard** opens the confirmation sheet of §6.3 ("Discard this ride?", **Discard** secondary left,
  **Keep it** primary right). The mock-up PNG predates these rows.
- **Planned workouts (stage P2-2b, spec §5.7, ADR 0017).** Only when the tablet has a service address and token (and the service has
  a plan source); otherwise nothing is drawn and nothing is said. At the top of the left column, above "Ride", scrolling with it:
  a **Planned** label (label, text 3) with a 48 dp ghost refresh icon button (`refresh-cw`, "Refresh planned workouts", off while
  it asks); one line of state under it (label, text 2: "Updated 07:30", "Updating…"; with a 16 dp `triangle-alert` and text 1 when
  there is a problem: "Out of date · updated 17 Nov 21:15" over the reason in text 2 (plain words and a technical hint in brackets: "The system blocks this app's network access
  (SocketException · EACCES)"), or "The service did
  not accept the token. Check the token in Settings"); today's workouts as **value rows** (§7.5), or "Nothing planned today"
  (body, text 2); then **This week** as a disclosure row with its count, collapsed, and opened the days under it ("Tomorrow",
  "Sun 22 Nov": label, text 3) each with its value rows. A value row shows the name, one line of description, the length ("65 min")
  and the intensity factor ("IF 0.76", caption, text 3) on the right; the chosen one is surface 2 with a check, like the Free ride
  and local workout rows. A ridden workout says "✓ Done 18:04 · description" (a 16 dp `check` before the subtitle); one the player
  cannot run is dimmed, takes no press or focus and says why ("Longer than 12 hours", with a `triangle-alert`).

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
- **When the ride is not being written to disk** (stage M2b-2), one Notice with `triangle-alert` sits at the top of the
  body, above row 1 (below the paused banner): "This ride is not being saved" over "Keep riding. Overdrive tries to save it
  again when you finish." No button and no cause: the ride is unaffected.

### 6.3 Paused (`ride--paused.png`)

Same layout. Chip becomes the inverted "Paused" chip with pause icon. A 48 dp banner
(`surface2`, radius 8) under the top bar: pause icon, "Paused at 32:17" (bodyStrong), "Pedal or
press Resume to continue. Target power ramps back in over 3 s." (text 2). All live values except
Time turn text 3; power loses its zone colour and chip. Control bar: **Finish ride**
(secondary, flag icon) left, **Resume** (primary, 200 dp) right. Finish asks for confirmation in
a bottom sheet ("Finish and save this ride?" Finish / Keep riding).

### 6.4 Finished (`ride--finished.png`)

Top bar title "Ride saved" (while the files are written or if that failed, "Ride finished"). Left: workout name (title) + date and clock range; tile group 4 × 2
(Duration, Moving time, Average power, Normalised power, Average heart rate, Max heart rate,
Work, Load; label over numS); "Time in power zones" card (§8.3); at the bottom, a status line over a button row.
Right, 440 dp: laps table (Lap, name, Time, Watts, HR), 36 dp rows,
hairline separators, numbers right-aligned and tabular.

**Bottom, by state (stage M2b-2).** Status line (label, text 2, with a 16 dp icon), then the 64 dp button row with **Done**
(primary) right-aligned:

| State | Status line | Buttons |
|---|---|---|
| Saving | spinner ring (§7.7) + "Saving the ride…" | **Share FIT file** and **Save to folder** (secondary, disabled) |
| Saved | `check` + "Saved on this tablet · 2026-11-18 18-04 Sweet spot 3×10.fit · 312 kB" | **Share FIT file** (`share-2`), **Save to folder** (`folder-open`) |
| Failed | `triangle-alert` + what happened and what to do ("The ride could not be saved. Free up some space on the tablet and try again.") | **Retry** (`refresh-cw`) when retrying can help |

A second line of label text under the status says what the last share or save did ("Saved to the folder you chose."). **Done**
on a failed save asks "Leave without saving this ride?" (**Leave** / **Stay**) first. The mock-up PNG shows the Saved state
as first drawn, with the sync line of Phase 2.

### 6.5 Devices (`devices--pairing.png`)

Two columns. Left: hint "Paired devices connect on their own when the app starts." and the
device rows (§7.7) for Trainer, Heart rate, Click. Right: "Click buttons" card with the
press/hold mapping table with the Click's own name of each role's button under the role, the ERG note, and under the card
**Learn buttons** (primary) and **Reset to default** (ghost; off while the mapping is the default).

**Pairing and Bluetooth (stage M3-2).** Under the hint, in this order, **one** of the following blocks applies:

- **Bluetooth cannot be used:** one Notice (§7.6) with its one action, above the rows; the rows say it too ("D500 · Bluetooth
  is off") and **Pair** on a row does the notice's action; no scan panel.
- **A ride is live:** a line (label, text 3) under the rows, "Devices cannot be changed during a ride."; no **Forget**, no scan.
- **Otherwise the scan panel**, under the rows card: a **Scan for devices** primary button (**Scan again**, secondary, once a
  scan has run); while scanning, a secondary **Stop**, the spinner ring and "Looking for devices…". Results are one card of
  selection rows (§7.5, no dividers inside a section): a section label ("Trainers", "Heart-rate straps", label, text 3), then
  the advertised name (bodyStrong) over the signal as a word and a number ("Strong · −58 dBm", "Good", "Weak": thresholds
  −60 and −75 dBm; "Paired · " first on the paired one, which carries the selected row's `surface2` fill and check). A tap
  pairs that device and replaces the old one of its role. Nothing found, or a refused scan, is a Notice without an action
  ("Nothing found: switch the trainer or strap on and wake it ... then scan again" or "Scanning was refused ... wait half a
  minute").
- **Simulated devices (Developer setting):** the hint says "These are simulated devices. Switch to Bluetooth under Settings,
  Developer, Devices."; no pairing, no **Forget**.

A row that has a paired device ends with a ghost **Forget** (40 dp) after its status.

**The Click (stage M4-2).** The mock-up `design/mockups/devices.html` is the M1 drawing and still shows the disabled "Change button
mapping"; **Learn buttons** replaced it. The Click row is real. Scan results gain a section "Handlebar controllers" (label, text 3) whose
rows read "Zwift Click (right)" over the signal. **Learn buttons** is enabled only with the Click connected and no ride live;
otherwise it is off and one line under the buttons (label, text 3) says why: "Not during a ride.", "Connect the Click to learn
its buttons." or, with simulated devices, "Learning needs the real Click: switch to Bluetooth under Settings, Developer."

### 6.5b Learn buttons (`learn--*.png`)

A full screen with the back top bar ("Learn buttons"), two columns like Devices, **no confirmation dialogs** and no decoration.
Left: the step (label, text 3, "Step 3 of 5"), the question (title, "Press the button you want as Button 3"), what the role does
(label, text 2, "Play or pause · hold for pause or resume ride"), the instruction (label, text 3, "Press and release one button,
then wait."), then when the last press was refused one Notice (§7.6, no action): "One button at a time" / "Two buttons were
pressed together. Press just the one you want as Button 3." or "That button is taken" / "It is already Plus. Press a different
one for Button 3."; then **Cancel** (secondary) and **Reset to default** (ghost). Right: one card of the five roles (two-line
rows, 8 dp padding), each with a marker (a `check` once set, a 10 dp text-1 dot for the one being asked and a `surface2` fill on
the row, an empty 10 dp `surface3` dot for the rest), the role (bodyStrong) over what it does (label, text 2), and at the right
the Click's own name of the button (bodyStrong: `Y`, `Z`, `A`, `B`, `Plus`), "Press now" for the one being asked and "—" for the
rest. When all five are set the left column says "All five set", "The Click has its new buttons" and "They are saved. The Click
does nothing until you press Done, so a press cannot move the ride.", and **Cancel** becomes **Done** (primary). If the Click
drops meanwhile the instruction says "The Click is not connected. Reconnect it to go on." in text 1. The Click's own presses
are the only input; nothing animates.

### 6.6 Settings (`settings--settings.png`)

Two columns of grouped cards; group label (label, text 3) above each card; setting rows 64 dp
(title bodyStrong + one-line explanation in label/text 2, control at the right).

- **Overlay:** live preview of the compact overlay on a bright frame (200 dp), Mode
  (segmented Compact / Detailed), Opacity (slider 85–100%, value at the right), Blur the video
  behind (switch, "Uses more battery"), Position (Reset, ghost).
- **Rider and gears:** FTP (stepper, ±5 W, long-press repeats), Gears (stepper, 10–30), Step per
  gear (stepper, ±0.1%, 0.5–2.0%; caption says the total stays within 15%).
- **Physics for speed and distance:** mass (kg, ±1), CdA (m², ±0.01), Crr (±0.0005).
- **The rider's profile on these four** (FTP, mass, CdA, Crr; Phase 2 fix, ADR 0021): a row shows the value in force, and when it
  is not the tablet's own a **source marker** sits left of the stepper: **From service** (the profile on the service) or **Not sent
  yet** (changed here, the service has not taken it). Label style, text 3, one line, no icon and no colour (the zone colours are the
  only accent, §2); nothing at all for the tablet's own value. When the last request for the profile failed, one plain line
  ("Profile: Service unreachable · showing the last known profile") replaces the FTP row's note. No other screen marks it: the
  Ready detail and the ride show the number in force.
- **Developer** (stage M3-2), after the overlay card in the left column: **Devices** (segmented **Bluetooth** / **Simulated**;
  Bluetooth is the default and a ride never falls back to the simulation), **Record Bluetooth sessions** (switch, on in the
  early builds), **Share Bluetooth logs** (the row says "4 files · 120 KB" or "No logs yet", or what the last share could not
  do; a secondary 40 dp **Share** button with `share-2`, off while there are no logs), then the simulated-ride rows (Demo speed,
  Scripted dropouts, Simulated Click). The logs hold heart-rate values: they leave the tablet only through **Share**.
- **Sync** (stage M4-2), right column under the physics card: **Service address** and **Token** are *field rows* (title and note
  on top, the 48 dp field under them, surface2 with the border, radius 8; a refused address shows its reason under the field in
  label, text 1; a plain `http://` address shows "Not encrypted: the token is sent as plain text" there). The notes say "Where
  finished rides are sent" and "Sent only to this address" (stage P2-1b, which replaced "Not used yet"); the token shows as
  dots, with a ghost **Show** / **Hide**; **Waiting to upload** is a setting row whose value (numXS, right) is "3 rides" and
  whose note is one line in plain words about the last problem (absent when all is sent); **Last upload** is a setting row
  whose value (numXS, right) is "Today 18:04", "17 Nov 23:59" or "Never", followed by a secondary 40 dp **Send now** (off while
  nothing waits or an upload is under way).
- **Developer · Click** (stage M4-2), last in the right column: setting rows only (switches, segmented controls, steppers with
  "ms") for the Click's connection options and the two gesture timings, and a final **Reset** (secondary, off at the defaults).
- Later groups (About) use the same row.

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

**Value row** (`ValueRow`, stage P2-2b): the two-line list row with a value on the right, numXS tabular and right-aligned over a
caption in text 3 ("65 min" over "IF 0.76"); a 16 dp icon may go before the subtitle (the check of "Done", the alert of a row that
cannot be used); the selected row is surface 2 with a check after the value; a disabled row has a text-3 title, no press and no
focus. **Disclosure row** (`DisclosureRow`): single-line, 56 dp, the title in bodyStrong, a detail in label text 2 ("3 workouts")
and a 20 dp `chevron-right` (`chevron-down` when open); its state is also said to accessibility. **Focus ring**: a row that has
focus (keyboard, d-pad; Compose gives focus to clickable things only after the first hardware key) draws a 2 dp text-1 ring
inside its edge, the focus state of §7.1 for rows; these two rows are the first components to draw it, the others follow when
they are next touched.

### 7.6 Notice

surface2, radius 8, padding 12 × 16, 12 dp gap: 20 dp icon (text 1) · title (bodyStrong) over
text (label, text 2) · up to two 40 dp buttons side by side, 8 dp apart (the first is the main answer: **Save** before
**Discard**). Used for the Click unlock notice and similar recoverable problems, and for an interrupted ride that was
found. No colour, no border.

### 7.7 Device row

72 dp min, padding 14 × 20. Icon well 40 dp (surface2, radius 8) with the device icon
(`bike` trainer, `heart` strap, `gamepad-2` Click). Title (bodyStrong): device role. Subtitle
(label, text 2): model and detail.

| State | Icon well | Subtitle | Trailing |
|---|---|---|---|
| Not paired | icon text 3 | "Not paired" | **Pair** (secondary) |
| Searching | icon text 1 | "Searching for heart-rate straps…" | 20 dp spinner ring (2 dp, track surface3, arc text 2, 1000 ms per turn) |
| Connected | icon text 1 | "Coospo HR7 · battery 80%" | `check` 16 dp + "Connected" (label, text 2) |
| Connection lost | icon text 1 | `triangle-alert` 16 dp + "Connection lost · retrying in 8 s" ("Not found · …", "Could not connect · …", "Not a trainer · check the pairing") in text 1; the seconds count down once a second, only while it waits | **Retry now** (secondary) |
| Blocked (stage M3-2) | icon text 1 | `triangle-alert` 16 dp + "D500 · Bluetooth is off" or "· needs Nearby devices" in text 1 | none: the Notice above the rows holds the one action |
| Click connected (M4-2) | icon text 1 | "Zwift Click (right) · battery 87%" (the battery once the Click has said) | `check` 16 dp + "Connected" |
| Click checking (M4-2) | icon text 1 | "Zwift Click (right) · press any button", **only with Settings, Developer · Click, Silence means locked on** (off by default: a real Click is silent until a button is pressed, so an answered one is Connected at once) | spinner ring |
| Click locked | icon text 1 | "Zwift Click (right) · locked" | "No presses" (label, text 1) + the notice below the row, in **two wordings by the sign** (stage M4-2, spec §4.7): the Zwift service is hidden: title "Click is locked", text "Zwift has it locked. Unlock it through Zwift once; the ride continues without it."; it answers and says nothing (**only with Silence means locked on**): title "Click sends no presses", text "Press any button on it. If that does nothing, unlock it through Zwift once; the ride continues without it."; button **Check again** |

A row that has a paired device (stage M3-2) ends with a ghost **Forget** button, 40 dp, after the status. Searching reads
"Connecting to <name>…". The Click's lost and blocked rows read like the others' ("Not found · retrying in 8 s", "Connection
lost · …", "Could not connect · …", "Zwift Click (right) · Bluetooth is off").

**Top-bar device status:** three 16 dp icons in the same order, text 2 when connected, text 3
when not paired, text 1 + `triangle-alert` when lost, blocked or locked. Tapping opens Devices.

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

Specified chart by chart in `design/companion.md` §6 (stage P3 design pass), which supersedes the first sketch that stood here.
In short: every chart has a **readout row** above the plot that doubles as its legend and shows the selected point (tap, or hold
and slide; at rest the latest point); no end labels and no hover; one selection per screen where charts share an x axis; a
**Values** sheet as the table twin; neutral series (text 1 main, text 3 or text 2 second), zone colours only for power data;
baselines and reference ranges as flat surface-2/3 bands named in words.

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

The companion's screens, states, charts, copy and data needs are in **`design/companion.md`**; mock-ups in
`design/mockups/companion/`. Same tokens and components as here. Status bar inset, then screen padding 12 dp top, 16 dp sides,
16 dp gaps; header = title (title) + one subtitle line (label, text 2) with 4 dp extra side inset. Five tabs: Today, Fitness,
Power, Wellness, Rides; Settings is pushed from Today. The recovery state uses no colour: "Ready", "Caution" and "Rest" differ by
word, icon (`check`, `triangle-alert`, `pause`) and meter fill.

## 11. Copy

- Labels are plain words: Power, Heart, Cadence, Time, Gear, Target, Speed, Distance (the
  overlay uses "Heart" for width; the ride screen uses "Heart rate").
- Sentence case. No trailing colons. No exclamation marks.
- Buttons say what happens: Start, Pause, Resume, Finish ride, Pair, Retry now, Check again,
  Share FIT file, Done; for Bluetooth: Scan for devices, Scan again, Stop, Forget, Pair a trainer, Allow, Open settings,
  Turn on, Share.
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

## 13. Companion components (stage P3 design pass)

New `ui-kit` components for the phone; their screens are in `design/companion.md`. All are built from the tokens above.

- **BottomNav**: §7.9, five items (`house` Today, `activity` Fitness, `zap` Power, `moon` Wellness, `list` Rides). Tapping the
  active item scrolls its screen to the top.
- **ScreenHeader**: title (title) over one subtitle line (label, text 2): the date and/or "Updated 07:40"; "Updating…" with a 16 dp
  spinner ring while a refresh runs; optional 48 dp ghost icon button at the right.
- **StatusLine**: the problem line under a header: surface 2, radius 8, padding 12 × 16, 20 dp `triangle-alert`, first line
  bodyStrong text 1 ("Offline · updated 06:10"), cause in label text 2, optional 40 dp secondary button. A Notice without a title
  icon choice: always the alert, always about data being old.
- **RangeSelector**: a full-width `SegmentedControl` with three equal segments, 44 dp tall (48 dp with its frame).
- **RecoveryCard**: card with "Recovery · this morning" (label, text 3) and the **RecoveryMeter** (96 × 6 dp, three segments
  4 dp apart, radius 3, filled text 1 from the left: Ready 3, Caution 2, Rest 1, the rest surface 3; hidden from accessibility);
  the state line (24 dp icon + the word in numM); the reason (body, text 2); and **FactRows**: 56 dp, name (body) left, value
  (numXS + unit) over caption right; outside the usual range the caption is text 1 and a 16 dp `triangle-alert` leads the name.
- **ChartCard**: card, padding 16 × 16 × 12, gap 8: heading (label, text 3) with an optional ghost **Values** (label, text 2,
  48 dp hit), the **ChartReadout**, the plot, an optional note (label, text 2).
- **ChartReadout**: one wrapping row, label style, 24 dp min: the selected x (text 1, tabular), then per series a key (12 × 2
  line, 8 dp dot or 12 × 8 band in the mark's colour, 6 dp gap), its name (text 2) and value (text 1, 600, tabular); 14 dp
  between entries; a 24 dp **Today** pill (surface 2, full radius, label text 2, 48 dp hit) at the right while a past point is
  selected. It is the chart's legend.
- **LineChart**, **ColumnChart**, **SleepTimingChart**, **RideChart**: Canvas charts with a shared scale and selection model
  (geometry in `design/companion.md` §6): 32 dp y gutter (40 dp on Wellness), 20 dp x band, hairline gridlines, `borderStrong`
  baseline, crosshair 1 dp text 2 only while a past point is selected, 8 dp selection dots with a 2 dp surface-1 ring, 6 dp small
  dots, flat surface-2/3 bands.
- **ValuesSheet**: bottom sheet (system motion) with a lazy table: first column label text 1, numbers right-aligned tabular,
  40 dp rows, header in caption text 3.
- **ProgressBar**: 4 dp track surface 3, fill text 1, radius 2; determinate only (first read, learning nights).

### 13.1 Settled in stage P3-4a

What the implementation added or fixed to build the components above (`android/ui-kit`, `docs/adr/0024-companion-app.md`):

- **BottomSheet**: the sheet of §9 as one component (70% scrim, surface 1 panel, 12 dp top radius, grab handle, 200 ms in and 150 ms out, instant
  without animations). `ValuesSheet` and the Set FTP sheet are made of it.
- **StackedNotice**: a Notice whose buttons sit under the text (a phone is too narrow for them beside it); used for the FTP suggestion.
- **LoadingBlock, ErrorBlock, EmptyBlock, PartialNote**: the states of companion.md §5 as components. Loading and error are placed at 40% of the screen's
  height; the empty block is a heading and one sentence; none has an illustration.
- **SegmentedControl** takes `segmentHeight` (44 dp on the phone, 40 dp on the tablet) and `equalWidth`; **Spinner** takes a `diameter` (16 dp in the header).
- **ChartReadout**: the text of the readout is the polite live region; the **Today** pill is a separate control (24 dp visual, 48 dp hit area through a layout
  that reports 24 dp) described to TalkBack as "Back to today". Two charts that share a selection each carry a pill.
- **Charts**: the y gutter is 32 dp, 40 dp when a tick label has four digits; the free-ride steps of the ride plot are surface 3 (opaque); a series with a
  missing reading is a gap, never a zero, and the readout says "—"; the stepped FTP line is drawn from daily points so a day can be selected on it.
- **Touch**: a hold of 250 ms and a slide scrubs; a vertical scroll that starts without the hold scrolls the page. The haptic is `TextHandleMove`.

