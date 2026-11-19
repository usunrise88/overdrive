# Companion app (phone): interface spec

The implementer's spec for `android/app-companion` (Phase 3, spec §6.1). It adds to `design/system.md`; it does not replace it.
Tokens, type styles, components, chart rules (§8) and motion (§9) there apply unchanged unless this file says otherwise. Where
the two disagree about the companion, this file wins; where a mock-up and this file disagree, this file wins.

Mock-ups: `design/mockups/companion/*.html` (open in a browser; they load `../tokens.css`), rendered to
`design/mockups/companion/png/`. All numbers in them are synthetic.

Contents: 1 Frame and rules · 2 Navigation · 3 Shared patterns · 4 Screens · 5 States · 6 Charts · 7 Copy · 8 Accessibility ·
9 Performance · 10 Components · 11 Data the screens need · 12 Tokens · 13 Mock-up index. Stage P3-4c adds §4.11 (the FIT share target),
§4.12 (repeated sessions) and §6.10 (charts over attempts); they have no HTML mock-up, the Compose screenshots of the stage are their
reference (`app-companion/build/shots/repeat*.png`, `repeats--*.png`, `import--*.png`, `rides--outdoor.png`, `settings--outdoor-*.png`).

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
| Notes, an after-ride note | Ride detail |
| Rides, a row | Ride detail or Activity detail |
| Rides, Repeats view, a session | Repeat detail (§4.12) |
| Repeat detail, an attempt | Ride detail of that ride |
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

1. Header "Fitness", "Updated 07:40". 2. StatusLine if any. 3. Range selector.
4. **Tile group** (3 columns, system.md §7.3, padding 12 × 14): **Fitness** 54 with "−3 in 4 weeks", **Fatigue** 43 with
   "−15 in 7 days", **Form** 11 with its word ("Fresh", §7.3). Always today; not affected by the range or the selection.
5. **Fitness and fatigue** chart card (§6.1). 6. **Form** chart card (§6.2) with the note "Fitness minus fatigue. Below −10
   you are training hard; above 5 you are fresh." 7. **Weekly load** chart card (§6.3).

Watch activities are part of the load (spec §6.7), so they are part of all three charts; the weekly readout names their share.

### 4.4 Power (`power--*.png`)

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
| Settings · heart rate | `GET/PATCH /v1/profile` | **new profile fields:** `maxHeartRate`, `thresholdHeartRate` (bpm or null) and their estimates from rides (`estimatedMaxHeartRate`, `estimatedThresholdHeartRate`, read-only) |
| Settings · last sync | local | time of the last successful exchange, waiting record count, last failure cause |

## 12. Tokens

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

Render: `NODE_PATH=$(npm root -g) node design/tools/render-mockups.mjs companion/` (set `CHROMIUM_PATH` to use a browser
binary other than Playwright's own). Shared pieces: `companion.css`, `companion.js` (shell, chart geometry, synthetic data).
