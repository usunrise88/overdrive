# ADR 0028: The companion's repeated sessions and the FIT share target

Status: accepted (Phase 3, stage P3-4c, 2026-11-19). Builds on ADR 0024 (the companion as built: container, `RemoteResource`, caches, screens),
ADR 0026 (the service's side of repeats and outdoor rides) and ADR 0027 (the outbox pattern of the watch batches).

## Context

Two additions of the owner's second spec reach the phone in this stage. **The repeat-session page** (spec §11.7): riding the same workout again is the
cleanest measure of progress, the service detects repeats by the hash of the workout's steps and computes, per attempt, the heart rate at the same
power, the aerobic drift, the compliance and their trends (ADR 0026); the phone shows every attempt side by side. **The FIT share target** (spec §11.8,
path A): from spring the owner rides outside with a Geoid CC600 and OnelapFit exports the ride as a FIT file that Android can share to other apps; the
companion takes it and sends it to `POST /v1/activities/fit`. The rules of `CLAUDE.md` hold: the phone talks only to our service, there is no Play Services or
Firebase, **every number is computed by the service** (the phone words a trend, it does not compute one), Strava is never involved, the repository is public (every
FIT file in a test is synthetic, every figure invented) and health data is never in a log line.

Written **without a real OnelapFit export and without two real rides of one workout**: the FIT files are written by the tests (a header and anything after it), the
service is the loopback stand-in of ADR 0024 extended with the repeats and the import. The last section lists what only a real file and a real phone settle.

## Decision

### 1. Layers

| Where | What |
|---|---|
| `core/remote` | `RepeatsRepositories`: `repeatsResource` (`GET /v1/repeats`) and `RepeatDetails` (`GET /v1/repeats/{hash}`, one resource per hash), both `RemoteResource`s of ADR 0024 |
| `core/sync`, `core/remote` | `HttpPostBytesRequest` and `HttpPostBytesTransport`: a `POST` of bytes with a content type, same secrecy as the JSON POST (the token and the body are never printed); `ServiceClient.postBytes` returns the service's own error code with a failure (`Outcome.Failed.code`) |
| `core/outdoor` | `FitFileCheck` (what is certainly not FIT, the type-and-name rule), `FitOutbox` (the interface and `InMemoryFitOutbox`), `FitImporter` (keep, send, park, retry, status) |
| `android/data` | `UrlConnectionTransport.postBytes` (fixed length, in 64 KiB pieces so a cancelled request stops), `FileFitOutbox` and `AtomicBytesFile` (the outbox on disk) |
| `android/app-companion` | `RepeatsUi` and `FitWords` (pure models: every word and number), `RepeatsScreen`, `ImportScreen`, `ImportActivity`, `share/SharedFiles` (the content-resolver reader and the intents), `FitFlushWorker`, the container's wiring |
| `android/ui-kit` | one parameter: `ChartReadout(rest = RestPill.Latest)` |

### 2. Repeated sessions (spec §11.7)

**Data.** The list and each session are `RemoteResource`s with their own disk cache (`cache/repeats`, one file per hash in `cache/repeat-details`, bounded to 24 by
`FileTextStores`); the list is asked when the Repeats view opens, a session when its page opens, each at most once a minute, and both on a pull down and on **Sync now**
(the list; Settings' "Last sync" does not include it, so a service that has not got the route yet does not turn Settings into a failure). Offline, after a restart, the cached
page is shown with "Offline · updated …"; a session the service no longer has keeps what the phone had and says the cause.

**The Rides tab** has a second view, **History | Repeats** (a `RangeSelector` of two segments, kept in the container while the app lives). The list is one card of rows: the
name, "5 attempts · last Wed 18 Nov" and **the latest attempt against the previous in one plain line**: "Heart rate at the same power 144 bpm, 2 lower than last time" (higher,
the same; without both figures the service's own reason). The line never says "fitter": one comparison proves little (spec §11.7).

**The page** (`design/companion.md` §4.12, §6.10): the heart rate at the same power as a chart over the attempts with **the service's `note` under it, as it wrote it**; the
attempts newest first (tap opens the existing ride detail; an attempt with no figure shows the service's reason); the heart rate **per compared step** as a table of steps by
attempts (steps fixed at the left, attempts scrolling sideways, the latest in view); then drift, compliance, average power and duration, each a chart. A chart is a dot per attempt
on `PlotChart` as it is (x is the attempt, equally spaced; a thin line between neighbours; a gap, never a zero, where an attempt has no figure), with one selection for the page, the
readout as the legend, **Values** and TalkBack's two actions like the other charts; the pill says **Latest**.

**The trend is said in words, and only as the service says it.** `trendOf` reads the service's `direction`, `change`, `attempts` and `minAttempts`:

| `direction` | Heart rate at the same power | Drift | Compliance |
|---|---|---|---|
| `improving` | "Heart rate at the same power is trending down: fitter" | "Aerobic drift is trending down" | "Compliance is trending up" |
| `worsening` | "… is trending up" | "… is trending up" | "… is trending down" |
| `steady` | "… is steady" (over "Within 2 bpm over 4 attempts") | "… is steady" | "… is steady" |
| `unknown`, or fewer attempts than `minAttempts` | "Not enough attempts yet" over "2 of 3 attempts have a figure" | the same | the same |

"Fitter" is the one word the spec asks for (lower heart rate at the same power means fitter) and it is said only for the heart rate, only when the service says `improving`, only with at
least three attempts that have the figure (the contract makes that impossible to violate: a trend with fewer attempts must be `unknown`); the second line says how much it moved ("Down 4 bpm over 5 attempts").
The phone draws **no fitted line, no band and no reference line**: computing the intercept or a band would be the phone's own number. Average power and duration have no trend because the
service gives none.

**States** (design §5): loading, refreshing, offline or old with the cause, an error with nothing cached, empty with what to do ("Ride the same workout twice on the tablet and it
appears here, with every attempt side by side."), and partial (a card whose measure no attempt has is its heading and one sentence, with no empty frame; one attempt with a figure draws its dot).

### 3. The FIT share target (spec §11.8 path A)

**Taking the file in.** `ImportActivity` (exported, `singleTop`, portrait) has two intent filters: `ACTION_SEND` for `application/vnd.ant.fit`, `application/x-fit`,
`application/octet-stream` and `*/*`, and `ACTION_VIEW` for the same types on `content://`. The generic types match every share sheet, so the check is the activity's: **a FIT type
is accepted whatever the name; any other type needs a display name that ends in `.fit`** (`FitFileCheck.looksLikeFit`, applied to the intent's type and to the resolver's). The bytes are
read through the `ContentResolver`, **only for `content://`** (a `file://` or any other scheme is refused as unreadable: a path another app names is not a file it shares, and could be one of
this app's own private files), **at most 16 MiB**: a provider that reports more is refused without a read, and the read stops one byte past the cap, so a provider that lies about its
size costs no more than the cap. They go into memory and from there into the app's private outbox: never to a shared or world-readable place, and the name the sharing app gave the file is
used for that one check and kept nowhere (the outbox names are numbers, no log line has one).

**Before anything is kept**, `FitFileCheck.refusalOf` refuses what is certainly not FIT: no bytes, a zip archive (OnelapFit may share its export zipped: said as such), a header that is not FIT
(12 or 14 bytes, `.FIT` at byte 8), over 16 MiB. A refusal here is certain and is never kept or sent; the rest is the service's to judge (`invalid_fit`).

**The outbox** is the watch batches' pattern (ADR 0027) with one file per entry: **kept on disk before it is sent** (written to a temporary file, forced to storage, renamed: a kill at any
instant leaves nothing or the whole file), one file each named by a sequence number, a header with the size and CRC-32 (a damaged file reads as no bytes and is set aside as damaged),
**removed only when the service answered 2xx with the contract's body** (a 2xx that is not `activity-import-response`, a captive portal, is not an acceptance), at most **20 waiting** (the
next is refused with the reason, so a rider offline for weeks cannot fill the phone), a file already waiting is not queued twice, and a **parked** file (set aside with its reason in the
header, never retried) is bounded to the last five (the original is still in OnelapFit).

**What the service's answers come to** (`FitImporter.sendOne`):

| Answer | The file | The rider reads |
|---|---|---|
| 201 `imported` / 200 `duplicate` with the contract's body | removed | "Imported: …" / "Already imported" with the reason (same file, same start and length, overlap) |
| no network, connection refused or cut, timeout, 5xx, 408, 429, **404 `not_found`** (a service that has not got the route yet), a 2xx that is not the contract's | **kept**, backoff | "Saved for later" and the cause |
| 401 (and a token that cannot be a header) | **kept**, not retried until the rider asks or the token changes | "Saved, not sent", **Settings** |
| 422 `invalid_fit` / `unsupported_sport` / `empty_activity`, 413, 415, any other 4xx | **parked** with the reason, not retried | not a FIT file / not a cycling ride / no ride in it / too large / the service did not accept the file type / "The service refused the file (400)." |
| no address or token set up | **kept**, not a failure | "Saved for later", "Overdrive is not connected to your service yet" |

A 404 is kept and not parked on purpose: it is the service saying it does not know the route, which an update fixes, and parking a ride for that would be wrong. The service is idempotent by
the file's bytes, so a file whose answer was lost is sent again and is told "already imported" the second time; the outbox and the service hold the ride once.

**When it is sent.** At once, from the share screen (the rider is looking, so the backoff does not apply; the file is already on disk); by **Sync now** (ignores the backoff and tries a refused
token once more); when the app opens; after a backoff of 30 s doubling to 30 minutes while the app is alive (in memory: a restart tries at once); by `FitFlushWorker`, a WorkManager job
with a network constraint, queued whenever a file is kept (`APPEND_OR_REPLACE`, so a file kept while a job runs gets the next one), with the system's own backoff and four attempts; and by the
daily read at about 06:00, which now also flushes. **No foreground service, no notification, no Play Services.** Settings' **Outdoor rides** row says "N files waiting" and why, and what was
refused. An accepted file makes the Rides list ask again, so the ride is there when the rider looks.

**The import screen** (`design/companion.md` §4.11) is the first run's layout: "Importing…", then "Imported" with the date, duration, distance, average heart rate and load as the service
read them ("Sun 15 Nov, 1:12:04, 31.4 km, average heart rate 142 bpm, load 74 (approximate)."), "Already imported", or one plain cause with what to do. The container does the work in its own
scope, so leaving the screen, rotating or being recreated changes nothing about the file; a recreated activity shows what is there and does not import again.

**The Rides list** shows the imported ride as the activity row the service lists (`kind` `activity`, `sport` `cycling-outdoor`, name "Outdoor ride"), marked by a 16 dp `bike` and the word
"Outdoor" (the watch's `heart-pulse` and "Watch" stay as they are), never by colour; its page says "from a bike computer file".

### 4. Privacy

The token is in the settings only: in no cache or outbox file, no state, no outcome, no problem, no printed request, no exception (`HttpPostBytesRequest.toString` prints the kind and the size,
nothing of the body; `ServiceClient` returns no body in an outcome). A ride's bytes are in the private outbox until the service has them and in no log line; the status the screens read holds counts
and the kind of the last trouble; the summary of an answer (the ride's facts) goes to the screen that asked for it and nowhere else. Tests look for the token and a marker of the bytes in every file
under the cache, every state and every log line (`ShadowLog`), and that nothing of a kept file is outside the private files folder (the cache, the external files and the external cache are empty).

### 5. Decisions about the code

- **A separate interface for bytes** (`HttpPostBytesTransport`), not a change to `HttpPostRequest`: every fake of the JSON POST keeps working, and the secrecy of a body that is a ride is stated where it is declared.
- **`FitImporter` is in `core`** and pure (an outbox interface, the shared client, a clock): backoff, parking, duplicates, the queue limit and a run that sends one file once are JVM tests; `FileFitOutbox` and the Android reading are thin.
- **The container owns the import**, like the watch data: one importer, one outbox, one retry timer, one status flow; the activity only hands over the file and shows the state.
- **No ZIP support.** A zip is said as one; opening the archive is the first thing a real export may ask for (below).
- **Settings' order:** Service, Health Connect, Heart rate, **Outdoor rides**, About: the new row comes after the groups an existing flow test scrolls to.
- **Chart x is the attempt, not the date**: the service's trend is over attempts in the order ridden, and a week's gap and a month's gap are the same step; the readout names the date.

## Tests

- **`core` (JVM):** `FitImporterTest` (kept before sent, the contract's answer, offline with the backoff doubling, a restart finding the outbox, order and stopping at the first failure, 5xx/408/429/404 kept,
  a 2xx that is not the contract's, a refused token not asked again until the rider asks, no service set up, every refusal code parked with its reason and not retried, a refused file not in the way of the
  ones behind it, the parked bound, what is refused on the phone, the same file not queued twice, the queue limit, a disk that refuses, a damaged copy, one run at a time and a file shared meanwhile sent once, no
  token or byte in any outcome, status or request line), `FitFilesTest` (the type-and-name rule, the header, the checksum, `ServiceClient.postBytes`: token, type, error codes, not set up, a refused token), `RepeatsRepositoriesTest`
  (the resources, the cache in the service's words, offline after a restart, a service without the route, another address, a damaged cache, one resource per hash and the memory bound).
- **`android/data`:** `UrlConnectionTransportPostBytesTest` against a local server (the bytes exactly with the FIT type and a fixed length, 5 MiB, every status, redirects not followed, an answer over the limit, a closed port,
  DNS, a timeout, **a cancelled request**, no token or file in any failure or printed request), `FileFitOutboxTest` (order, restart, parked with its reason across a restart, discard, damaged files, foreign files, no temporary file, a stale one swept, an unwritable folder, a 16 MiB file, file names that are numbers only).
- **`app-companion`:** `RepeatsModelsTest` and `FitWordsTest` (every sentence: the list's line, every trend rule, the charts' geometry, readouts and tables, the attempts, the step table, every state; every import
  outcome and every refusal; Settings' row; the outdoor row and page), Robolectric screenshots into `build/shots` (`repeats--*`, `repeat--*`, `import--*`, `rides--outdoor`, `settings--outdoor-*`; never committed), `RepeatsFlowTest` (the Rides tab,
  the page, a tap selecting an attempt, the pill, Values, an attempt opening its ride, Back, the kept view), **`FitContainerTest`** and **`RepeatsContainerTest`** (the real container, real files and sockets against the loopback
  `LocalService`, extended with `GET /v1/repeats`, `GET /v1/repeats/{hash}` and `POST /v1/activities/fit`: stored, duplicate, each error code, no network, a service that is down and the retry, a lost answer, a refused token, a restart, the queue limit, privacy),
  **`ImportActivityTest`** (the intents: an `ACTION_SEND` with a content URI to a synthetic FIT file through a real content resolver, an `ACTION_VIEW`, a non-FIT name, a non-FIT body, a zip, a file too large as its provider says
  and as it really is, exactly 16 MiB, a path instead of content, no service set up, a service that is down, a refused token, the manifest's filters), `WatchWorkTest` (the new job and worker).
- **Every wait is for a condition**, and where a test closes the container or reads a file it waits for the disk as well. **What is asserted is what the rider sees** (the screen's state, the files, the service's holdings), not
  how many requests it took: the container tests count none.
- **Stability:** the new container, intent and flow tests (`FitContainerTest`, `RepeatsContainerTest`, `ImportActivityTest`, `RepeatsFlowTest`: 37 tests per run), the data tests
  (`FileFitOutboxTest`, `UrlConnectionTransportPostBytesTest`: 10 + 8 per run) and the pipeline tests of `core` (`FitImporterTest`, `FitFilesTest`, `RepeatsRepositoriesTest`: 21 + 7 + 9 per run) were run **ten times in a row pinned to two cores**
  (`taskset -c 0-1`) with **three busy loops (`yes > /dev/null`) on the same cores**, the build cache off (`--no-build-cache`) and the test tasks cleaned before each run so every run really ran, on a Gradle
  daemon started under the pin: **ten of ten passed, no failure and no retry** (the logs are not committed). Earlier runs on a free machine found one test bug, one missing safeguard and no race (the scheduler call is now guarded where WorkManager is not initialised, and a provider that throws anything is a file that cannot be read): a test
  file seeded `999` was the same bytes as one seeded `103` (the generator keeps seven bits), and the importer rightly did not queue it twice. Every test waits for what the rider sees or for the disk, and none counts requests.

## Deviations from the brief and the design

- **The design had no repeat page or share flow; they are designed here** (`design/companion.md` §4.11, §4.12, §6.10, `design/system.md` §13.3) in the same language, with no HTML mock-up: the Compose screenshots are the reference.
- **A parked file is not kept for ever:** the last five, because a FIT file is up to 16 MiB and the original is in OnelapFit (the watch batches keep every parked batch).
- **Backoff in memory:** a restart forgets it and tries at once (a file is a few hundred kilobytes, the service answers or does not).
- **A 404 keeps the file** where the brief says 4xx parks it (a service without the route is not a verdict on the file).
- **No notification** when a file goes in the background: the Rides list and Settings say it.
- **The activity label is "Add outdoor ride"**, what the share sheet shows; the app's label stays "Overdrive Companion".

## What only a phone and a real OnelapFit export settle

- **Whether OnelapFit's share sheet offers Overdrive, and what it sends:** the type (`application/octet-stream`, a FIT type, `*/*`), the display name (does it end in `.fit`? if not, the name rule refuses a real file and the header
  alone should decide), whether the file is the `.fit` itself or **a zip** (a zip is refused as such; opening the archive is a small extension), and whether the URI grant outlives the activity (the file is read as soon as the screen opens).
- **Whether the real file decodes** at the service (ADR 0026's list) and what the summary says for a CC600 ride (a distance and a climb, the average heart rate, the load): the import screen shows what the service read.
- **The system's job:** whether WorkManager runs `FitFlushWorker` soon after the network returns on HyperOS with the app closed (the battery settings of the first run's third step apply), and whether the daily job also sends it.
- **The share sheet's behaviour with `*/*`:** Overdrive appears for every file shared to any app; the answer to a photo is the plain "not a valid FIT file". If the owner finds that noisy, the `*/*` filter can go and the FIT types remain.
- **The feel of the repeat page** with real rides: the 3%, 60 s and 120 s rules on the trainer (many attempts with "no figure"?), the trend sentences after three and four attempts, TalkBack's reading of a chart with its trend and of the step table's rows, the 1.3x font, a 360 dp width, the step table's sideways scroll under the gesture bar.
- **Spec §11.7's live line on the tablet** is not part of this stage (stage v0.4.0).

## Consequences

- The companion reads `GET /v1/repeats` and `GET /v1/repeats/{hash}` and sends `POST /v1/activities/fit`; the contracts, the service, the CI workflows and the ride app are untouched.
- `core` gained `outdoor` (`FitFileCheck`, `FitOutbox`, `FitImporter`) and the repeats repositories; `ServiceClient` gained `postBytes` and a failure may carry the service's error code. `android/data` gained the byte POST and the FIT outbox.
- The release APK gains one activity, one worker, no permission and no dependency (`androidx.core`'s `IntentCompat` is already there through the activity library).
- Path B (OnelapFit to Strava to intervals.icu) needs nothing from the phone and is not built.
