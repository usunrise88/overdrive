# ADR 0024: The companion app

Status: accepted (Phase 3, stage P3-4a, 2026-11-18). P3-4b adds the watch data (Health Connect, wellness, recovery).

## Context

Spec §6.1 puts the statistics on the owner's phone. `design/companion.md` specifies every screen, state and chart; `design/system.md`
§13 names the new components. P3-4a builds everything of it that reads the **service**: the five tabs, Fitness, Power, Rides, ride
detail, Settings and the first run's connect step, with the data layer under them. Health Connect, the wellness figures and the recovery
rating wait for the wellness contracts (P3-2) and come in P3-4b; this stage leaves the seams for them. The rules of `CLAUDE.md` hold:
no Google Play Services and no Firebase (the companion is a plain `HttpURLConnection` client of our own service), nothing secret in the
repository, the phone never talks to anything but our service, and Strava is not involved at all.

## Decision

### 1. Layers

| Where | What |
|---|---|
| `core/remote` | `ServiceClient` (the one way to the service), `RemoteResource<T>` (one cached answer), `MetricsRepository` (per range), `RidesRepository` (newest page and older pages by cursor), `RideDetails` (per ride id), `FtpSuggestionAnswers` (the queued dismissal), `ServiceProblem`, `CacheText` (the cache's text format). Pure Kotlin, tested on the JVM. |
| `core/sync` | `HttpPostTransport`: a `POST` next to GET, PUT and PATCH, with the same secrecy rules (`HttpPostRequest` prints neither the token nor the body). |
| `core/plan`, `core/metrics` | `Workout.plannedLoad()` (IF² × hours × 100, the figure a planned card shows) and `FormWord` (Fresh, Neutral, Training hard, Very fatigued: one function for the phone and the assistant). |
| `android/data` | `UrlConnectionTransport.post` (the PATCH and POST share one helper), `FileTextStore` and `FileTextStores` (an atomic file per answer; a bounded folder for ride details), `SecretText` and `ServiceRules` (the address and token rules of the ride app's Settings, copied: see 7). |
| `android/ui-kit` | The charts (`PlotChart`, `ColumnChart`, `RideChart`) over pure geometry (`ChartMath`: clean ticks, linear and log scales, nearest point, decimation), `ChartCard`, `ChartReadout`, `ValuesSheet`, `BottomSheet`, `BottomNav`, `ScreenHeader`, `StatusLine`, `StackedNotice`, `RangeSelector`, `RecoveryCard`, the loading, error and empty blocks. `SegmentedControl` gains `segmentHeight` and `equalWidth`, `Spinner` a `diameter`. |
| `android/app-companion` | `CompanionContainer` (the object graph, no DI framework, like the ride app's), the screens' pure models (`TodayUi`, `FitnessUi`, `PowerUi`, `RidesUi`, `RideDetailUi`: every word and number is a unit test), the Compose screens, `NavState`. |

### 2. One resource, one file, one state

`GET /v1/metrics` for each range, `GET /v1/rides`, `GET /v1/rides/{id}?series=true` and `GET /v1/workouts` are each a `RemoteResource`:
the cache is read first (`load`, also lazily before the first request), a request follows, and the screen reads one `ResourceState`
(`value`, `fetchedAtMillis`, `problem`, `refreshing`). Loading, refreshing, fresh, stale, error and empty are all derived from it
(`screenOf`), so no screen decides them on its own. The profile is the ride app's `ProfileRepository` as it is (ADR 0021): the FTP in
force, and the edit that goes to the service.

- **Cache file** (`filesDir/cache/<name>`): `overdrive-cache 1 <kind> <address checksum> <fetched at> <CRC-32 of the body>` then the answer
  exactly as the service wrote it. Reading is strict: another kind, another version, a body that fails its checksum, a body that is not a
  valid answer of the contract, an answer for another address: all read as **no cache**, never as an exception and never as half an answer.
  The file holds the rider's figures and a checksum of the address; the token is in no file but the settings.
- **Written before it is published.** The answer is written (atomically, not cancellable) and only then published to the state, so a screen
  that has seen an answer can rely on it being on disk. This removes by construction the race that cost a release in the ride app, where
  the cache was written on a coroutine that `close()` cancels. The container tests still wait for the disk before they read or close.
- **When it asks.** At start (Today's resources), when a screen opens, when the rider pulls down, on Sync now, and once a changed address or
  token has stayed as typed for 1.5 s. A screen opening asks each resource **at most once a minute** (the brief of this stage; the design
  says five minutes, a constant, `RemoteResource.MIN_GAP_MILLIS`); a pull down and Sync now always ask. One request per resource at a
  time: a request that arrives during one is dropped, which is the rule of the ride app's repositories and means that a Sync now tapped in the
  middle of a failing attempt does nothing; the failure is on screen and the rider taps again.
- **A refused token** (401) is remembered with the address and token it was refused for, in the one `ServiceClient`: until either changes or
  the rider asks, every resource answers "token refused" without going out, so a wrong token costs one request.
- **Failures.** `ServiceProblem` carries a kind, the ride app's plain words (`plainReason`, ADR 0014), the transport's technical hint and the
  HTTP status, and **no secret**. `ProblemWords` turns the kind into the design's sentence (§7.5): "No network connection. Pull down to try
  again.", "The phone could not find <host>. ...", "The service did not accept the token. Check it in Settings." The status line says
  "Offline" for no network and "Out of date" for everything else; a kind that has no designed sentence (secure connection, a refused
  request, a missing route, not our service) uses the plain words.
- **Rides.** The newest page (50) is a resource with its cache. Older pages come by `nextBefore` (the service's cursor), are kept in
  memory and dropped when the newest page is fetched again. The month's "Load 593" is summed from the pages fetched so far, so a month
  that is cut by the end of the loaded pages shows its partial total until the next page arrives.
- **Ride details** are one resource per ride id, one file per ride in a folder bounded to 24 files (the oldest go first), eight kept in memory.

### 3. The FTP answers

**Use 256 W** puts the FTP on the profile at once (`ProfileRepository.edit`) and sends it with `PATCH /v1/profile`; offline it stays as the
profile's unsent edit (on disk, shown as waiting, sent at the next refresh), and the one line "FTP is now 256 W. The tablet uses it from its
next sync." replaces the notice until the next visit to Today. **Keep 250 W** hides the suggestion at once and sends
`POST /v1/ftp-suggestion/dismiss`; the answer is written down **before** it is sent (`filesDir/cache/ftp-answers`, one line per
suggestion: `7 pending`, `7 sent`, the last 20 kept, for one address) so that it survives a restart and an offline spell, and a suggestion
the rider dismissed stays hidden even when the cached metrics still hold it. A 4xx other than 401 settles an answer (the suggestion is gone).
A suggestion that the profile already has (an FTP of 256 W in force) is no longer one. Power's **Set FTP** (no FTP on the service) is the same
edit from a stepper sheet.

### 4. Connection and first run

Settings holds the service address and the token in DataStore, like the ride app; the token is a `SecretText`, which prints as dots and
compares by value, so a state dump, a log line or a failing assertion cannot leak it. The address takes the ride app's rules (a host, no
user name or password, at most 2048 characters) and may be typed without its scheme: `https://` is added. **Connect** makes one
`GET /v1/profile` with the typed values and stores them only if the service answers; a refused token is said under the token field, any
other failure under the address field with the design's sentence and the hint. A plain `http://` address is accepted and flagged "Not
encrypted: the token is sent as plain text" next to the field. Only a valid address is stored while typing; one that is not keeps what was
typed and says why.

**Cleartext.** The manifest has `usesCleartextTraffic="true"`, the same decision as the ride app's (ADR 0014 sections 6 and 8): the bench
runs the service on the local network, the flag in Settings says so, and the app reaches nothing but the address the owner typed. Removing
the attribute makes both apps https-only. Not changed here.

### 5. Charts

Drawn with `Canvas` in `drawWithCache` (design §9): paths, tick text and band rectangles are built once per (spec, size) and the lambda is
`remember`ed on the spec, so a recomposition for a new readout does not rebuild them. The **selection** is a `ChartSelection` (one x per
screen where charts share an axis) read in the **draw phase** for the crosshair and the dots and in composition only by the readout row,
so scrubbing redraws and never rebuilds a path. A tap selects the nearest day, a hold of 250 ms (a `ViewConfiguration` of 250 ms around the
chart) and a slide scrubs and snaps, with `TextHandleMove` on each snap; a vertical scroll without the hold is the page's. Each chart is one
semantics node with its readout and summary as the description and **Previous point** / **Next point** actions; the readout is a polite
live region (the text only: the **Today** pill is a control of its own, "Back to today"); the **Values** sheet is the table twin, a lazy list.
Daily series are at most 366 points and rides' series at 10 s; `ChartMath.decimate` keeps the lowest and highest of each pixel column if
more than two points per pixel ever arrive.

Choices where the design left room: the y gutter is 32 dp, 40 dp where a label has four digits (the power curve); a free-ride step in the ride's
power plot is surface 3 (not blended: surface 3 on surface 1 would not show); the power curve's range line has gaps where the range has no
effort that long and the readout says "—" there; two steady rides on one day are two dots and the selection snaps to the later; the weekly
chart's last column label and the selected one are drawn clamped to the card.

### 6. What is not here yet, and how it looks

- **Recovery** is `WellnessRepository.recovery`, a `RecoveryState` (not available yet, learning, rated, no reading) that the Today model maps to
  the card. Everything but "not available yet" is built, drawn in screenshots and tested, so P3-4b only has to produce it. Until then Today
  draws the design's learning form without invented figures: "Learning your usual range" with an empty bar and "Overdrive rates recovery after
  14 nights with the watch. The watch is not connected yet."
- **Wellness** is the design's empty state: "Overdrive cannot read Health Connect yet." with no button. **Notes** (insights) are not drawn: the
  design says a card with no note is not drawn, and there is no `GET /v1/insights` yet. The Notes screen and the **All notes** row come with P3-3.
- **Settings** omits the Health Connect and Heart rate groups: the design shows no "coming" state for them and the profile contract has no heart-rate
  fields yet. The first run has the connect step only; `ONBOARDING_STEPS = 1` hides "Step 1 of 3" and P3-4b makes it 4 (the design's three steps and the
  first read).
- **Activity detail** shows what the list already says (duration, average heart rate, load with its note, "Counts toward fatigue...") until the watch
  data arrives; its heart-rate chart is P3-4b. There is no activity in the list before then.
- **Done** on Today's planned card matches today's ride **by the workout's name** from the rides list (the contract's list item has no workout id);
  the service's `get_ride` carries it and a later contract can make this exact.

### 7. Decisions about the code

- **No navigation library.** `NavState` is a selected tab and a back stack per tab, 70 lines, kept in the container so a recreated activity shows the same
  screen; it is smaller than a dependency and the design needs nothing more.
- **No Material.** Pull to refresh is a nested-scroll connection (a downward drag the list did not take, released past 64 dp, asks); it draws nothing, as the
  design says: the header says "Updating…".
- **`SecretText` and `ServiceRules` are copied into `android/data`** instead of moved, because the ride app's screens are not touched in this stage; the two
  apps keep separate settings files. Moving the ride app over is a later change with no behaviour in it.
- **Locale.** Dates and times use the phone's zone and locale (`Words`, `Clock`), so a phone in German says "Mi. 18. Nov."; the design's copy is the English one.
- **Insets.** The activity is edge to edge; the root pads for the status bar and the bottom navigation for the navigation bar (80 dp with a 16 dp
  minimum inset, as designed).

## Tests

JVM unit tests: `ChartMath` (ticks, scales, nearest point, decimation), the cache format, the repositories against a scripted transport, the POST transport against
a local server, the file stores, every screen model (words, numbers, states, the design's mock-up values). Robolectric: a screenshot of every screen and state into
`app-companion/build/shots` (never committed), the charts' touch on the real charts, the container against a local stand-in of the service on the loopback address
(real threads, real files, real sockets: the first run, fetching and keeping, the offline restart, paging, a refused token, the FTP answers online and offline and
across a restart, secrecy of the token in every file, state and log line), and a Compose flow through the tabs. Every wait is for a condition.

## What only a phone settles

See `docs/bench-checklist.md`, "Companion": the feel of the 250 ms hold against the system's scrolling, the haptic tick, TalkBack's reading of a chart and its two
actions, the 1.3x font scale and a 360 dp width, the pull down, how the charts scroll with a year of data on the Xiaomi 14 Ultra, the edge-to-edge insets under
HyperOS's gesture bar, the keyboard on the connect step, and a cleartext address on the owner's network.
