# Companion app (phone): interface spec

The implementer's spec for `android/app-companion` (Phase 3, spec §6.1). It adds to `design/system.md`; it does not replace it.
Tokens, type styles, components, chart rules (§8) and motion (§9) there apply unchanged unless this file says otherwise. Where
the two disagree about the companion, this file wins; where a mock-up and this file disagree, this file wins.

Mock-ups: `design/mockups/companion/*.html` (open in a browser; they load `../tokens.css`), rendered to
`design/mockups/companion/png/`. All numbers in them are synthetic.

Contents: 1 Frame and rules · 2 Navigation · 3 Shared patterns · 4 Screens · 5 States · 6 Charts · 7 Copy · 8 Accessibility ·
9 Performance · 10 Components · 11 Data the screens need · 12 Tokens · 13 Mock-up index · 14 Implementation note (charts stage). Stage P3-4c adds §4.11 (the FIT share target),
§4.12 (repeated sessions) and §6.10 (charts over attempts); they have no HTML mock-up, the Compose screenshots of the stage are their
reference (`app-companion/build/shots/repeat*.png`, `repeats--*.png`, `import--*.png`, `rides--outdoor.png`, `settings--outdoor-*.png`).
The charts stage adds **Power skills** (§4.14, §6.11), **Effort** (§4.15, §6.12) and **Progress** (§4.16, §6.13), with the copy in
§7.8–7.9, the data in §11 and the implementation note in §14; mock-ups `skills.html`, `effort.html` and `progress.html`.

## 1. Frame and rules

- **Device:** Xiaomi 14 Ultra, portrait, 412 × 915 dp (system.md §10). Layouts must hold from 360 dp wide: nothing has a fixed
  width except chart gutters; tables and tile groups shrink, text wraps.
- **Dark only.** The system defines no light theme (ADR 0002), so the companion has none. It follows the ride app, not the
  phone's theme. No Material You, no dynamic colour.
- **One hand.** Everything frequent is in the bottom half or the bottom navigation. Targets ≥ 48 dp. No gesture is the only way
  to do anything.
- **Colour:** neutrals for everything; power-zone colours only where the data is power (workout profiles, the ride's planned
  steps, time in zones). No status colours: recovery, deviations and problems are words + icon + weight (system.md §2).
- **Numbers:** `tnum` on every number, as everywhere in the project (system.md §3). Big standalone figures keep tabular digits
  too: the project rule wins over the general dataviz advice to use proportional figures there.
- **Offline first:** every screen draws from the last answers it cached, then refreshes. The phone talks only to our service
  (and to Health Connect on the phone).

## 2. Navigation

Bottom navigation (system.md §7.9): **Today** (`house`), **Fitness** (`activity`), **Power** (`zap`), **Wellness** (`moon`),
**Rides** (`list`). Five top-level screens, each its own back stack; tapping the active tab scrolls to the top.

| From | Pushed screen (back arrow top bar, no bottom nav) |
|---|---|
| Today, gear icon in the header | Settings |
| Today, note card, "All notes" | Notes |
| Today, planned card when the workout is done | Ride detail |
| Today, a fact row in the recovery card | Wellness tab, scrolled to that metric (a tab switch, not a push) |
| Today / Power, FTP suggestion | Answered in place; nothing is pushed |
| Power, a best-effort row | Ride detail of that ride |
| Power, a duration of the Power skills radar or a skill row | A bottom sheet over the tab (§4.14); its ride rows open Ride detail |
| Power, Power skills **Custom** | The date range sheet (§4.14) |
| Fitness, an activity row of Effort's week | Ride detail or Activity detail |
| Fitness or Power, the **Progress** row (their last card) | Progress (§4.16) |
| Progress, a ride in a readout or in Values | Ride detail |
| Notes, an after-ride note | Ride detail |
| Rides, a row | Ride detail or Activity detail |
| Rides, Repeats view, a session | Repeat detail (§4.12) |
| Repeat detail, an attempt | Ride detail of that ride |
| Settings, **Coach notebook** row (group **Coach**) | Coach notebook, read-only (§4.13) |
| Share sheet of OnelapFit, or **Open with** a `.fit` file | Import screen (§4.11), its own activity: **Done** returns to the app that shared |
| Telegram/notification deep link (later) | Today |

Settings is reached only from Today: it is rare, and a gear on every header is noise. Problem lines on any screen that need the
rider (token refused, address wrong) carry their own **Settings** button.

**First run** (no service address stored) replaces the tabs with the three steps of §4.10, then lands on Today.

**Back:** system back pops a pushed screen; on a tab other than Today it goes to Today; on Today it leaves the app.

## 3. Shared patterns

### 3.1 Screen header

Title (`title`, 22/28) and one subtitle line (`label`, text 2), 4 dp extra side inset, 8 dp above (system.md §10). The subtitle
says when the data on the screen was last fetched: **"Updated 07:40"** (today), **"Updated yesterday 21:10"**, **"Updated
17 Nov 21:10"**. Today's subtitle is the date first: "Wednesday 18 November · updated 07:40". While a refresh runs, the
subtitle reads **"Updating…"** with a 16 dp spinner ring before it; the content stays as it is (no dimming, no skeleton). Today
has a 48 dp ghost icon button `settings` at the right of the header.

### 3.2 Status line (problems)

When the last refresh failed, a **StatusLine** sits under the header (above the range selector): surface 2, radius 8, padding
12 × 16, 20 dp `triangle-alert`, a first line in bodyStrong text 1 ("Offline · updated 06:10") and the cause in label text 2,
optionally one 40 dp secondary button at the right. It replaces the header's "Updated" text (the header then shows only the
date on Today and nothing on the others). It goes away by itself at the next good refresh. Copy: §7.5.

### 3.3 Pull to refresh

Every top-level screen refreshes on open (at most once per 5 minutes) and on pull-down. The pull shows only the header's
"Updating…"; no stretching indicator, no bounce. Manual **Sync now** in Settings does the same for everything at once and also
sends waiting watch records.

### 3.4 Range selector

Fitness and Power: **6 weeks · 6 months · 1 year** (default 6 months). Wellness: **4 weeks · 3 months · 1 year** (default
4 weeks; the recovery baseline is 28–60 days, so 4 weeks shows what the morning rating looked at). A full-width segmented control
(system.md §7.8) with **44 dp segments** (48 dp with its border and padding, the touch minimum; the tablet's 40 dp segments stay).
It is the one filter row on the screen and scopes every chart under it; the tiles at the top always show today. The choice is
remembered per screen.

### 3.5 Selection and readout (instead of hover)

There is no hover on a phone. Every chart has a **readout row** between its heading and its plot (label style, 24 dp min):
the selected x in text 1 ("Wed 18 Nov", "20 min", "41:20"), then one entry per series: a swatch (12 × 2 line key, 8 dp dot or
12 × 8 band key, in the mark's colour), the series name in text 2 and the value in text 1, 600. The readout is the chart's
legend: there is no other legend and no end label.

- **At rest** the selection is the latest point (today, this week, the latest ride). The latest point carries its 8 dp dot
  with a 2 dp surface-1 ring; there is no crosshair.
- **Select:** tap a chart → nearest x. **Scrub:** touch and hold 250 ms, then slide; the x snaps to the nearest data point
  (day, week, duration, ride, 10 s). A vertical scroll that starts without the hold scrolls the page, never the chart.
- While a past point is selected: a 1 dp text-2 crosshair over the plot height, 8 dp dots on each series at that x, and a
  24 dp **Today** pill (surface 2, label text 2, 48 dp hit area) at the right of the readout; tapping it returns to rest.
  Leaving the screen also returns to rest.
- **One selection per screen** where charts share an x axis: Fitness (both line charts and the week that contains the day),
  Wellness (all small multiples; a "Showing Sun 15 Nov" line with the Today pill appears under the range selector), Ride
  detail (power, heart rate, W′ balance). Power's charts have different x axes and select independently.
- A haptic tick (`TextHandleMove`) on each snap while scrubbing; nothing else vibrates.

### 3.6 Values (table twin)

Each chart card has a ghost **Values** text button (label, text 2, 48 dp hit) at the right of its heading. It opens a bottom
sheet (system.md §9 motion) with the chart's data as a table, newest first, numbers right-aligned and tabular; the sheet's list
is lazy. This is the accessible equivalent of every chart and the way to read exact values without scrubbing.

### 3.7 Cards and spacing

Screen padding 12 dp top, 16 dp sides, 24 dp bottom above the nav; 16 dp between cards (system.md §10). Chart cards: padding
16 × 16 × 12, 8 dp between heading, readout and plot; the plot is the card's inner width (346 dp on a 412 dp screen). A short
explanatory note under a chart (label, text 2) only where the chart's meaning is not obvious from its title (Form, Aerobic drift).

## 4. Screens

### 4.1 Today (`today--*.png`)

Order, top to bottom; each block appears only when it has something to say.

1. **Header** "Today", "Wednesday 18 November · updated 07:40", gear.
2. **StatusLine** when the last refresh failed.
3. **Recovery card** (surface 1, padding 16, gap 12):
   - Head line: "Recovery · this morning" (label, text 3) left; the **recovery meter** right (96 × 6 dp, three segments 4 dp
     apart, filled text 1 from the left: Ready 3, Caution 2, Rest 1; the rest surface 3). When the state is not from this
     morning the label says so: "Recovery · yesterday".
   - **State line:** 24 dp icon + the state word in numM (36/40, 600): `check` **Ready**, `triangle-alert` **Caution**,
     `pause` **Rest**. Icon, word and meter carry the state; there is no colour.
   - **Reason** (body, text 2): one or two sentences, computed by the service from its rules, never by the LLM (§7.2).
   - **Fact rows**, 3 or 4, in the service's order (the signals that decided the state first). Row 56 dp min, hairline above:
     metric name (body, text 1) left; right-aligned value (numXS + unit) over a caption: "usual 50–56" in text 3 when inside the
     range, "above usual 50–56" / "below usual 6:50–7:50" in **text 1** with a 16 dp `triangle-alert` before the metric name
     when outside. A row with no reading shows "—" and "no reading". Tapping a row opens Wellness at that metric.
   - **Learning** (fewer nights than the baseline needs, default 14): no state, no meter. Heading "Learning your usual range",
     "Overdrive rates recovery after 14 nights with the watch. 9 nights so far.", a 4 dp progress bar (surface 3 track, text 1
     fill), then the fact rows with "last night" as the caption.
4. **Planned today card:** "Planned today" (label, text 3); the workout name (heading) over "1:05:00 · load 73 · IF 0.76"
   (label, text 2); the mini profile, card width × 44 dp (system.md §8.1, overlay variant: no axis, no FTP line, 1 dp step gap,
   y max 150%). Several workouts: one block each, separated by a hairline. Ridden: the subtitle begins with a 16 dp `check`
   "Done 18:04 ·" and the card opens the ride. Nothing planned: "Nothing planned today" (body, text 2) and no profile. No plan
   source configured: the card is not drawn.
5. **FTP suggestion** (only while one is open): a Notice (system.md §7.6) with `zap`: "Your FTP may be 256 W" over "Your best
   efforts of the last 6 weeks point to 256 W; it is 250 W now. Zones change only when you confirm." Buttons **Use 256 W**
   (secondary) and **Keep 250 W** (ghost). Either answer is sent to the service (PATCH profile for Use; a dismissal for Keep);
   offline, the answer is queued and the Notice says "Will be sent when the service can be reached." After **Use**, one line
   replaces the Notice until the next screen visit: "FTP is now 256 W. The tablet uses it from its next sync."
6. **Note card:** "Morning note · 07:30" (or "After your ride · 19:20", "Weekly review · 08:00": the kind of the latest note,
   label, text 3) over the text (body, text 1); a 48 dp **All notes** row with `chevron-right` under a hairline. No note yet
   today, but an earlier one exists: the card shows the latest one with its day ("Morning note · yesterday 07:30"). Never a
   note: the card is not drawn. Before 10:00 with no note yet: "No note yet today. It is written after this morning's watch
   data reaches the service." (body, text 2).

### 4.2 Notes (pushed)

Back bar "Notes". A lazy list grouped by day (section label: "Today", "Yesterday", "Monday 16 November"), one card per note:
kind and time (label, text 3), text (body). An after-ride note ends with a link row to the ride ("Threshold 2×20 ›"). 30 days
are loaded; scrolling to the end loads 30 more.

### 4.3 Fitness (`fitness--*.png`)

1. Header "Fitness", "Updated 07:40". 2. StatusLine if any.
3. **Tile group** (3 columns, system.md §7.3, padding 12 × 14): **Fitness** 54 with "−3 in 4 weeks", **Fatigue** 43 with
   "−15 in 7 days", **Form** 11 with its word ("Fresh", §7.3). Always today; not affected by the range or the selection.
4. **Effort** card (§4.15, §6.12): always the last 12 weeks, its own selection.
5. Range selector. 6. **Fitness and fatigue** chart card (§6.1). 7. **Form** chart card (§6.2) with the note "Fitness minus
   fatigue. Below −10 you are training hard; above 5 you are fresh." 8. The **Progress** row (§4.16).

**Charts stage:** the range selector moves below the tiles and Effort, so that everything above it is "now" and everything under
it follows it (the rule of §3.4 stays true). The **Weekly load** card (§6.3) is **retired**: Effort draws the same weekly totals
with the usual range, the verdict and the days, and a second column chart of the same numbers on one tab is noise. The weekly
totals over 6 months or a year stay readable in the Values sheet of Effort (all weeks the service sends, newest first) and in
Fitness, their rolling average. §6.3 stays as the reference of the column geometry that Effort reuses.

Watch activities are part of the load (spec §6.7), so they are part of all three charts; the weekly readout names their share.

### 4.4 Power (`power--*.png`)

**Charts stage order:** 1. Header. 2. StatusLine. 3. FTP card. 4. **Power skills** (§4.14; its own window). 5. Range selector.
6. Power curve, Best efforts, FTP history as below. 7. The **Progress** row (§4.16). The **Aerobic drift** card moves to Progress
(§6.13.4). The range selector moves under the skills card for the reason
given in §4.3: everything above it is "now" or has its own window, everything under it follows it. The list below keeps its
earlier numbering.

1. Header "Power", "Updated 07:40". 2. StatusLine. 3. Range selector.
4. **FTP card** (surface 1, padding 16): "FTP" (label, text 3) over **250 W** (numM, unit W) over "Set 2 Oct" (label, text 2).
   With an open suggestion, the right half shows "Estimated" over **256 W** (numM) over "from 3 rides in 6 weeks", then "Your
   best efforts point to a higher FTP. Zones change only when you confirm." and the buttons **Use 256 W** (secondary, 48 dp) /
   **Keep 250 W** (ghost). Without one: "Set 2 Oct · estimated 251 W" and nothing else. No FTP set on the service: "No FTP set"
   with "Rides use the tablet's own FTP until you set one here." and a **Set FTP** secondary button opening a stepper sheet.
5. **Power curve** chart card (§6.4). 6. **Best efforts** card: a table of 5 s, 1 min, 5 min, 20 min, 1 h in the range: duration
   (body), watts (numXS + W), date (label, text 2), `chevron-right` 16 dp; 48 dp rows; a row opens the ride. A duration nobody
   rode long enough for is left out.
7. **FTP history** chart card (§6.5). 8. **Aerobic drift** chart card (§6.6) with its note.

Watch activities never appear on this screen (spec §6.7).

### 4.5 Wellness (`wellness--*.png`)

1. Header "Wellness", "Updated 07:40". 2. StatusLine. 3. Range selector. 4. "Showing Sun 15 Nov" + Today pill while a past day
   is selected.
5. **Small multiples**, one card each, in this order: **Resting heart rate**, **Sleep**, **Sleep timing**, **Respiratory
   rate**, **Oxygen saturation**, and **Heart rate in sleep** only when the service has it. Each card (§6.7):
   - Head line: name (label, text 3) left, "usual 50–56" (label, text 3) right.
   - Value line: the selected day's value (numS + unit) left; the verdict right: "In your usual range" (label, text 2), or
     `triangle-alert` 16 + "Above usual" / "Below usual" (label, text 1), with " · day 3" when it is the third day in a row.
     No reading: "—" and "No reading" (text 3).
   - The chart.
   - A metric with no reading in the whole range: no chart; one sentence instead (§7.6).

### 4.6 Rides (`rides--rides.png`)

Header "Rides", "Updated 21:10". A lazy list grouped by month: month label (label, text 3) left, "Load 593" (the month's total,
label, text 3, tabular) right; one card of two-line rows (system.md §7.5) per month.

- **Ride row:** name (bodyStrong); "Wed 18 · 1:05:12" (label, text 2); trailing load (numXS) over average power "196 W"
  (caption, text 3).
- **Watch activity row:** activity name as the watch records it ("Beat Saber"); subtitle begins with a 16 dp `heart-pulse` and
  the word **Watch**: "♥ Watch · Sun 15 · 42:10"; trailing load over average heart rate "142 bpm". Marked by icon, word and the
  different unit, never by colour.
- **Outdoor ride row** (stage P3-4c, §4.11): an imported FIT file is an activity of the service (`sport` `cycling-outdoor`, name "Outdoor
  ride"). It is marked like a watch row, by a 16 dp `bike` and the word **Outdoor**, never by colour: "Outdoor · Sun 15 · 1:12:04" after the icon; trailing
  load over average heart rate "142 bpm". Its page is the activity page with "from a bike computer file".
- 50 rows are loaded at a time; the next 50 when the list nears its end.
- Empty: "No rides yet" with "Rides from the tablet and activities from the watch appear here after they reach the service."
- **History | Repeats** (stage P3-4c, §4.12): under the header a two-segment range selector (44 dp segments, the component of §3.4) switches
  the tab between this list and the repeated sessions. The choice is kept while the app lives; the header's subtitle and the status line
  follow the view that is shown.

### 4.7 Ride detail (pushed, `rides--ride*.png`)

Back bar: the name (heading) over "Thu 12 Nov · 18:02–18:58" (label, text 2).

1. **Tile group, 3 × 3:** Duration, Normalised (W), Load · Average (W), Intensity (IF, two decimals), Work (kJ) · Avg heart
   (bpm), Max heart (bpm), Moving. A missing value is "—". Values numS.
2. **Planned workout** (only for a ride of a planned workout): one row card: "Planned workout" (label, text 3), "VO2 5×3 · all
   15 steps ridden" (bodyStrong), "Efforts 287–291 W against 288 W" (label, text 2). The service computes these two lines from the
   plan as it was when the ride was stored (spec §8.4 `get_ride`). Steps skipped: "VO2 5×3 · 12 of 15 steps ridden".
3. **Power, heart rate and W′ balance** chart card (§6.8).
4. **Time in power zones** card: the existing `ZoneTimeBars` (system.md §8.3).
5. **Laps** card: a table, 40 dp rows: # (label, text 3) · step name (label) · time · watts over "of 288" (target, caption text 3;
   "ramp" for a ramp, nothing for free ride) · heart. A ride without a workout has one lap: the table is not drawn.
6. **After-ride note** card when there is one.

### 4.8 Activity detail (pushed, `rides--activity.png`)

Back bar: activity name over "Sun 15 Nov · 20:05–20:47 · from the watch". Tiles Duration, Avg heart, Max heart; a one-tile group
**Load** 38 with "From heart rate, approximate: short hard efforts are under-counted." (spec §6.7); a heart-rate chart card
(§6.9); the line "Counts toward fatigue, form and recovery. Not used for the power curve, FTP or aerobic drift." (label, text 2).

### 4.9 Settings (pushed, `settings--settings*.png`)

Group labels (label, text 3) over cards of setting rows (system.md §6.6: title bodyStrong + one note line in label text 2, control
at the right, 64 dp min) and field rows.

- **Service:** **Service address** field row ("Where your rides and watch data are kept"; the address checks and messages of the
  tablet: "The address needs a host, like example.com.", "Not encrypted: the token is sent as plain text"), **Token** field
  row ("Sent only to this address", dots with **Show**/**Hide**; a refused token adds `triangle-alert` + "The service did not
  accept this token (401)." in text 1 under the field), **Last sync** row (value numXS "Today 07:40"; note "Sent 214 watch
  records · received today's figures" or, after a failure, what is waiting: "Nothing sent since then. 312 watch records are
  waiting."), then **Sync now** (secondary, 40 dp, `refresh-cw`; off while a sync runs, then "Syncing…" with a spinner ring).
- **Health Connect:** **Data access** ("All 6 allowed" / "5 of 6 allowed · sleep is off, so recovery says less"; **Manage**
  opens Health Connect's own permission screen for the app), **Last read** (value; note "Once a day at about 06:00, and when
  you open the app"), **Background reading** ("Allowed" with a `check`, or "Blocked by the phone's battery saving. The morning
  note will be late." in text 1 with **Open settings**). Health Connect missing or too old: one Notice in place of the card,
  "Health Connect is not available" / "Update Health Connect from the Play Store, then come back." (Health Connect on the phone
  is a system app; the companion itself uses no Play Services.)
- **Heart rate:** **Maximum** and **Threshold** steppers (bpm, ±1, long press repeats; ranges 120–220 and 100–210). Notes:
  "Highest you have reached. Your rides show 184." and "Used to count the load of watch activities. Your rides suggest 167."
  (the estimate from the service, spec §6.7). A change is sent to the service; the next load computation uses it.
- **Outdoor rides** (stage P3-4c): one row, "FIT files from OnelapFit" (bodyStrong) with a note (label, text 2) at the left and the value
  (numXS) at the right: **No files waiting** / **1 file waiting** / **3 files waiting**. The note says what to do when nothing waits ("Share a
  .fit file from OnelapFit to Overdrive to add an outdoor ride."), why files wait and that they go by themselves ("There is no network
  connection. They go to your service when it can be reached."), and what the service refused ("1 file was not imported: not a cycling
  ride."). **Sync now** also sends the waiting files.
- **Coach:** one row, **Coach notebook** (bodyStrong) with the note "What the coach keeps about you. Read-only." and a chevron-right at the
  right; it opens the notebook (§4.13). Nothing else in the group.
- **About:** Version (numXS), Licences (Geist SIL OFL 1.1, Lucide ISC; opens a plain text screen).

### 4.10 First run (`settings--onb-*.png`)

Full screens without tabs, padding 24 × 20, the step ("Step 2 of 3", label, text 3; it is a real sequence), title, one paragraph
(body, text 2), the content, and the buttons at the bottom, full width (primary over ghost). No illustrations.

1. **Connect to your service.** "Overdrive keeps your rides and watch data on your own service. Enter its address and the token
   made for this phone." Address and token fields; **Connect** (primary). Connect makes one `GET /v1/profile`; a failure stays on
   this step with the cause under the field it concerns (§7.5).
2. **Read your watch data.** "Overdrive reads these from Health Connect and sends them only to <host>. It never writes to
   Health Connect." Then the list of what is asked and why (bodyStrong name over label text 2 reason):
   Resting heart rate · "The main recovery signal"; Sleep · "How long and when you sleep, for recovery"; Respiratory rate ·
   "A recovery signal"; Oxygen saturation · "A recovery signal"; Heart rate · "Heart rate in sleep, and the load of watch
   activities"; Exercise · "Watch activities count toward fatigue". **Continue** opens the system dialog; **Not now** goes on
   without access (Today then shows the learning card with "Allow Health Connect in Settings to rate recovery.").
   Steps, distance, calories and the rest are **not** asked for: nothing on screen uses them.
   After the dialog, when something was left off: the same list with "✓ Allowed" / "⚠ Off" at the right (label; text 2 and
   text 1) and a Notice (`info`) saying what is lost ("Sleep is off" / "Recovery works from the other signals and says less. You
   can allow it later in Settings."); **Continue** and **Allow sleep** (ghost; asks again once, then opens Health Connect).
3. **Read in the background.** "Once a day, around 06:00, Overdrive reads new watch data so this morning's figures and note
   are ready when you wake. Xiaomi phones stop this unless both settings below are on." Two rows with their state, re-read on
   resume: **No battery restrictions** (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) and **Autostart** (MIUI/HyperOS has no API
   to read it: show the path "Apps · Autostart · Overdrive" and "Not checked" instead of On/Off). **Open settings** (primary),
   **Skip** (ghost).
4. **Reading the last 60 days.** A 4 dp progress bar, "1,240 of about 3,100 records" (label, text 2, tabular), "You can leave
   the app; reading continues." It runs as expedited WorkManager work; Today opens when it ends or when the rider leaves.

### 4.11 Import screen (stage P3-4c, spec §11.8 path A)

What the share target shows. It is a **separate activity** (`ImportActivity`) that the share sheet of OnelapFit or a file manager's **Open with**
starts, for the FIT types (`application/vnd.ant.fit`, `application/x-fit`) and for `application/octet-stream` and any other type whose display
name ends in `.fit`. It is the first run's layout: no tabs, padding 24 × 20, the title "Outdoor ride" (title), one block at 40% of the
height, the buttons at the bottom, full width (primary over ghost), no illustration. The block is a polite live region.

| State | Mark | Heading (heading) | Sentence (body, text 2) | Buttons |
|---|---|---|---|---|
| Reading and sending | 24 dp spinner ring | Importing… | none | none |
| Stored | `check` | Imported | "Sun 15 Nov, 1:12:04, 31.4 km, average heart rate 142 bpm, load 74 (approximate)." (what the service read; parts the file does not have are left out; "(approximate)" when the load is) | **Open Overdrive** (primary, the Rides tab), **Done** (ghost) |
| Already there | `check` | Already imported | "You shared this file before, so it counts once." / "A ride with the same start and length is already in your history." / "It overlaps a ride already in your history, so it counts once."; the ride's facts under it (label, text 3) | as above |
| Kept for later | `triangle-alert` | Saved for later | the cause, then "The file is saved on this phone and goes to your service when it can be reached." Causes: "There is no network connection." (no "pull down" here), "The phone could not find overdrive.example.net. Check the address in Settings, or try again on another network." (+ the hint in brackets), "The service did not answer. It may be restarting; try again in a minute.", "The service had a problem (503). Try again later." | **Done**; **Settings** (primary) when the address is the cause |
| Token refused | `triangle-alert` | Saved, not sent | "The service did not accept the token. Check it in Settings. The file is saved on this phone and goes to your service when it can be reached." | **Settings**, **Done** |
| Not connected yet | `triangle-alert` | Saved for later | "Overdrive is not connected to your service yet. Connect it in the app; the file is saved and goes then." | **Settings**, **Done** |
| Refused | `triangle-alert` | Could not import | one cause: not a FIT file ("This is not a valid FIT file. In OnelapFit, export the ride as a FIT file and share that."), a zip archive ("This is a zip archive, not a FIT file. Open it and share the .fit file inside."), empty ("The file is empty."), too large ("The file is larger than 16 MB, which is more than your service takes."), not cycling ("This FIT file is not a cycling ride, so it was not imported."), no ride ("This FIT file has no ride in it."), too many waiting ("20 files are already waiting for your service. Connect to it, then share this one again."), damaged copy, the service's other refusal ("The service refused the file (400).") | **Done** |
| Not readable | `triangle-alert` | Could not read the file | "The app that shared it did not let Overdrive read it. Share it again, or save it and share it from Files." | **Done** |
| No file | `triangle-alert` | Nothing to import | "Share a .fit file from OnelapFit to Overdrive." | **Done** |

Rules: only `content://` is read (a path another app names is not a file it shares); at most 16 MiB; the file is checked by its type and name
before a byte is read and by its header before anything is kept; the file is kept in the app's private storage before it is sent and
removed only after the service answered; nothing is copied to a shared place; the file name is used for one check and kept nowhere.

### 4.12 Repeated sessions (stage P3-4c, spec §11.7)

**The list** is the second view of the Rides tab (**History | Repeats**, §4.6): one card of rows, one row per session ridden at least twice,
most recently ridden first. A row is 88 dp at least, padding 12 × 16 × 12 × 12: the session's name (bodyStrong), "5 attempts · last Wed 18 Nov"
(label, text 2), the latest attempt against the previous in one plain line (label, text 1, at most two lines): **"Heart rate at the same
power 144 bpm, 2 lower than last time"** ("higher than", "the same as last time"; without both figures "Heart rate at the same power:" and the
service's own reason), and a `chevron-right`. The line never says "fitter": one comparison proves little (spec §11.7). Empty: "No repeated
sessions yet" / "Ride the same workout twice on the tablet and it appears here, with every attempt side by side."

**The page** is pushed (back bar: the session's name, then "6 attempts · 8 Oct to 12 Nov"), a lazy list of cards, 16 dp apart:

1. **Heart rate at the same power, bpm** (chart card, §6.10): the trend in words, the readout, the plot, and under the plot **the service's
   own note** as it wrote it ("Heart rate also moves from day to day with heat, sleep and caffeine, so look at the trend over several
   attempts, not at a single comparison."), plainly, in label text 2.
2. **Attempts** (card): newest first, 64 dp rows: "Attempt 6 · Thu 12 Nov" (bodyStrong), "1:01:00 · 186 W · compliance 100%" (label, text 2),
   at the right the heart rate at the same power (numXS) over "bpm at power" (caption, text 3), `chevron-right`; an attempt with no figure shows "—"
   over "no figure" and **the service's reason** in label text 2 under the subtitle (e.g. "Step 2: only 90 s were within 3% of the target
   power after the first 60 s; 120 s are needed"). A tap opens the ride (§4.7).
3. **Heart rate by step, bpm** (card, only when a step is compared): the table twin of its own figures, so no Values button. At the left the
   steps (label, "220 W · 10:00" in caption text 3) stay; the attempts are columns (48 dp, "#1" … in caption text 3, oldest first), which
   scroll sideways when more than five and open scrolled to the latest. The latest column is text 1, 600; the others text 2; a missing figure is "—",
   and a note says what it means in the service's own numbers ("A dash means fewer than 120 s within 3% of the step's power, after its first 60 s.").
4. **Aerobic drift, %**, 5. **Compliance, %** (the share of the planned time ridden), 6. **Average power, W**, 7. **Duration, min**: chart cards of §6.10.

**The trend in words** (bodyStrong, over "Down 9 bpm over 6 attempts" in label text 2) is the service's, never the phone's:

| Service says | Heart rate at the same power | Drift | Compliance |
|---|---|---|---|
| improving (with at least 3 attempts that have the figure) | Heart rate at the same power is trending down: fitter | Aerobic drift is trending down | Compliance is trending up |
| worsening | Heart rate at the same power is trending up | Aerobic drift is trending up | Compliance is trending down |
| steady (inside the band) | Heart rate at the same power is steady | Aerobic drift is steady | Compliance is steady |
| unknown (fewer than 3 attempts have a figure) | **Not enough attempts yet**, over "2 of 3 attempts have a figure" | the same | the same |

"Fitter" is said only for a falling heart rate at the same power; there is no word for a rising one beyond "trending up" (the note under the chart
gives the usual reasons). Average power and duration have no trend: the service does not give one and the phone computes none.

States as §5: loading "Loading your repeated sessions…" / "Loading the session…"; offline or old: the status line over the cached list or page;
error with nothing cached: "Could not reach your service" and the cause; a card whose measure no attempt has: its heading and one sentence in the
card, no frame ("No attempt has an aerobic drift figure yet. Drift is measured on steady stretches of 40 minutes or more with heart rate."); a
single attempt with a figure draws its dot; an attempt with no figure is a gap in the line and a dash in the readout, never a zero.

### 4.13 Coach notebook (pushed, read-only, spec §11.6, ADR 0035)

Reached from Settings (group **Coach**). Back bar "Coach notebook". The subtitle is "Updated 07:40" (or "Updating…" with a spinner while a
request runs), then the status line when the last refresh failed, then the content:

- **The read-only line** (body, text 2), first in the content: "Read-only. To change something, tell the coach or send a note in Telegram."
  The page has no control that changes the notebook and sends nothing.
- **The seven sections in the service's fixed order**, each a card (surface 1, padding 16, gap 8): the title (heading), "changed 3 days ago,
  version 4" (label, text 2), then the text (body, text 1), selectable and wrapped as written. The words for the change: "changed today",
  "changed yesterday", "changed N days ago" up to six days, then "changed on Mon 9 Nov"; a section never written says "Not written yet"
  and has no text.
- **Methodology** is marked, not moved: the label "Current plan" (label, text 2) sits above its title, and the reason given for its
  current text follows the text as "Why: …" (label, text 2). A change to the plan is announced to the rider (spec §12.4).
- **Decisions log** (the last section): one line per entry, oldest first as written. Collapsed it shows the latest five with **Show all**
  (secondary, 40 dp); expanded, the same button reads **Show latest**. Five entries or fewer have no button. The collapsed state survives a
  rotation and is not kept across a restart.
- **Empty** (no section ever written): the heading "No notebook yet" and one sentence: "The coach writes its notebook as it plans your
  sessions, and it appears here."
- **Offline or old:** the cached text stays and the status line says why, as on the other pages ("Offline · updated 06:10", the cause in
  words). Nothing cached and the request failed: the error block with **Try again**.
- **No sizes.** The service's caps and totals are not shown: this page has no place for them.

The data is `GET /v1/notebook`, a cached resource (`cache/notebook`) asked when the page opens (at most once a minute), on a pull down and on
**Sync now**. It is not part of Settings' "Last sync". The words are `NotebookUi`, pure and tested in `NotebookModelsTest`.

### 4.14 Power skills (Power tab, charts stage; `skills--*.png`)

**Purpose.** One glance at what kind of rider the power says you are now (sprint, short attacks, climbs, time trials, the long
hour), against your own best ever, and how each skill sits on a published scale. It answers "where am I strong, where am I
weak, and am I near my best?". It is not a test and nothing on it is a target.

**Place.** The card after the FTP card and before the range selector (§4.4). It has its own window, so it does not follow the
range selector; the range selector's charts sit below it.

**Card, top to bottom** (ChartCard: padding 16 × 16 × 12, **12 dp** between parts here, as the window control needs air):

1. **Heading row:** "Power skills" (label, text 3) and the ghost **Values** (§3.6).
2. **Window control:** a full-width `RangeSelector` with three 44 dp segments **8 weeks · All time · Custom** (default 8 weeks;
   remembered while the app lives, not across a restart: an old custom range would be a surprise).
3. **Range line** (only while Custom is on): "2 Nov – 15 Nov 2026 · 9 rides" (label, text 1, tabular), a ghost **Change**
   button (40 dp, 48 dp hit) and a 48 dp ghost icon button `x` ("Clear custom range" to TalkBack) that returns to 8 weeks.
   This line is the one place the chosen range is shown; the segment keeps the word "Custom".
4. **Legend** (the readout row of §3.5, without values): a 12 × 2 line key in text 1 with the window's name ("8 weeks", or the
   custom range "2 Nov – 15 Nov") and a 12 × 2 key in text 3 "All time". With All time chosen there is one outline and one key.
5. **The radar** (§6.11), centred.
6. **Note** (label, text 2): with the weight known, "Rings mark levels 3, 6 and 9. Tap a duration or a skill for its rides.";
   without, "Each axis is a share of your best ever: the ring is your best, the inner ring 75%, the centre 50%."
7. **Skill rows**, five, in ring order: **Sprint** 15–30 s, **Attack** 1–3 min, **Climb** 5–10 min, **Time trial**
   15–30 min, **Endurance** 45–60 min. A row is 72 dp at least, a hairline above, padding 12 dp top and bottom, the whole row
   one target:
   - left: a 12 × 4 dp key in the skill's colour (§6.11), the name (bodyStrong, text 1), its span (label, text 3, tabular);
     under it the level, "Strong · level 5 of 9" (label, text 2);
   - right: the best watts at the skill's **key duration** (numXS + "W") over "4.4 W/kg · 5 min" (caption, text 3, tabular).
     Key durations: Sprint 15 s, Attack 1 min, Climb 5 min, Time trial 20 min, Endurance 60 min (the next one of the skill
     when the key duration has no data);
   - under both, full width: the **level bar** (§6.11): nine segments, 6 dp high.
   - **Without weight:** no level line and no bar; the right side shows watts and the duration only.
   - **No data for the skill in the window:** "No ride of 45 min or longer" (label, text 2) in place of the level, "—" (text 3)
     at the right, no bar.
8. **Closing note** (label, text 2), two sentences: the approximation, then the outdoor rule (spec §11.8).
   - With weight: "Levels are approximate: your watts per kilogram (72.0 kg, 7-day average) against published power-profile
     tables. Outdoor rides have no power, so only rides from the tablet count."
   - Without: "Levels need your weight. Weigh yourself with a scale that writes to Health Connect and they appear here. Outdoor
     rides have no power, so only rides from the tablet count."

**Detail of a duration** (a tap on an axis's wedge or its label; `skills--skills-axis.png`). A bottom sheet (system.md §9 motion,
grabber, `x` at the right, 70% scrim), with the axis marked on the radar under it (§6.11, selection). Content:

- Title "5 min" (heading) over the skill's name "Climb" (label, text 2).
- Row **"Last 8 weeks"** (or "2 Nov – 15 Nov" when that is the window): bodyStrong, under it the ride, "Thu 12 Nov · VO2 5×3 ›"
  (label, text 2; the row is 48 dp+ and opens Ride detail); at the right the watts (numXS + W) over "4.4 W/kg" (caption, text 3;
  absent without weight).
- Row **"All time"**, the same. When All time is the window, this is the only value row.
- Row **"Level, 8 weeks"** with "Approximate, at 72.0 kg" under it, and "Fit · 4 of 9" (bodyStrong) at the right. Absent
  without weight.
- A duration with no ride in the window: its row says "No ride of 45 min or longer in these 8 weeks" and the all-time row stays.
- Close: `x`, Back, a tap on the scrim or a swipe down. The axis mark goes when the sheet closes.

**Detail of a skill** (a tap on a skill row; `skills--skills-skill.png`). The same sheet: title "Climb" over "5–10 min · Fit,
level 4 of 9 in 8 weeks"; a small table, one row per duration of the skill (5 min, 10 min): the window's watts over its date,
the all-time watts (text 2) over its date; a tap on a value opens its ride. Then one sentence with the watts per kilogram of each
duration ("4.4 W/kg for 5 min and 4.0 W/kg for 10 min at 72.0 kg.") and "Tap a value to open its ride."

**Custom range** (`skills--skills-picker.png`). **Custom** (or **Change**) opens a bottom sheet:

- Title "Custom range" (heading) and the choice so far (label, text 2, tabular, a polite live region): "Choose the first day" →
  "From 2 Nov · choose the last day" → "2 Nov – 15 Nov · 14 days · 9 rides".
- One month at a time: 48 dp `chevron-left` / `chevron-right` ghost icon buttons around "November 2026" (bodyStrong); the right
  chevron is off (35%) on the current month. Monday-first grid, day names (caption, text 3), **44 dp day cells** with 2 dp
  between rows (the grid spans the sheet: 46 dp per column at 360 dp).
- A day with a tablet ride carries a 4 dp text-2 dot under its number, so empty stretches show before choosing (the dates come
  from the rides list the app already caches, §4.6). Today has a 1 dp `borderStrong` ring. Days after today are text 3 at 55% and
  cannot be chosen.
- Tap a day: it becomes the start (inverted: text-1 fill, onPrimary 600 text, radius 8). Tap a second: the end; the days between
  take a surface-3 band. A tap before the start makes that day the new start; a tap after a complete range starts again. Start and
  end may be the same day.
- "Tap the first day, then the last. A dot marks a day with a ride." (label, text 3).
- Buttons, full width: **Show 2 Nov – 15 Nov** (primary; off until both ends are chosen) over **Cancel** (ghost).
- **Cancel**, Back, a tap on the scrim or a swipe down close the sheet and **change nothing**: the window stays what it was
  (8 weeks, All time or the earlier custom range). Only **Show** applies the range.
- The sheet opens on the month of the current custom end, else on this month.

**States** (`skills--skills-states.png`; the general rules are §5):

| State | What the card shows |
|---|---|
| Loading, nothing cached | Heading, window control, then the spinner line "Loading your power skills…" in a 120 dp block. |
| Refreshing, offline, out of date | The cached card unchanged; the tab's StatusLine says why (§3.2, §7.5). A window chosen while offline draws from its cache if it was fetched before; else the body says "Not loaded yet. It loads when the service can be reached." (body, text 2). |
| No rides with power yet | Heading, "No rides yet" (heading) and "Power skills fill in from rides on the tablet. They appear here after the tablet uploads its first ride." No control, no frame. |
| Fewer than 3 durations with data in the window | No radar, no skill rows. "Ride longer to see the shape: the radar needs at least three durations." (body, text 2) and a table of the durations that have data: duration · watts · date, 48 dp rows that open the ride. |
| A duration with no data | A gap in the outline at that axis (§6.11); the label is the duration over "—" in text 3; its skill row says "No ride of … or longer" when none of its durations has data, else the level comes from the others. |
| Custom range with no rides | Range line "2 Aug – 9 Aug 2026 · no rides"; the body is one sentence: "No rides on the tablet between 2 Aug and 9 Aug. Choose another range." No radar. |
| Weight unknown (none in the last 7 days, spec §12.5) | The share-of-best radar and rows without levels (above). |
| Very small values | The point stays at least 6 dp from the centre so it never vanishes; the label prints the true watts. |
| Very large values (above level 9) | The point stops at the plot's edge; the label prints the true watts; the level is "World class · level 9 of 9". |

### 4.15 Effort (Fitness tab, charts stage; `effort--*.png`)

**Purpose.** Answer at a glance "is this week a lot or a little **for me**?", and show the trend of the last three months. The
number is the week's **load**, the same training load as everywhere in the app (from power for the tablet's rides, from heart
rate for watch activities and outdoor rides, spec §6.7). The service computes the load, the usual range and the verdict; the
phone only draws them.

**Place.** Fitness tab, after the tiles and before the range selector (§4.3). Its 12 weeks never follow the range selector and
its selection is its own (it does not move the Fitness and Form selection, and they do not move it).

**Card, top to bottom** (ChartCard, 8 dp between parts):

1. Heading row: "Effort" (label, text 3) and **Values**.
2. **Verdict** (heading, text 1), the service's verdict in words (§7.8) for the selected week; at rest this week, e.g. "Below
   your usual range so far". It is the one thing to read first.
3. **Week line** (the readout of §3.5; wraps at 1.3×): "This week · load **97** so far" or "Week of 9 Nov · load **395**" (label,
   text 2; the load text 1 600, tabular), " (approximate)" when the service says so; then a 12 × 8 band key in surface 3 and
   "Usual 298–510"; then, while a past week is selected, the 24 dp **This week** pill at the right (48 dp hit; to TalkBack "Back
   to this week").
4. **The chart** (§6.12): 12 weekly columns over the usual-range band.
5. Sub-heading "This week, by day" / "Week of 9 Nov, by day" (label, text 3).
6. **The days:** seven small columns, Mon to Sun (§6.12).
7. **Activity rows** of the selected week, oldest first, 56 dp, a hairline above each: the name (bodyStrong) over the day ("Mon
   16", label, text 2; a watch activity starts with a 16 dp `heart-pulse` and "Watch ·", an outdoor ride with `bike` and
   "Outdoor ·", as on Rides, §4.6); at the right the load (numXS) over its **share of the week** "27%" (caption, text 3), with
   " · approximate" when its load came from heart rate. A row opens Ride detail or Activity detail. None yet: "No rides or
   activities yet this week." (body, text 2).

**Interaction.** A tap on a column (or hold and slide, §3.5) selects its week: the verdict, the week line, the band, the days and
the rows follow. **This week** returns to rest; leaving the tab does too. The days and the rows are not selectable as a chart.

**States** (`effort--effort-states.png`):

| State | What the card shows |
|---|---|
| Loading, nothing cached | §5, for the whole tab. |
| Offline / out of date | The cached card unchanged and the tab's StatusLine. When the cache is from an earlier week, the week line names that week ("Week of 9 Nov") and the verdict is that week's; the phone never relabels a cached week as "This week". |
| No load ever | "No effort yet" (heading) and "Weekly effort starts with your first ride on the tablet or your first activity on the watch." No chart. |
| Learning (fewer than 8 complete weeks before the selected one) | Verdict "Learning your usual range" and "Your usual range appears after 8 weeks of training. 5 so far." (label, text 2); the columns without a band; weeks before the first load are empty slots. |
| Nothing yet this week | Verdict "Nothing yet this week", week line "This week · load 0 · usual 334–511"; the current slot draws nothing; "No rides or activities yet this week." |
| Activity without heart rate (and so without load) | Its row shows "—" over "no load" (text 3) and "No heart rate in this ride, so it adds no load." (label, text 2) under the day; it is left out of the shares. |
| Load partly from heart rate | " (approximate)" in the week line and " · approximate" on those rows; the verdict is still given. |
| A zero week | Nothing in its slot; Values says 0. |
| A very large week | The y axis grows to it (step 100, or 200 above 400); the band gets thinner but its numbers stay in the week line. |

### 4.16 Progress (pushed, charts stage; `progress--*.png`)

**Purpose.** "Am I getting fitter?" over months, with measures other than FTP: one small chart per measure, each with the
service's verdict in words, so the answer is one glance per card. Nothing here is a target.

**Where it lives, and why a pushed screen.** Progress mixes power (tablet rides), heart rate (rides) and the watch (resting heart
rate, weight), so it belongs to no single tab; each tab answers one question (Fitness: load and form now; Power: what the power is
now; Wellness: how recovered this morning), and Power already carries FTP, Power skills, the curve, best efforts and FTP history.
Adding six charts to a tab would bury the "now" cards that Power skills and Effort just put at the top. §2's rule is five tabs
and pushed screens behind rows, so Progress is a **pushed screen** (back bar, no bottom nav) reached from **one row in two
places**: the last card of **Fitness** (after Form) and the last card of **Power** (after FTP history). Both open the same screen
at the top. The **Aerobic drift** card (§6.6) **moves from Power to Progress**, where it sits with the other heart-rate trends;
the Power tab ends with FTP history and the Progress row.

**The entry row** (`progress--progress-entry.png`): a card of one 64 dp row: "Progress" (bodyStrong) over the service's
headline, the verdict of the measure it judges clearest ("Heart rate at the same power is trending down: fitter", label, text 2,
at most two lines), `chevron-right` (text 3). Without a headline (no measure has a verdict yet) the line reads "How your power
and heart rate change over the months". To TalkBack: "Progress. Heart rate at the same power is trending down: fitter. Opens
the progress page."

**The screen, top to bottom:**

1. Back bar "Progress" over "Updated 07:40" (or "Updating…"); StatusLine when the last refresh failed (§3.2).
2. Range selector **6 months · 1 year · All time** (default 6 months, remembered as on the other screens, §3.4). It scopes every
   chart and every verdict on the page (the service judges the trend over the range shown).
3. Group heading **Power** (heading, 8 dp above, 4 dp side inset): cards **Best power** (§6.13.1) and **Weight and watts per
   kilogram** (§6.13.6).
4. Group heading **Heart rate**, with the caution under it, once for the four cards below (label, text 2): "Heart rate also moves
   with heat, sleep and caffeine, so trust a trend over several weeks, not one ride." Cards **Aerobic efficiency** (§6.13.2),
   **Efficiency factor** (§6.13.3), **Aerobic drift** (§6.13.4), **Resting heart rate** (§6.13.5).

**Every card** (ChartCard, 8 dp between parts): the name (label, text 3) with **Values**; the **verdict** (bodyStrong, text 1),
the service's direction in words (§7.9); the **numbers line** (label, text 2, tabular), the service's comparison: now against the
start of the range ("139 bpm at 180 W, 7 lower than in June"); the **readout** (§3.5) at rest on the latest point; the plot; a
note only where the measure is not obvious (§6.13). A tap or a hold selects a point **in that card only** (the cards have
different x steps: months, weeks, rides); the **Latest** pill returns it to rest (§6.10's pill). The verdict and the numbers line
never follow the selection: they are about the range.

**States** (`progress--progress-states.png`):

| State | What the rider sees |
|---|---|
| Loading, nothing cached | §5: back bar, range selector, "Loading your progress…". |
| Offline / out of date | The cached cards unchanged and the StatusLine (§7.5). A range never fetched shows "Not loaded yet. It loads when the service can be reached." |
| Nothing at all (no ride, no watch data) | "No progress to show yet" (heading) and "Progress fills in from rides on the tablet and from the watch. Give it a few weeks of riding." |
| Too few points for a verdict | The verdict reads **"Not enough data yet"**; the numbers line says how many there are and what adds one ("3 of 4 weeks have a figure. Ride steady for 5 minutes or more to add one."); the points that exist are drawn (one point: its dot, §5). |
| A measure with no data in the range | The card is its name and one sentence, no frame (§7.9). |
| A missing period | A gap in the line (week or month), never a zero and never bridged; ride-based charts (efficiency factor, drift) draw only the rides, and their 5-ride mean connects across days without rides, as §6.6. |
| Weight unknown | Best power's note leaves out W/kg; the weight card is one sentence. |

## 5. States

Every screen draws from cache first. The table says what each screen shows in each state; §7 has the words.

| State | What the rider sees |
|---|---|
| **Loading, nothing cached** (first open of a screen only) | Real header and controls; in the body a 20 dp spinner ring + "Loading your fitness…" (body, text 2) placed at 40% height. No skeletons, no placeholder charts. |
| **Refreshing with cache** | Content unchanged; header subtitle "Updating…" with a 16 dp spinner ring. Charts keep their selection. |
| **Fresh** | Header subtitle "Updated 07:40". |
| **Stale / offline** (last refresh failed, cache shown) | StatusLine "Offline · updated 06:10" or "Out of date · updated yesterday 21:10" + the cause; content is the cache, undimmed, with all its dates intact. On Today the recovery label keeps its own day ("Recovery · yesterday") so an old verdict never passes as this morning's. |
| **Error, nothing cached** | Centred block: 24 dp `triangle-alert`, heading "Could not reach your service", the cause (body, text 2), the technical hint in brackets (label, text 3), **Try again** (secondary, `refresh-cw`) and **Open settings** (ghost). |
| **Empty** (connected, no data of this kind yet) | Heading + one sentence saying what fills the screen and where it comes from (§7.6). No illustration, no empty chart frames. |
| **Partial** | The parts that have data are drawn; a part without data is replaced by one sentence in its own card (a metric with no readings, a ride without heart rate: the HR plot is replaced by "No heart rate in this ride.", W′ without an FTP: "W′ balance needs an FTP."). A single missing day is a gap in the line, never a zero. |
| **Learning** (Today, Wellness) | Recovery card in its learning form (§4.1). Wellness draws the lines without a band and says "Your usual range appears after 14 nights." in place of "usual …". |

Charts with **one point** draw just the dot (8 dp, ring) at the centre of the x range and the readout; a line needs two. The
y range then centres on the value with the usual tick step.

## 6. Charts

General rules: system.md §8 (hairline gridlines in `border`, ticks caption text 3 tabular, no chart junk, text never in a series
colour). Common geometry for this file:

- Plot width = card inner width (346 dp). Left gutter for y labels **32 dp** (40 dp on Wellness for "22:00"), right padding 6,
  top padding 10; **x-axis band 20 dp** under the plot when the chart has x labels.
- Y ticks: 3–5 at clean steps (1, 2, 2.5, 5 × 10ⁿ), labels right-aligned 8 dp left of the plot; the bottom line (zero or the
  axis minimum where zero matters) is `borderStrong`.
- Line 2 dp text 1 for the main series, 1.5 dp text 3 (or text 2 on Wellness) for the second; round joins and caps.
- Selected/latest dot 8 dp, 2 dp surface-1 ring. Small dots (days outside a band, estimates, drift points) 6 dp with a 1.5 dp ring.
- Bands: surface 3 (Wellness usual range) or surface 2 (reference ranges on Form and Drift), flat, no edge lines.
- Reference lines: 1 dp `borderStrong` with a caption at its right end, 5 dp above ("FTP 250", "Threshold").
- Gaps: a missing x breaks the line; nothing is interpolated except where stated.

### 6.1 Fitness and fatigue (line, one axis)

- **Data:** daily `ctl` and `atl` over the range (one point per day, today last).
- **x:** days. Ticks: 6 weeks → every second Monday ("12 Oct"); 6 months → month starts ("Jun"); 1 year → quarter starts ("Jan",
  "Apr"). No label within 3 days of either end.
- **y:** load per day, nice range around both series, min 0 allowed. Ticks every 10 or 20.
- **Marks:** Fitness 2 dp text 1, Fatigue 1.5 dp text 3, drawn under Fitness.
- **Readout:** "Wed 18 Nov — Fitness 54 — Fatigue 43". Integers.
- **Height** 196 dp (176 plot + 20 axis).

### 6.2 Form (line with a reference band)

- **Data:** daily `tsb`. **y:** at least −30 to +10 so the band always shows; ticks every 10 or 20; zero line `borderStrong`.
- **Band:** −30 to −10 in surface 2, labelled "Training hard" (caption, text 3) inside its lower-left corner.
- **Marks:** 2 dp text 1. **Readout:** "Wed 18 Nov — Form 11 · Fresh" (true minus for negatives: "−8 · Neutral").
- **Height** 148 dp.

### 6.3 Weekly load (columns)

*Charts stage:* retired from the Fitness tab (§4.3); Effort (§6.12) reuses this column geometry.


- **Data:** weekly totals, Monday-first weeks: `weekStart`, `load`, `rideLoad`, `otherLoad`, `rides`. The current week is partial.
- **x:** one column per week: 6, 26 or 52 columns. Column width = min(20, slot − 2) dp, at least 3 dp; 4 dp top radius (less when
  thinner), square at the baseline. Ticks: week starts that open a month (6 weeks: every week start "9 Nov").
- **y:** 0 to a nice max; 2–3 gridlines; 0 on the `borderStrong` baseline. A zero week draws nothing.
- **Marks:** text 3 columns; the **selected week** (at rest: the current week) in text 1. Value labels (caption) only on the
  selected column (text 2) and the highest column (text 3).
- **Readout:** "Week of 16 Nov · so far — Load 97 · Rides 1 · Watch 38" (Watch only when > 0).
- Selecting a day on the line charts selects its week here; tapping a column selects the week's last day above.
- **Height** 150 dp.

### 6.4 Power curve (line on a log x axis)

- **Data:** best average power for each duration in the range and best ever: `[{durationSec, power, date, rideId}]` for both,
  at the durations 1, 2, 3, 5, 8, 10, 15, 20, 30, 45 s, 1, 1.5, 2, 3, 4, 5, 7, 10, 15, 20, 30, 40, 60, 90, 120 min. A duration
  longer than the longest ride is absent: the line ends there.
- **x:** log₁₀ of seconds from 1 s to 2 h. Ticks "5 s", "1 min", "5 min", "20 min", "1 h".
- **y:** 0 to a nice max over best ever (W); ticks every 250 (or 100 below 600 W). FTP reference line "FTP 250".
- **Marks:** range 2 dp text 1, best ever 1.5 dp text 3 under it.
- **Readout:** "20 min — 6 months 257 W — Best ever 268 W". At rest the selection is **20 min**, the duration FTP comes from;
  scrubbing snaps to the listed durations.
- **Height** 220 dp.

### 6.5 FTP history (step line + estimate dots)

- **Data:** `ftp.history [{date, ftp}]` (changes, with the value in force at the range start), `ftp.estimates [{date, eftp}]`.
- **x:** days as §6.1. **y:** nice range around both, ticks every 10 W.
- **Marks:** FTP as a 2 dp text-1 step line (horizontal, then vertical at the change day); estimates as 6 dp text-2 dots.
- **Readout:** "Wed 18 Nov — FTP 250 W ● Estimated 256 W" (the estimate nearest before the selected day).
- **Height** 150 dp.

### 6.6 Aerobic drift (dots + rolling mean, reference band)

- **Data:** `drift [{date, rideId, name, driftPercent}]`, steady rides only (the service decides which; default 40 min or more
  of steady power with heart rate).
- **x:** days as §6.1. **y:** 0–10% (more if a value exceeds it), ticks 0, 5, 10 "%". Zero line `borderStrong`.
- **Band:** 0–5% surface 2 labelled "Steady".
- **Marks:** each ride a 6 dp text-2 dot; the mean of the last five rides as a 2 dp text-1 line through the ride dates (it
  connects across days without rides).
- **Readout:** "14 Nov · Endurance 90 ● Drift 3.3% — Last 5 rides 4.5%", one decimal. Scrubbing snaps to rides. Tapping the
  readout's ride name opens it.
- Note under it: "How much heart rate rose against power on steady rides of 40 minutes or more. Lower is better."
- **Height** 150 dp.

### 6.7 Wellness small multiples (line in a baseline band)

- **Data per metric per day:** `value` (or null), `baselineLow`, `baselineHigh` (the usual range in force that day: the rolling
  mean ± one spread, spec §6.4), `deviation` (`inside` / `above` / `below`) and `streak` (days in a row outside).
- **x:** days, shared by every card on the screen. Ticks: the first day, the middle, "Today".
- **y:** fixed per metric so a glance compares like with like: resting HR 45–65 bpm (step 5); sleep 5–9 h (step 1, "7 h");
  respiratory rate 12.5–15.5 /min (step 1); SpO₂ 92–100% (step 2); sleeping HR 40–70 bpm. Widened when a value falls outside.
- **Marks:** band surface 3 (a polygon following the daily range); line 1.5 dp text 2; days outside the band 6 dp text-1 dots;
  the selected/latest day 8 dp text 1 with ring.
- **Value line formats:** resting HR integer bpm; sleep `h:mm` ("7:40"); respiratory rate one decimal "/min"; SpO₂ integer "%".
- **Height** 112 dp.

**Sleep timing** (floating bars on a clock axis):

- **Data per night:** `sleepStart`, `sleepEnd` (local times), usual bedtime range and usual wake range.
- **y:** clock time, 21:00 at the top to 09:00 at the bottom (extended in 1 h steps if a night falls outside); gridlines every
  2 h labelled "22:00", "00:00", …
- **Bands:** usual bedtime and usual wake time as two horizontal surface-3 bands.
- **Marks:** one bar per night from sleep start to sleep end, width min(8, slot − 3) dp, fully rounded, text 3; the selected night
  text 1. Value line "23:05–06:45"; verdict "In your usual times" / "Later than usual" / "Earlier than usual" (by bedtime).
- **Height** 168 dp.

### 6.8 Ride: power, heart rate and W′ balance (three plots, one time axis)

One card, three plots stacked with 22 dp between them, one x axis at the bottom (minutes: "0", "10", …, "min" at the right end).

- **Data:** `series` at 10 s (or the ride's length / 600, whichever is coarser): `[{t, power, heartRate, wBal}]`; the
  planned workout (native format, §8.1) when the ride followed one; FTP and threshold HR at ride time; CP and W′ used.
- **Power** (150 dp): the planned steps as the background, each in its zone colour pre-blended with surface 1 at 35% (the
  ride screen's "past" rendering, system.md §2), 1 dp gaps; free-ride steps surface 3 blended; then the power line 1.5 dp
  text 1. y 0 to max(150% FTP, peak) rounded to 50 W; ticks every 100; FTP line labelled "FTP". A ride without a plan draws no
  background.
- **Heart rate** (88 dp, caption "Heart rate, bpm" over it): 1.5 dp text 2; threshold line "Threshold"; y 80–190 (widened as
  needed), ticks 100, 140, 180.
- **W′ balance** (72 dp, caption "W′ balance, kJ"): 2 dp text 1 with a 10% text-1 wash to the baseline; y 0 to W′; ticks 0,
  W′/2, W′. At rest the lowest point carries the dot.
- **Readout at rest:** "Whole ride — Power avg 189 W — Heart avg 139 ● W′ lowest 7.2 kJ at 48:50".
  **Selected:** "37:30 — Power 292 W — Heart 157 — W′ 12.6 kJ · VO2 4 of 5 · Z5" (time `m:ss` / `h:mm:ss`).
- **Height** about 360 dp in all.

### 6.9 Activity heart rate

One plot as the ride's heart-rate plot, 160 dp + axis, with the threshold line; readout "Whole activity — Heart avg 142 · max 171".

### 6.10 Over the attempts (stage P3-4c)

Used by the repeat page (§4.12); the geometry of §6 applies (32 dp gutter, 6 dp right, 10 dp top, 20 dp axis band, hairline gridlines, `borderStrong` baseline).

- **Data:** one figure per attempt, oldest first; a missing figure is NaN (a gap).
- **x:** the attempt, 1 … n, **equally spaced** (not by date: the unit of a trend is the attempt), the axis from 0.5 to n + 0.5 so the first and last dots are
  not on the edge. Labels "1", "2", … every attempt up to 8, then every k-th and always the last.
- **y:** a clean range around the figures (1 bpm / 5 W / 2 min of padding, at most 4 ticks), the unit in the card's heading ("Heart rate at the same power, bpm") and
  not on the ticks; drift 0 to 10 % (more in steps of 5), compliance 0 to 100 with ticks at 0, 50, 100.
- **Marks:** 6 dp text-1 dots with a 1.5 dp surface ring, a 1.5 dp text-3 line between neighbours (no gap across an attempt with a figure on each side);
  the selected or latest attempt an 8 dp dot with a 2 dp ring. No fitted line, no band, no reference line: the trend is in words.
- **Readout:** "Attempt 4 · Thu 29 Oct ● Heart rate 144 bpm" (drift "3.4%", compliance "100%", power "184 W", duration "1:00:36"); "—" for no figure.
  The pill at the right while a past attempt is selected says **Latest** (to TalkBack "Back to the latest attempt").
- **One selection for the page:** a tap or a hold selects an attempt in every chart of the page at once.
- **Values** (every chart card): "Attempt" / the figure, newest first: "#6 · 12 Nov". **Height** 148 dp.

### 6.11 Power skills radar (charts stage)

**Form.** A radar with 12 axes, used here and nowhere else: the durations of one rider's profile, read as a shape (a pointed
sprint, a full hour). Exact values are on the labels, in the sheets and in Values.

**Axes.** 12 durations clockwise from the top, 30° apart: 15 s (top), 30 s, 1, 2, 3, 5, 10 (bottom), 15, 20, 30, 45, 60 min.
Angle of axis i (0° at three o'clock, y down): `θᵢ = −90° + 30° × i`.

**Radial scale.** Linear from the centre (0) to the plot's edge (1), one scale for both outlines.

- **Weight known: the level scale.** Each axis places the rider's watts per kilogram on that duration's power-profile scale:
  `score = 1 + 8 × (W/kg − B) / (T − B)`, where B is the floor of level 1 and T the floor of level 9 at that duration (table
  below); the radius is `score / 9`, at least 6 dp, at most the edge. The edge is level 9 and **the rings at ⅓ and ⅔ of the radius
  are levels 3 and 6** (hairline `border`; the edge `borderStrong`; spokes hairline `border`). The shape compares durations fairly
  (a 900 W sprint and a 250 W hour stand on the same footing), it matches the skill rows, and a ring means the same on every axis.
- **Weight unknown: share of the best ever.** Each axis is the window's watts as a share of the all-time best at that duration,
  `r = (share − 0.5) / 0.5`: the centre is 50%, the one inner ring (half the radius) 75%, the edge 100%. The all-time outline is
  then the edge itself and is not drawn twice. Starting at 50% gives the 5–15% that matter room to show; the note under the
  radar says it in words.
- Rejected: watts on one common axis (15 s and 60 min differ about fourfold, so the long half collapses to the centre and every
  rider looks like a sprinter), and a log scale (nobody reads one on a radar).

**Level table** (W/kg, men's; log-interpolated in duration between the 5 s, 1 min, 5 min and FTP anchors of the published power
profile tables (Allen and Coggan), rounded). The service owns the table as a data constant and may refine it; the phone never
holds it. B is the floor of level 1, T the floor of level 9; levels 2–8 are equal steps between.

| | 15 s | 30 s | 1 min | 2 min | 3 min | 5 min | 10 min | 15 min | 20 min | 30 min | 45 min | 60 min |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| B | 8.2 | 6.9 | 5.6 | 4.2 | 3.35 | 2.3 | 2.2 | 2.1 | 2.1 | 2.0 | 1.95 | 1.9 |
| T | 18.5 | 15.0 | 11.5 | 9.8 | 8.85 | 7.6 | 7.25 | 7.05 | 6.95 | 6.75 | 6.55 | 6.4 |

A women's table is built the same way from the published women's anchors (the API stage checks the anchor values against the
source); which table applies is a profile setting (§11). Below B the score falls under 1 (drawn there, level 1); above T it is
level 9 and the point stops at the edge.

**Skill level** (rows and sheets): the mean of the scores of the skill's durations that have data in the window, rounded down,
clamped to 1–9. **The nine levels**, our own plain words on an ordinal scale, always written with their number ("Strong · level
5 of 9"):

| Level | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|
| Word | Starting | Casual | Regular | Fit | Strong | Very strong | Racer | Elite | World class |

They are approximate: the tables survey trained riders, indoor power differs from outdoor, and the weight is a 7-day average. The
card's closing note and every sheet say "approximate".

**Skill ring and colour.** A 4 dp ring just outside the plot (its centre line 6 dp beyond the edge), split into five arcs, each
from 15° before its first axis to 15° after its last, with a **2 dp surface-1 gap** between arcs. Each skill takes the power-zone
colour its best efforts are ridden in: a real zone meaning (the data is power, system.md §2), ordered short to long:

| Skill | Durations | Colour | Why |
|---|---|---|---|
| Sprint | 15 s, 30 s | Z7 #EA8DE7 | best 15–30 s efforts sit above 150% of FTP |
| Attack | 1, 2, 3 min | Z6 #FF7C7C | 121–150% |
| Climb | 5, 10 min | Z5 #FFA94F | 106–120% |
| Time trial | 15, 20, 30 min | Z4 #F2DE3C | 91–105% |
| Endurance | 45, 60 min | Z3 #61DF84 | the long efforts sit at or just under threshold |

Validated with the dataviz skill's validator (`validate_palette.js`, dark, surface #11151A, adjacent pairs including the wrap from
Endurance back to Sprint): **colour-vision separation passes** (worst pair Z3–Z4, ΔE 8.1 under protanopia). The normal-vision
floor is 12.9 for Z5–Z6, under the method's 15: the pair system.md §2 already names as the closest zones. The zone colours are fixed
tokens and the system adds no hue, so colour is **never the only carrier**: every axis prints its duration, every row its name
and span, the arcs are cut by gaps, and the rows follow the ring's order. Z2 for Endurance was rejected: next to Z7 at the wrap it
collapses under protanopia (ΔE 4.4). The skill colour appears only on the arcs, the row keys and the level bars, never on text.

**Outlines.**

- Window (8 weeks or custom): 2 dp text 1, round caps, with a 10% text-1 wash inside **only when all 12 axes have data**.
- All time: 1.5 dp text 3, no wash, drawn under the window's. Not drawn when All time is the window.
- A duration without data breaks the outline: the two segments that would meet there are not drawn, and there is no wash. An
  isolated point (both neighbours missing) is a 6 dp dot with a 1.5 dp surface-1 ring.
- No vertex dots at rest. **Selected axis** (while its sheet is open): the spoke in text 2, the duration label in text 1 and the
  watts in 700, an 8 dp dot with a 2 dp ring on each outline at that axis.

**Labels.** At each axis, outside the ring, a two-line block: the duration (caption, text 3) over the watts (label 600, text 1,
tabular: "930 W" with " W" in 500 text 2; "1,010 W" with a thousands comma; "—" in text 3 when missing). The block begins
`Rp + 14 dp` from the centre; it is centred horizontally when |cos θ| < 0.3, otherwise left-aligned on the right half and
right-aligned on the left half; vertically its centre moves by `15 × sin θ` dp, so the top label (15 s) sits wholly above the
top of the ring and the bottom one (10 min) wholly below; their neighbours are 30° away and do not touch them (checked at 360 dp and
at 1.3×).

**Geometry.** Card inner width w (346 dp on a 412 dp phone, 296 at 360). Label column 54 dp ("1,010 W" at 13 sp is about 50 dp).
Plot radius `Rp = min(w/2 − 54 − 14, 118)`: 105 dp at 412, 80 dp at 360. Height `2 × (Rp + 14 + 34)`: 306 dp at 412, 256 at 360.
At **1.3× font scale** the label column is measured (the widest label + 4 dp) instead of fixed and Rp shrinks with it (about 90 dp
at 412, 66 at 360); the block heights grow with the text. The radar never scales text down.

**Hit targets.** Each axis owns the wedge of ±15° around it, from the centre out to the end of its label block: at a radius of
92 dp or more that wedge is wider than 48 dp, and the label block itself is 48 dp tall with padding. A tap picks the axis by angle,
anywhere in the radar's box, and opens its sheet. No hold-and-scrub on the radar: the sheet is its readout.

**Level bar** (skill rows): nine segments across the row's full width, 6 dp high, 2 dp gaps (surface 1), the first segment rounded
on the left and the last on the right (3 dp). Segments 1 … level take the skill's colour, the rest surface 3. Hidden from
accessibility; the words say the level.

**Values** (the table twin): "Duration" · the window · "All time" (and "W/kg" with the weight), 12 rows in axis order, "—" for no
data.

### 6.12 Effort (weekly columns with the usual range)

- **Data:** 12 Monday-first weeks, oldest first, the current one partial: `weekStart`, `load`, `approximate`, `usualLow`,
  `usualHigh` (null while learning), `verdict`. For the selected week: its seven days' load and its activities.
- **x:** one slot per week, 12 across the plot; columns as §6.3 (min(20, slot − 2) dp, 4 dp top radius, square at the baseline, a
  zero week draws nothing). Ticks: the first week's Monday ("31 Aug"), the seventh ("12 Oct"), and **"Now"** under the current
  week (text 2, right-aligned to the plot's end), which marks it as in progress; the week line says "so far".
- **y:** 0 to a nice maximum over the loads and the selected week's usual high; step 100 (200 above 400); 32 dp gutter, hairline
  gridlines, `borderStrong` baseline.
- **Band:** the **selected week's** usual range (at rest, this week's), one flat surface-3 rectangle across the plot, behind the
  columns. It moves when another week is selected, so a column is always seen against the range its verdict used. No label inside
  the plot (it would sit behind columns); the band key in the week line names it ("Usual 298–510"). The service computes it as
  the mean ± one standard deviation of the 8 complete weeks before that week, like the wellness usual range; a week without 8
  before it has none.
- **Marks:** columns text 3; the selected week text 1 with its value (caption, text 2) 5 dp above. No other value labels and no
  colour for above or below: the position against the band and the verdict in words carry it.
- **Height** 160 dp (140 plot + 20 axis).

**The days** (under the chart): seven slots across the card's width, 64 dp tall (46 plot + 18 labels), columns 20 dp wide in
**text 2**, scaled to the week's largest day, no y axis and no numbers (the rows carry them); "Mon" … "Sun" (caption, text 3);
today in text 1 600; days after today at 50%. Baseline `borderStrong`.

**Values:** "Week" · "Load" · "Usual" · the verdict word ("Above", "Within", "Below", "—"), every week the service sends (up to 52),
newest first ("16 Nov · so far").

### 6.13 Progress charts (charts stage)

The geometry of §6 (32 dp gutter, 6 dp right, 10 dp top, 20 dp axis band, hairline gridlines, `borderStrong` baseline where zero
is shown). **x** is time over the range; ticks at month starts (6 months: every month "Jun"; 1 year: every second month; all
time: every quarter, or every year beyond two years). Lines 2 dp; dots 6 dp with a 1.5 dp surface ring; the selected or latest point
8 dp with a 2 dp ring. **Verdicts** come from the service's trend, the model of the repeat page (spec §11.7): a least-squares line
over the points with a figure, `steady` inside a stated band, else `improving` or `worsening` by which way is better, `unknown`
below the minimum count (table). The phone computes no trend.

| # | Card | x step | Points | Minimum for a verdict | Steady band (service) | Better when |
|---|---|---|---|---|---|---|
| 1 | Best power | month | best 5, 20, 60 min mean power of the month | 3 months with that duration, for each line | ±2% of the start | higher |
| 2 | Aerobic efficiency | week | heart rate at the reference power; power at the reference heart rate | 4 weeks with a figure | 2 bpm / 4 W | lower bpm, higher W |
| 3 | Efficiency factor | ride | normalised power ÷ average heart rate, steady rides | 5 rides | 0.02 | higher |
| 4 | Aerobic drift | ride | drift %, steady rides of 40 min or more (§6.6) | 5 rides | 1 point | lower |
| 5 | Resting heart rate | week | mean of the week's nights (at least 3 nights) | 4 weeks | 1 bpm | lower |
| 6 | Weight and W/kg | week | mean weight (at least 2 weighings); FTP in force ÷ that weight | 4 weeks | 0.05 W/kg (weight has no verdict) | higher W/kg |

**6.13.1 Best power.** Three lines on one watt axis, one per duration, each in the zone colour of its Power skills ring (5 min
Z5, 20 min Z4, 60 min Z3, §6.11): the same durations mean the same colours across the tab, and the data is power. Monthly points
at mid-month with their dots (a month is a single fact, so every point shows); a month without a ride of that length breaks that
line; the current month is "so far" in the readout. y: a clean range over the three lines, step 50 W. Readout "Nov · so far ●
5 min 326 W ● 20 min 281 W ● 60 min 247 W"; the line keys are the legend, so colour is never alone. Note: "Your best of each
month." plus, with a weight, "3.9 W/kg at 20 min with 72.0 kg." Height 132 dp. The verdict judges the three together (the service:
`improving` when at least two lines improve and none worsens; the reverse for `worsening`; else `steady`), and the numbers line
gives each: "5 min +21 W · 20 min +19 W · 60 min +11 W since June".

**6.13.2 Aerobic efficiency** (the repeat page's comparison, generalised to every ride). Two stacked plots on one x, each with a
caption over it (caption, text 3): "Heart rate at 180 W, bpm" (2 dp text 1) and "Power at 140 bpm, W" (2 dp text 2), 96 dp each.
The **reference power** is fixed for the range by the service (about 70% of the FTP at the range's start, rounded to 10 W) and the
**reference heart rate** likewise (about 80% of the threshold heart rate, rounded to 5 bpm); both are named in the captions and the
readout, so a change of FTP never moves the reference silently. Each week's figure is the median over that week's steady
stretches (5 minutes or more of steady power after the ride's first 10 minutes, not paused, with heart rate; within 5% of the
reference power, or within 3 bpm of the reference heart rate). Readout "Week of 16 Nov — At 180 W 140 bpm — At 140 bpm 184 W". Note:
"From steady stretches of 5 minutes or more in every ride, the median of each week."

**6.13.3 Efficiency factor.** §6.6's form: each steady ride a 6 dp text-2 dot, the mean of the last five rides a 2 dp text-1 line
through the ride dates. y a clean range, two decimals in the readout ("1.42"), one on the ticks. Readout "Mon 16 Nov · Threshold
2×20 ● Ride 1.42 — Last 5 rides 1.39"; scrubbing snaps to rides; a tap on the readout's name opens the ride. Note: "Normalised
power divided by average heart rate on steady rides. Higher is better." Height 112 dp.

**6.13.4 Aerobic drift.** §6.6 unchanged (band 0–5% "Steady", dots and 5-ride mean, its note), now on this page, height 112 dp.

**6.13.5 Resting heart rate.** One weekly line 2 dp text 1; y a clean range (step 2 or 4 bpm); readout "Week of 16 Nov — Weekly
mean 51 bpm". Note: "From the watch, the mean of each week's nights." Height 112 dp.

**6.13.6 Weight and watts per kilogram.** Two stacked plots, 96 dp each, with captions: "Watts per kilogram (FTP)" (2 dp text 1, two
decimals in the readout) and "Weight, kg" (2 dp text 3, one decimal). The verdict is about watts per kilogram only; weight is
reported plainly in the numbers line ("weight 72.0 kg, 1.4 kg less") and never judged. Readout "Week of 16 Nov — FTP per kg
3.47 W/kg — Weight 72.1 kg".

**Values** of each card: the points newest first with the date ("Nov 2026", "16 Nov", "Mon 16 Nov · Threshold 2×20") and every
figure of the card, "—" for none.

## 7. Copy

Plain English, sentence case, no exclamation marks, no hype, no diagnosis, no medical words (system.md §11, spec §6.5).

### 7.1 Labels

Tabs: Today, Fitness, Power, Wellness, Rides. Metrics: Fitness, Fatigue, Form, Load, Weekly load, Power curve, Best efforts,
FTP, Estimated, FTP history, Aerobic drift, Resting heart rate, Sleep, Sleep timing, Respiratory rate, Oxygen saturation, Heart
rate in sleep, Duration, Moving, Normalised, Average, Intensity, Work, Avg heart, Max heart, Time in power zones, Laps, W′ balance.
Units: W, bpm, /min, %, kJ, h. "usual 50–56" for a baseline; "Watch" for an activity from the watch.

### 7.2 Recovery

| State | Icon | Meter | Word | Default reason (the service writes the actual one from the facts) | Guidance appended |
|---|---|---|---|---|---|
| Ready | `check` | 3 | Ready | "All four signals are in your usual range." | "Train as planned." |
| Caution | `triangle-alert` | 2 | Caution | "Resting heart rate is above your usual range for the second morning." | "Train, but keep the hard parts short." |
| Rest | `pause` | 1 | Rest | "Two signals are outside your usual range." | "Keep today easy or take it off." |
| Learning | — | — | Learning your usual range | "Overdrive rates recovery after 14 nights with the watch. 9 nights so far." | — |
| No data last night | — | — | No reading from last night | "Wear the watch to sleep, then open Overdrive in the morning." | — |

The reason names signals and directions only ("above your usual range", "shorter than usual"), never a cause ("you may be ill").

### 7.3 Form words

From today's form (fitness − fatigue), computed in `core` (one function, shared with MCP): **Fresh** above +5 · **Neutral**
−10 to +5 · **Training hard** −30 to below −10 · **Very fatigued** below −30.

### 7.4 Deviation words (Wellness)

"In your usual range" · "Above usual" · "Below usual" · "Above usual · day 3" · sleep timing "In your usual times", "Later than
usual", "Earlier than usual" · "No reading".

### 7.5 Problems (StatusLine, error block, Settings)

Reuse the ride app's classification of network failures (`TransportFailure` in `android/data`) and its wording; first line
"Offline · updated 06:10" when there is no network, otherwise "Out of date · updated 06:10".

| Cause | Text | Action |
|---|---|---|
| No network | "No network connection. Pull down to try again." | — |
| DNS | "The phone could not find overdrive.example.net. Check the address in Settings, or try again on another network." | Settings |
| Blocked by the system | "The system blocks this app's network access. Allow it in the phone's app settings." | Open settings |
| Refused / cut | "The service did not answer. It may be restarting; try again in a minute." | — |
| Token refused (401) | "The service did not accept the token. Check it in Settings." | Settings |
| Server error (5xx) | "The service had a problem (500). Try again later." | — |
| No network, on the import screen | "There is no network connection." then "The file is saved on this phone and goes to your service when it can be reached." | — |
| Health Connect unavailable | "Health Connect is not available. Update it, then come back." | — |
| Health Connect permission revoked | "Overdrive can no longer read your watch data. Allow it in Settings." | Settings |
| Background reading blocked | "The phone stopped this morning's read. The figures are from yesterday." | Settings |

A technical hint in brackets follows where the ride app gives one ("(UnknownHostException · DNS)", label, text 3).

### 7.6 Empty

- Today, no plan source: the planned card is absent. No notes ever: the note card is absent.
- Fitness: "No load yet" / "Fitness and fatigue start with your first ride on the tablet or your first activity on the watch."
- Power: "No rides yet" / "The power curve, FTP and aerobic drift fill in from rides on the tablet. They appear here after the
  tablet uploads its first ride."
- Aerobic drift alone: "No steady rides yet. Drift is measured on steady rides of 40 minutes or more with heart rate."
- Wellness, no access: "Overdrive cannot read Health Connect yet." + **Allow** (primary).
- Wellness, a metric with no readings: "No readings in the last 4 weeks. The watch writes them during sleep; check that
  blood-oxygen tracking is on in OHealth." (resting HR and respiratory rate say "…check that the watch is worn at night.")
- Rides: "No rides yet" / "Rides from the tablet and activities from the watch appear here after they reach the service."
- Repeats: "No repeated sessions yet" / "Ride the same workout twice on the tablet and it appears here, with every attempt side by side."

### 7.7 Repeats and the import

Units on the repeat page: bpm, W, % (a percent is written close to its number: "3.0%"), min. A trend sentence names the measure ("Aerobic drift is
trending down"), never "you"; no medical words, no verdict beyond the one the spec asks for ("fitter", only for a falling heart rate at the same
power with the service's three attempts). The import screen says what happened to the file and where it is, in that order, and what to do.

### 7.8 Power skills and Effort

**Power skills.** Heading "Power skills"; segments "8 weeks", "All time", "Custom"; "Change"; "Clear custom range" (TalkBack);
legend "8 weeks" / "All time" / "2 Nov – 15 Nov"; range line "2 Nov – 15 Nov 2026 · 9 rides" ("1 ride", "no rides"); skills
"Sprint", "Attack", "Climb", "Time trial", "Endurance"; spans "15–30 s", "1–3 min", "5–10 min", "15–30 min", "45–60 min"; level
"Strong · level 5 of 9"; the levels Starting, Casual, Regular, Fit, Strong, Very strong, Racer, Elite, World class; "4.4 W/kg ·
5 min"; "No ride of 45 min or longer"; notes and state sentences exactly as in §4.14. Sheets: "Last 8 weeks", "All time",
"Level, 8 weeks", "Approximate, at 72.0 kg", "Fit · 4 of 9", "Tap a value to open its ride." Picker: "Custom range", "Choose the
first day", "From 2 Nov · choose the last day", "2 Nov – 15 Nov · 14 days · 9 rides", "Tap the first day, then the last. A dot
marks a day with a ride.", "Show 2 Nov – 15 Nov", "Cancel". Durations "15 s", "1 min", "60 min" (never "1 h" here: the set ends at
60 min). A range within one year omits the year except on the range line.

**Effort.** Heading "Effort". The service sends the verdict as a key; the phone owns the words:

| Key | A past week | This week |
|---|---|---|
| `above` | Above your usual range | Above your usual range |
| `within` | Within your usual range | Within your usual range |
| `below` | Below your usual range | Below your usual range so far |
| `none` (nothing this week yet) | — | Nothing yet this week |
| `learning` | Learning your usual range | Learning your usual range |

In the current week "above" and "within" can only stay or rise, so they are said plainly; "below" says "so far". Week line "This
week · load 97 so far", "Week of 9 Nov · load 395", " (approximate)", "Usual 298–510"; pill "This week"; "This week, by day",
"Week of 9 Nov, by day"; rows "27%", "7% · approximate", "no load", "No heart rate in this ride, so it adds no load."; empty "No
effort yet" / "Weekly effort starts with your first ride on the tablet or your first activity on the watch."; learning "Your usual
range appears after 8 weeks of training. 5 so far."; "No rides or activities yet this week." Never "good", "bad",
"overtraining", "too much": the verdict compares the rider with their own weeks and says nothing more.

### 7.9 Progress

Title "Progress"; groups "Power", "Heart rate"; cards "Best power", "Weight and watts per kilogram", "Aerobic efficiency",
"Efficiency factor", "Aerobic drift", "Resting heart rate". The caution, once, under "Heart rate": "Heart rate also moves with
heat, sleep and caffeine, so trust a trend over several weeks, not one ride." Verdicts (the service sends the direction, the phone
owns the words; the pattern of §4.12):

| Card | improving | worsening | steady | unknown |
|---|---|---|---|---|
| Best power | Best power is trending up: better | Best power is trending down | Best power is steady | Not enough data yet |
| Weight and W/kg | Watts per kilogram are trending up: better | Watts per kilogram are trending down | Watts per kilogram are steady | Not enough data yet |
| Aerobic efficiency | Heart rate at the same power is trending down: fitter | Heart rate at the same power is trending up | Heart rate at the same power is steady | Not enough data yet |
| Efficiency factor | Efficiency factor is trending up: fitter | Efficiency factor is trending down | Efficiency factor is steady | Not enough data yet |
| Aerobic drift | Aerobic drift is trending down: better | Aerobic drift is trending up | Aerobic drift is steady | Not enough data yet |
| Resting heart rate | Resting heart rate is trending down: better | Resting heart rate is trending up | Resting heart rate is steady | Not enough data yet |

"Fitter" is said only for the two measures of heart rate against power (as on the repeat page); the others say "better", and a
worsening trend has no judging word at all. Numbers lines (the service's figures, the phone's words): "5 min +21 W · 20 min
+19 W · 60 min +11 W since June"; "139 bpm at 180 W, 7 lower than in June · 185 W at 140 bpm, 13 more"; "1.41 over the last 5
steady rides, 1.33 in June"; "4.2% over the last 5 long rides, 6.9% in June"; "51 bpm this week, 54 in June"; "3.47 W/kg now,
3.24 in June · weight 72.0 kg, 1.4 kg less" ("more" when it rose). Not enough data: "3 of 4 weeks have a figure. Ride steady for
5 minutes or more to add one." · "2 of 3 months have a 60-minute ride." · "4 of 5 steady rides so far." · "4 of 5 rides of
40 minutes or more so far." · "2 of 4 weeks have 3 nights with the watch." · "2 of 4 weeks have 2 weighings." No data in the range:
Best power "No rides with power in this range."; Aerobic efficiency "No steady stretch of 5 minutes or more with heart rate in
this range."; Efficiency factor "No steady ride with heart rate in this range."; Aerobic drift "No steady rides yet. Drift is
measured on steady rides of 40 minutes or more with heart rate."; Resting heart rate "No resting heart rate yet. It comes from the
watch through Health Connect."; Weight "No weight yet. Weigh yourself with a scale that writes to Health Connect." Empty page "No
progress to show yet" / "Progress fills in from rides on the tablet and from the watch. Give it a few weeks of riding."; loading
"Loading your progress…"; entry row "Progress" with the headline or "How your power and heart rate change over the months".
Outdoor rides have no power, so they are in none of the ride measures (each needs power and heart rate together); the page does
not mention them, as the Power tab's closing notes already say it.

## 8. Accessibility

- **Contrast:** all text uses the existing tiers (≥ 4.5:1 on every surface, system.md §2). Chart marks that carry data are text
  1, 2 or 3 or a zone colour (≥ 4.5:1 on surface 1); surface 2/3 are used only for bands and backgrounds whose meaning is also
  in words ("usual 50–56", "Training hard", "Steady").
- **Not colour alone:** recovery = icon + word + meter; deviation = icon + words + text 1; watch activities = icon + "Watch" +
  unit; zones = "Z5" in readouts and the zone table.
- **Touch:** every target ≥ 48 dp (range segments 44 dp + 4 dp frame; the Today pill and Values have 48 dp hit areas around
  smaller visuals; rows 48–64 dp).
- **TalkBack:** each chart is one focusable node whose description is its readout plus a summary ("Fitness and fatigue, last 6
  months. Fitness 54, down 3 in 4 weeks. Fatigue 43.") and custom actions **Previous point** / **Next point** that move the
  selection; the readout is a polite live region. The meter is hidden from accessibility (the word says it). Values opens the
  table twin. Tab items say "Today, tab, 1 of 5".
- **Font scale:** layouts hold to 1.3×; above that tile groups drop to 2 columns and readouts wrap.
- **Reduced motion:** the system setting makes the sheet and screen transitions instant (system.md §9).

- **Power skills (TalkBack).** Reading order: heading, window control ("8 weeks, selected, 1 of 3"), range line, the radar, its
  note, the five skill rows, the closing note. The radar is one focusable node whose description is its text alternative, the
  values in axis order: "Power skills, last 8 weeks against all time. 15 seconds 930 watts, all time 1,010. 30 seconds 705 watts,
  all time 760. … 45 minutes, no ride. … Rings mark levels 3, 6 and 9." (without weight: "… 93% of your best"). It has no
  per-axis actions (twelve would be noise): each duration is reached through its skill row's sheet and through Values. Each skill
  row is a button: "Climb, 5 to 10 minutes. Fit, level 4 of 9. Best 316 watts, 4.4 watts per kilogram, at 5 minutes. Opens
  details." The ring and the level bars are hidden. When a sheet opens its title takes focus; its ride rows are buttons ("Thu 12
  Nov, VO2 5×3, opens the ride").
- **Date range sheet.** Each day is a button: "Monday 2 November, has a ride, first day"; days after today are disabled ("not
  available"); the choice line is a polite live region; **Show** is disabled until both ends are chosen; the month chevrons are
  "Previous month" / "Next month".
- **Effort (TalkBack).** Reading order: heading, verdict, week line, the chart, the days, the rows. The chart is one node, as the
  other charts, with the custom actions **Previous week** / **Next week**; its description: "Effort, last 12 weeks. This week so
  far 97, below your usual range of 334 to 511. Last week 395, within. Highest 579, week of 12 Oct." The verdict and the week line
  are a polite live region. The days are one node: "Monday 97, Tuesday 0, Wednesday, today, …". Rows are buttons: "Threshold
  2×20, Monday 16, load 97, 100% of the week, opens the ride".
- **Colour.** The skill colours never carry meaning alone (names, spans, gaps and order, §6.11); Effort uses no hue at all.
- **Progress (TalkBack).** Reading order: back bar, range selector, "Power" heading, its cards, "Heart rate" heading and the
  caution, its cards. In each card: name, verdict, numbers line, then the chart as one node (custom actions **Previous point** /
  **Next point**; the readout is a polite live region) whose description reads the range and the trend in numbers: "Heart rate at
  180 watts, last 6 months, weekly. 146 in the week of 1 June, 140 in the week of 16 November; 3 weeks without a figure. Trending
  down: fitter." Best power: "Best power by month, last 6 months. 5 minutes from 305 to 326 watts, 20 minutes from 262 to 281, 60
  minutes from 236 to 247, no 60-minute ride in August." A stacked pair is one node with both series. The "Latest" pill is "Back to
  the latest point".
## 9. Performance

- **Lists** (`Rides`, `Notes`, Values tables, Settings is short) are `LazyColumn`s with stable keys (ride id, note id, date);
  month and day headers are `stickyHeader`s. Paged by 50 rows / 30 days.
- **Screens** with several cards are one `LazyColumn` too, each card one item, so off-screen charts are not composed.
- **Charts** are drawn with Compose `Canvas` inside `Modifier.drawWithCache`: paths, ticks and text layouts are built once per
  (data, size, range) and cached; the selection (crosshair, dots) is drawn in a separate `drawWithContent` layer that reads the
  selection `State` in the draw phase, so scrubbing re-draws but never re-composes or rebuilds paths. Only the readout row
  recomposes while scrubbing, and only when the snapped x changes.
- **Data size:** the service sends what is drawn: ≤ 366 daily points per series, ≤ 53 weeks, 25 curve points, ride series at
  10 s (≤ 600 points per hour). Decimation happens on the service, not the phone.
- **No continuous animation.** Spinner rings turn only while a fetch runs (max 30 s, then the error state). Nothing pulses.
- **Background:** one WorkManager periodic job a day (Health Connect read + upload + metrics prefetch) plus the read on open
  (spec §7.3). No foreground service, no polling while open beyond the 5-minute refresh on screen entry.
- **Cache:** the last successful answer of every endpoint, keyed by endpoint and range, in one small store (DataStore or
  files); the app opens to cached content without waiting for the network.

## 10. Components

**Reused from `android/ui-kit` as they are:** `OverdriveTheme`, `OdText`, `ValueWithUnit`, `OdIcon`, `OdButton`,
`OdIconButton`, `OdChip`, `ZoneChip`, `OdCard`, `TileGroup`/`StatTile`, `HorizontalRule`, `ValueRow`, `DisclosureRow`, `Notice`,
`SettingRow`, `OdTextField`, `Stepper`, `SegmentedControl` (with a 44 dp segment height parameter), `BackTopBar`, `Spinner`,
`ConfirmSheet` (as the base of the bottom sheet), `WorkoutProfile` (mini variant), `ZoneTimeBars`.

**New** (to `ui-kit`, specified in system.md §13): `BottomNav`, `ScreenHeader`, `StatusLine`, `RangeSelector` (a
`SegmentedControl` preset), `RecoveryCard` with `RecoveryMeter` and `FactRow`, `ChartCard` with `ChartReadout`, `LineChart`,
`ColumnChart`, `SleepTimingChart`, `RideChart` (three stacked plots), `ValuesSheet`, `ProgressBar`. The charts share one
`ChartScale`/selection model so Fitness, Wellness and the ride detail can sync their selections.

**Stage P3-4c adds no component to `ui-kit` except one parameter:** `ChartReadout` takes `rest: RestPill` (**Today** by default, **Latest** on the repeat
page). Everything else is built from the components above (`RangeSelector` for History | Repeats, `ChartCard`, `PlotChart`, `OdCard`, `OdButton`,
`LoadingBlock`, `ErrorBlock`, `EmptyBlock`, `PartialNote`, `StatusLine`, `ValuesSheet`); the step table and the attempt rows are composed in the app.

**Icons:** all from the existing set (`house`, `activity`, `zap`, `moon`, `list`, `settings`, `check`, `triangle-alert`, `pause`,
`heart-pulse`, `info`, `chevron-left`, `chevron-right`, `refresh-cw`, `minus`, `plus`, `x`). None to add.

## 11. Data the screens need

Plain terms; the field names the server agent fixes in `contracts/` win. Every answer carries `generatedAt` (when the service
computed it, UTC) and `stale` (true when an input source failed), as `GET /v1/workouts` does, so the header can say "Updated …".

| Screen | Endpoint | What the screen needs |
|---|---|---|
| Today · recovery | `GET /v1/metrics` (or a `recovery` part of it) | For the latest morning: `date`, `state` (`ready` / `caution` / `rest` / `learning` / `noData`), `reason` (one sentence, rule-generated), `nightsLogged` and `nightsNeeded` while learning, `facts` [{`metric`, `value`, `unit`, `baselineLow`, `baselineHigh`, `deviation`, `streak`}] in decisive order, 3–4 of them |
| Today · planned | `GET /v1/workouts?from=today&to=today` (exists) | Name, workout (for the profile, duration, load and IF; load and IF computed with the current FTP: either by `core` on the phone or added by the service), and for a ridden one the ride id and its end time |
| Today · FTP suggestion; Power · FTP card | `GET /v1/metrics` → `ftp` | `current`, `setAt`, `suggestion` {`watts`, `basedOnRides`, `windowDays`, `createdAt`} or null, `latestEstimate`; answering: `PATCH /v1/profile {ftp}` for Use, and a way to dismiss a suggestion (new: e.g. `POST /v1/ftp-suggestion/dismiss`) |
| Today · note; Notes | `GET /v1/insights?from&to` | [{`id`, `kind` (`morning` / `ride` / `week`), `createdAt`, `text`, `rideId`}] |
| Fitness | `GET /v1/metrics?from&to` | daily [{`date`, `load`, `ctl`, `atl`, `tsb`}]; weekly [{`weekStart`, `load`, `rideLoad`, `otherLoad`, `rides`, `activities`}]; tiles need today and the values 7 and 28 days ago (from daily) |
| Power · curve and best efforts | `GET /v1/metrics?from&to` → `powerCurve` | for the range and for all time: [{`durationSec`, `power`, `date`, `rideId`}] at the 25 durations of §6.4 |
| Power · FTP history | → `ftp.history` [{`date`, `ftp`}] incl. the value in force at `from`; `ftp.estimates` [{`date`, `eftp`}] |
| Power · drift | → `drift` [{`date`, `rideId`, `name`, `driftPercent`}] |
| Wellness | wellness metrics endpoint (to come; `GET /v1/metrics` or `GET /v1/wellness?from&to`) | per day: `restingHr`, `sleepSeconds`, `sleepStart`, `sleepEnd`, `respiratoryRate`, `spo2`, `sleepingHr` (null where missing), and per metric per day `baselineLow`, `baselineHigh`, `deviation`, `streak`; usual bedtime and wake ranges; `nightsLogged` |
| Wellness upload | `POST /v1/wellness` | Batched Health Connect records (resting HR, HR samples, sleep sessions with stages if present, respiratory rate, SpO₂, exercise sessions with their HR samples); answer = counts accepted, so Settings can say "Sent 214 watch records" |
| Rides | `GET /v1/rides?before&limit` (or `/v1/activities`, spec §6.7) | [{`id`, `kind` (`ride` / `activity`), `name`, `sport` (activities), `start`, `durationSeconds`, `load`, `loadApproximate`, `averagePower` (rides), `averageHeartRate`}], newest first, paged; month totals can be summed on the phone from the page or sent |
| Rides · Repeats | `GET /v1/repeats` (stage P3-4c) | per session: `hash`, `name`, `attempts`, `lastDate`, and `comparison.heartRateAtPower` {`latest`, `previous`, `difference`, `reason`}; most recently ridden first |
| Repeat detail | `GET /v1/repeats/{hash}` | `name`, `note`, `definition`, `steps`, `comparedSteps`, `attempts` [{`rideId`, `number`, `date`, `durationSeconds`, `averagePower`, `heartRateAtPower` {`value`, `reason`}, `stepHeartRates`, `driftPercent`, `compliance`}], `trends` {`heartRateAtPower`, `driftPercent`, `compliance`: `direction`, `change`, `attempts`, `minAttempts`, `steadyBelow`} |
| Import | `POST /v1/activities/fit` (stage P3-4c) | the FIT file as `application/vnd.ant.fit`; the answer's `status` (`imported` / `duplicate`), `reason`, and `summary` (date, duration, distance, average heart rate, load); errors `invalid_fit`, `unsupported_sport`, `empty_activity`, `payload_too_large`, `unsupported_media_type` |
| Ride detail | `GET /v1/rides/{id}` | `summary` (§8.2 names), `laps` with `targetPower` per lap (from the plan), `series` at 10 s [{`t`, `power`, `heartRate`, `wBal`}], `secondsInZones[7]`, `plan` {`workout` as stored, `stepsRidden`, `stepsPlanned`, `effortRange`, `effortTarget`} or null, `ftp`, `thresholdHr`, `cp`, `wPrime`, `insightId` |
| Activity detail | same, `kind: activity` | summary (duration, avg/max HR, load, `loadApproximate: true`), `series` [{`t`, `heartRate`}], `thresholdHr` |
| Power · Power skills (charts stage) | `GET /v1/metrics` → `powerSkills` (or its own `GET /v1/power-skills?from&to`) | for the window (`from`/`to` local days, default the last 56 days) **and** for all time, at the 12 durations of §6.11: [{`durationSec`, `watts` (or null), `date`, `rideId`, `rideName`}]; `rideCount` in the window; tablet rides only (spec §11.8); the weight used, `riderWeight` {`kg`, `date`} or null (from the profile, spec §12.5); per duration `score` (the level score, null without weight) and per skill `level` (1–9, null without weight), so the phone never holds the table; `table` (`men` / `women`) |
| Power · Power skills, picker dots | `GET /v1/rides` (exists, cached) | the start days of the rides, to dot the calendar; no new field |
| Profile (charts stage) | `GET/PATCH /v1/profile` | **new optional field** `powerProfileTable`: `men` (default) or `women`, which level table the service uses. Until it exists the service uses `men` and the closing note stays true ("published tables") |
| Fitness · Effort (charts stage) | `GET /v1/metrics` → `effort` | 12 weeks, oldest first (and up to 52 for Values): [{`weekStart`, `load`, `approximate` (any part from heart rate), `usualLow`, `usualHigh` (null while learning), `verdict` (`above` / `within` / `below` / `none` / `learning`), `current` (true for the partial week)}]; `weeksLogged` and `weeksNeeded` (8) while learning; for each week its `days` [{`date`, `load`}] (7) and `activities` [{`id`, `kind` (`ride` / `activity`), `sport`, `name`, `date`, `load` (or null when there was no heart rate and no power), `approximate`, `share` (0–1 of the week, null when `load` is null)}]. The service computes every number and the verdict (the current week's `below` means "below so far") |
| Progress (charts stage) | `GET /v1/progress?from&to` (new) | see the field list below the table |
| Settings · heart rate | `GET/PATCH /v1/profile` | **new profile fields:** `maxHeartRate`, `thresholdHeartRate` (bpm or null) and their estimates from rides (`estimatedMaxHeartRate`, `estimatedThresholdHeartRate`, read-only) |
| Settings · last sync | local | time of the last successful exchange, waiting record count, last failure cause |

**Progress (`GET /v1/progress?from&to`, charts stage).** Every block carries `trend` {`direction` (`improving` / `worsening` /
`steady` / `unknown`), `betterWhen` (`higher` / `lower`), `points` (with a figure), `minPoints`, `change` (signed, in the unit),
`from` (the value at the range's start, for the numbers line), `latest`, `since` (date), `steadyBand`}, as the repeat trends do.
Plus `generatedAt`, `stale`, `range` {`from`, `to`}, and `headline` {`measure`, `direction`} for the entry row (null when no
measure has a verdict).

- `bestPower`: `months` [{`month` (YYYY-MM), `partial`, `d300`, `d1200`, `d3600`: each {`watts` or null, `wattsPerKg` or null,
  `date`, `rideId`, `rideName`}}]; a `trend` per duration and an overall `trend`; `weightKg` used (or null).
- `aerobicEfficiency`: `referencePower` (W), `referenceHeartRate` (bpm), `weeks` [{`weekStart`, `heartRateAtPower` or null,
  `powerAtHeartRate` or null, `stretches`, `seconds`}]; a `trend` for each of the two.
- `efficiencyFactor`: `rides` [{`date`, `rideId`, `name`, `value`, `normalizedPower`, `averageHeartRate`}], `rolling5`
  [{`date`, `value`}], `trend`.
- `decoupling`: `rides` [{`date`, `rideId`, `name`, `driftPercent`}], `rolling5`, `trend` (the data of §6.6, moved here).
- `restingHeartRate`: `weeks` [{`weekStart`, `mean` or null, `nights`}], `trend`.
- `weight`: `weeks` [{`weekStart`, `kg` or null, `weighings`, `ftp`, `wattsPerKg` or null}], `trend` (of W/kg), `change` of weight
  (reported, not judged).

The service owns every rule in the table of §6.13 (steady stretch, references, minimum counts, bands); they belong in the spec and
the contract with these names or better ones.

## 12. Tokens

**Charts stage: still no additions.** Power skills uses the zone colours (as power data, §6.11), text 1/2/3, surface 1/3,
`border`, `borderStrong`; Effort uses only neutrals. Sizes specific to the two charts (ring 4 dp, level bar 6 dp, label column
54 dp, chart heights) live in this file and as `ui-kit` constants beside the chart code, as the other chart geometry does.

**No additions to `tokens.json`.** Every colour, size and style above exists already; the companion-only geometry (segment
44 dp, chart heights, gutters) lives in this file and in `ui-kit` constants next to the chart code. Adding a token would also
regenerate `OverdriveTokens.kt`, which belongs to the implementation stage, not this design pass.

## 13. Mock-up index

`design/mockups/companion/` (open any page in a browser; `png/` holds the renders at 2×, reduced to 256 colours):

| Page | Frames (`png/<page>--<frame>.png`) |
|---|---|
| `today.html` | `today` (Ready), `today-caution` (Caution, offline, FTP suggestion), `today-rest`, `today-learning`, `notes` |
| `fitness.html` | `fitness` (6 months), `fitness-selected` (6 weeks, past day), `fitness-year` |
| `power.html` | `power` (suggestion, 20 min), `power-selected` (5 min, no suggestion) |
| `wellness.html` | `wellness` (4 weeks), `wellness-selected` (past day, no SpO₂) |
| `rides.html` | `rides`, `ride` (detail), `ride-selected`, `activity` |
| `settings.html` | `settings`, `settings-problems`, `onb-connect`, `onb-health`, `onb-partial`, `onb-background`, `onb-sync` |
| `states.html` | `loading`, `error`, `stale`, `empty`, `values` |
| `skills.html` (charts stage) | `skills` (8 weeks, weight known, top of the Power tab), `skills-360` (360 dp wide), `skills-axis` (5 min sheet), `skills-skill` (Climb sheet), `skills-noweight` (share of best, no levels), `skills-custom` (custom range with 45 and 60 min gaps), `skills-picker` (date range sheet), `skills-states` (loading, no rides, list fallback, empty custom range, offline) |
| `progress.html` (charts stage) | `progress` (6 months, every card), `progress-entry` (the row at the end of Fitness), `progress-states` (too few points, no watch data, no weight, offline, nothing yet) |
| `effort.html` (charts stage) | `effort` (Fitness top, this week at rest), `effort-selected` (a past week, approximate watch load), `effort-states` (learning, no load, nothing yet this week, activity without heart rate) |

Render: `NODE_PATH=$(npm root -g) node design/tools/render-mockups.mjs companion/` (set `CHROMIUM_PATH` to use a browser
binary other than Playwright's own). Shared pieces: `companion.css`, `companion.js` (shell, chart geometry, synthetic data).

## 14. Implementation note (charts stage: Power skills and Effort)

For the implementer of §4.14, §4.15, §6.11 and §6.12. No new token; no new icon (`x`, `chevron-left`, `chevron-right`,
`heart-pulse`, `bike` exist).

**Reuse as they are.** `ChartCard` (heading + Values; Power skills passes 12 dp spacing through a parameter or wraps its body in
its own `Column`), `ValuesSheet` (both table twins), `RangeSelector` (the three window segments), `StatusLine`, `LoadingBlock`,
`EmptyBlock`, `OdButton` (Change, Show, Cancel), `OdIconButton` (`x`, chevrons), `ConfirmSheet` as the base of the detail and
picker sheets (as the Values sheet does), `HorizontalRule`, `OdText`, `ValueWithUnit`. The weekly columns of Effort are
`ColumnChart` with two additions: a **flat band layer** behind the columns that reads the selection (the selected week's
`usualLow`/`usualHigh`, so the band moves in the draw phase like the selection, without recomposition) and the **"Now"** tick.
`ColumnSpec` already has `starts`, `values`, `selectXs` and `restX`; add the per-week band arrays. The seven-day strip is a small
`Canvas` (or `ColumnChart` without gutter, ticks or selection; seven fixed slots). Effort's selection is its own `ChartSelection`,
not the Fitness one.

**New: `RadarChart` in `ui-kit/chart`.** Input: a `RadarSpec` built once per (data, window) by the screen model, compared by
identity: 12 `labels` (duration, watts text), `main` and `reference` radii as `DoubleArray` (0..1, NaN = no data), `rings`
(fractions), `segments` (start axis, end axis, colour), `wash` (bool), `heightDp` computed from the width. The screen model, not
the chart, turns watts into radii (level score or share of best), so the chart knows nothing about power. What it needs:

- **Axis angles:** `θᵢ = −π/2 + i·π/6`; point `(cx + cos θ·r·Rp, cy + sin θ·r·Rp)`; r is clamped to `[6 dp / Rp, 1]`.
- **Label layout:** measure every label once with `rememberTextMeasurer` inside `drawWithCache`; the label column is the widest
  label (+4 dp), so 1.3× font scale shrinks Rp instead of clipping. Place each block per §6.11 (horizontal anchor by cos θ, vertical
  shift 15 dp × sin θ). The top (i = 0) and bottom (i = 6) blocks are the collision risk: they are centred and pushed wholly outside
  the ring; assert in a test at 296 dp width and 1.3× that no two label rectangles intersect and none leaves the canvas.
- **Ring arcs:** `drawArc` with `style = Stroke(4.dp)` on the circle of radius `Rp + 6 dp`, start angle `θ(first) − 15°` plus half
  a 2 dp gap in degrees (`gap° = 2 dp / (Rp + 6 dp) × 180/π / 2`), sweep `30° × axes − gap°`. Butt caps (a round cap would eat the
  gap).
- **Outlines:** one `Path` per outline with `moveTo` after a NaN (a gap), closed back to the first point only when both ends have
  data; the wash `Path` only when no value is NaN. Draw order: rings and spokes, arcs, wash, reference outline, main outline,
  selection.
- **Hit testing:** in `pointerInput`, a tap at (x, y): `i = round((atan2(y − cy, x − cx) + π/2) / (π/6)) mod 12`, accepted
  anywhere in the radar's box; this gives every axis a wedge wider than 48 dp at its label. Call `onAxis(i)`; the selection
  (`Int?` state) is read in the draw phase, like `ChartSelection`.
- **Semantics:** one node, `contentDescription` = the text alternative of §8 built by the screen model; no per-axis children.

**Level bar** is a `Row` of nine `Box`es (or one small `Canvas`), weight 1, 6 dp, 2 dp spacing, the end radii of §6.11, cleared
from semantics. **The date range sheet** is built in the app from `ConfirmSheet`, a `LazyVerticalGrid`-free fixed 7-column layout
(one month is at most 6 rows, so a plain `Column` of `Row`s), and the cached rides list for the dots. Use `java.time` local dates;
the window sent to the service is `from`/`to` inclusive local days.

**Performance.** Both charts are drawn once per (data, size): paths, arcs and text layouts live in `drawWithCache`; a tap changes
one `State` read in the draw phase, so the radar never recomposes on selection and Effort recomposes only its verdict, week line,
days and rows when the selected week changes. **No continuous animation**: no draw-in of the outline, no morph when the window
changes (the new shape replaces the old at once, like every value change, system.md §9), the sheets use the standard sheet motion
and are instant with animations off. Each card is one `LazyColumn` item, so an off-screen radar is not composed. The data is tiny
(12 + 12 points, 12 weeks, ≤ 7 days, ≤ ~15 activities).

**Testing.** Pure functions in the app's model (`PowerSkillsUi`, `EffortUi`, as the other `*Ui` files): radii from watts in both
scales, the level words, the 3-durations threshold, gaps, the custom range line, the verdict words for past and current weeks, the
shares that ignore activities without load, the TalkBack strings. Compose screenshot tests of every frame in §13's new rows.

**Progress (§4.16, §6.13).** A pushed screen in the app's navigation like Notes, one `LazyColumn` with one item per card. Every
chart is the existing `PlotChart` in a `ChartCard`: `LineLayer` (weekly and monthly lines; NaN is a gap), `DotLayer` plus a
connected `LineLayer` for the ride measures (exactly the drift card's layers, which move here), `BandLayer` for "Steady". The
stacked pairs (aerobic efficiency, weight) are two `PlotChart`s under one readout, each with its own `ChartSelection` kept equal
(selecting in one selects the same week in the other). Best power's x is months at mid-month on a day axis, with `marked` dots on
every point. The readout's rest pill is **Latest** (`RestPill`, stage P3-4c). No new component, no new token, no animation. The
words of §7.9 are pure functions in a `ProgressUi` model with tests, like `RepeatsUi`.

### 14.1 Where the built screens differ from the design (release 0.4.1)

The screens of §4.14 and §4.15 were built from this file and the mock-ups and rendered with the app's Robolectric shots (`app-companion/build/shots/charts--*.png`)
beside the PNGs. They match in layout, text and colour. These are the places where the build had to decide something, each with its reason:

- **Radar labels at a large font (§6.11 geometry).** The rule "centre moves by 15 × sin θ" leaves two neighbouring blocks on one side closer than a block is tall once the text
  is 1.3× (44 dp against about 40), and they are aligned on nearly the same x. `RadarGeometry` therefore pushes the blocks of one side apart by 2 dp (the ones above the middle
  up, the ones below down) and grows the canvas to hold them; the radius still shrinks with the measured label column. At 1× nothing moves. A test checks that no two blocks
  intersect and none leaves the canvas at 296 and 346 dp, at 1× and at 1.3× (`RadarGeometryTest`).
- **Effort's axis** is 0 to the largest of the twelve loads and of every week's usual high, not of the selected week's, so that the axis does not move while a week is selected.
- **Effort, learning:** the week line (without the usual range) stays between the verdict and the sentence "Your usual range appears after 8 weeks of training. 5 so far.", so that the
  selected week's load is stated and not only drawn.
- **Effort, a cache from an earlier week:** the pill that returns to rest says **Latest** (not "This week") and there is no "Now" tick, the phone's clock decides which week is
  in progress.
- **Power skills, a window without rides** other than Custom has its own sentence ("No rides on the tablet in the last 8 weeks. Choose All time or a custom range."), and
  the card's "No rides yet" is shown only when no ride ever had a power curve.
- **Values sheets** head the Custom column "Range" (the fixed 76 dp column cannot hold a date range); a custom range longer than three years restarts the picker from the tapped day.
- **Segmented control:** equal segments take 6 dp of side padding (was 16) and never wrap, so "8 weeks" holds at 360 dp and 1.3×. The Fitness and Power range selectors are unchanged otherwise.
- **Settings** gains a **Power skills** group with the **Power profile table** (Men / Women) and one sentence that levels are approximate (§4.9 had no such row; ADR 0042 "The UI stage").
- **In the states without content** (loading, error, empty) Fitness and Power keep the range selector where it was; it moves under the new cards only when there is content.
