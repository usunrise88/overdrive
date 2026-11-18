# ADR 0008: Ride storage, crash recovery and FIT export on the tablet

Status: accepted (stage M2b-2, 2026-11-18)

## Context

ADR 0007 gave `core` everything pure about keeping a ride: the record's JSON, the crash-safe log (`RideLogRecorder`,
`RideLog.recover`, `FileByteSink`) and the FIT writer. This stage puts them in the ride app (spec §4.2 "Crash safety",
§4.8 "Files are kept in app storage and can be shared or saved to any folder", acceptance criteria 5 and 6): every ride is
written to disk as it happens, stored with its FIT file when it ends, and a ride that was cut off is found again at the next
launch. The questions this ADR settles are where the files live and in what order they are written, how the engine is kept
away from the disk, what the app does with what it finds on launch, and how a file leaves the app.

## Decision

### 1. Files: one folder, a few files per ride, written in one order

- **Where:** `filesDir/rides/`, the app's private storage (no permission, not readable by other apps, `allowBackup` stays
  `false`). The code is `RideStore` in `android/data` (`app.overdrive.data.rides`): plain Kotlin over `java.io.File`, so it
  is tested on a temporary folder with JUnit, and it is where spec §7.1 puts storage ("`data`: local database, outbox, sync
  client"). The sync stub's outbox (spec §4.9, M4) will sit beside it.
- **Per ride, under its id:**

  | File | What | Exists |
  |---|---|---|
  | `<id>.ridelog` | the crash-safe log of ADR 0007 | from the start of the ride until the ride is stored |
  | `<id>.meta` | title, start, duration: a few `key=value` lines (`java.util.Properties`, UTF-8) | from the start (title and start), complete once stored |
  | `<id>.json` | the finished ride record, the wire format of spec §8.2 | once stored |
  | `<id>.fit` | the FIT file | once stored |

  The **title** ("Sweet spot 3×10", "Free ride") is the host's idea, not part of the record, and a workout opened from a file
  is not kept across launches, so it is written down at the start in the small `.meta` file; without it a recovered ride
  would be called "Free ride" or "Workout". It also holds the duration so a list does not have to read rides. Nothing is a
  second source of truth: `.meta` is only ever a copy of the record's facts, and a missing or damaged one is rebuilt from the
  record or the log.
- **Ids.** The engine's ids are `ride-<start millis>`, which are file-name safe and used as they are. Any other id is reduced to
  `[A-Za-z0-9_-]` with a CRC-32 of the original added (`RideStore.idFor`), so two ids never share files and nothing can reach
  outside the folder. A ride whose id already has files is refused rather than appended to.
- **Finalize order and atomicity.** `RideStore.finalize` first builds the JSON and the FIT bytes in memory (so a record the
  FIT format cannot hold fails before the disk is touched), then writes **meta, JSON, FIT** each through `<name>.tmp`
  (written, `fsync`ed, `rename`d with `ATOMIC_MOVE`), syncs the folder (best effort), and **only then deletes the log**. At
  every instant the ride is therefore either in its log or fully stored:

  | The process dies... | Left on disk | The next launch |
  |---|---|---|
  | during the ride | the log, cut | **Interrupted**: asks (a minute or more) or deletes (less) |
  | after the `finished` line, before storing | a finished log | **Unsaved**: stores it silently |
  | after the meta file | log, meta | Unsaved, silently |
  | after the JSON | log, meta, JSON | Unsaved: the FIT file is written again, silently |
  | after the FIT file, before the log is deleted | everything | **Saved**; the scan deletes the log |
  | after the log was deleted | meta, JSON, FIT | Saved |
  | in the middle of a file | the old file and a `.tmp` | the scan deletes the `.tmp`; the old file is whole |

  A ride is **complete** when both `.json` and `.fit` exist. This order is the whole crash-safety argument for storing; a test
  stops the process after each step (`FinalizeStep` hooks) and completes the ride on the next start, and a mutation that
  deletes the log first fails it.
- **No call throws into the UI.** Every call that touches the disk returns `StoreResult.Ok` or `Failed(cause, retryable)`;
  the library turns them into states. `retryable` is false for what no retry can fix (a start the FIT format cannot date, a
  ride without samples, a log with nothing in it).
- **Housekeeping:** every ride is kept, nothing is deleted by age or count. Measured on a synthetic ride of an hour: the log
  593 kB, the JSON 519 kB, the FIT file 72 kB (a real ride with workout fields and the strap's RR intervals is up to 40%
  larger), so a stored hour is about 0.6 to 0.9 MB and a year of daily hour-long rides a few hundred megabytes. There is no history screen in Phase 1 (only the last ride is shown); a
  retention policy belongs with that screen and the sync outbox (spec §4.9), which decides what may be deleted.

### 2. Recording while riding: the engine never waits for the disk

- The host builds `CompositeRideRecorder(disk.recorder, InMemoryRideRecorder())`. `disk.recorder` is an `AsyncRideRecorder`
  in front of a `RideLogWriter`; the in-memory recorder is what the finished summary and the stored files are made from, so
  a ride whose log failed is still stored from memory.
- **`AsyncRideRecorder` is in `core`** (`app.overdrive.core.record`, commonMain, pure coroutines), next to
  `CompositeRideRecorder`: it knows nothing about files or Android, any recorder can sit behind it, and its tests (ordering,
  flush, close, failure, a stalled sink on a real thread) run with the rest of core. The producer's calls are
  `Channel.trySend` on an unbounded channel: they cannot block or fail. One consumer coroutine in the library's scope passes
  the calls on **in order, one at a time**, so `RideRecorder`'s contract holds for the delegate on whatever thread it runs.
  The consumer starts with the first call, so a ride that is prepared and never started costs nothing and leaves no file.
  `close()` ends the queue and waits until everything queued has been written (and the log has its `finished` line, synced
  and closed); `flush()` waits without ending.
- **One thread for the disk:** `Dispatchers.IO.limitedParallelism(1)` for the log writes, the stores and the launch scan. The
  engine's thread (ADR 0005) and the BLE adapters never wait for it. The `fsync` every five samples (ADR 0007) now happens
  there; a stall costs queue memory (a few hundred bytes a second), not ride time. The test `RideHostDiskTest` runs the real
  host and engine at 60 times speed with a sink whose `fsync` blocks: the ride goes on to ten seconds while nothing but the
  header is on disk, and after the release every second is in the stored record. A mutation that made the queue synchronous
  fails it.
- **The log is opened by the writer, on the disk thread, when the ride starts** (`RideLogWriter.onRideStarted` calls
  `RideStore.openLog`), so an `IOException` from `FileByteSink`'s constructor is handled there, not by the engine.
- **A log that cannot be written is one flag.** If opening the file or any later write fails, `RideLogWriter.failed` turns
  true, the first cause goes to logcat (the exception's class and message: no ride values, no heart rate) and the writer
  ignores everything after, which leaves the file a clean prefix. The host republishes `RideUi.logFailed` and the riding screen
  shows **one** notice: "This ride is not being saved. Keep riding. Overdrive tries to save it again when you finish." The
  cause is never on screen. The ride goes on; at the end it is stored from memory, so a disk that recovered saves it.
- `RideHost` still runs on its single thread; `RideRequest.recorder` is unchanged, it is only given the composite.

### 3. Finishing

- The host sees `RideState.FINISHED` (the engine has already called `onRideFinished`, on the same single thread), builds the
  summary as before and starts the save: `RideDiskLog.finish(record)` waits for the queue to drain, then calls
  `RideStore.finalize` with `FitWriter(FitOptions(utcOffsetSeconds = <the device's offset at the ride's start>))`. Every other
  `FitOptions` default is unchanged: the owner's intervals.icu import tunes them (ADR 0007). The offset is
  `ZoneId.systemDefault()` at the **ride's start time**, so a ride that began before a clock change gets its own offset; a
  recovered ride uses the device's zone now.
- The work runs in the library's own scope, **not** the ride's: the rider pressing **Done** while the files are written
  does not stop it (the screen just stops updating). A test cancels the caller mid-save and finds the files.
- **`FinishedRide.save`** is `Saving` (while the files are written; the finished screen shows a neutral spinner and a
  line, **Share FIT file** and **Save to folder** disabled), `Saved(StoredRide)` (the top bar says "Ride saved", the status
  line "Saved on this tablet · 2026-11-18 18-04 Sweet spot 3×10.fit · 312 kB", the two buttons work) or `Failed(message,
  retryable)` (the message in plain words, **Retry** when retrying can help, no file buttons). **Retry** (`RideHost.retrySave`)
  runs `finish` again; it is idempotent. Pressing **Done** on a failed save asks once, "Leave without saving this ride?"
  (**Leave** / **Stay**, the same sheet as finishing a ride), because the in-memory record is dropped; the finished log, if
  the disk was only briefly full, is still on disk and is completed silently at the next launch.
- **The overlay is unaffected.** Storing happens after the snapshot stream has already published `FINISHED`, so the service's
  stop rule and the "bring the app to the front" step (ADR 0006) are untouched, and the save-state republishes change nothing
  the overlay shows: `overlayStates` drops them (tested with four republished summaries giving one overlay state). The
  process stays alive for the few milliseconds of the save because the service lives until the summary has been seen.

### 4. Recovery on launch

`AppContainer` starts `RideLibrary.start()` at construction; the scan runs on the disk thread, never the main one.

1. `RideStore.scan()` tidies the folder: leftover `.tmp` files, logs without a valid header (the ride never got going: an
   empty file, garbage, a zero-filled tail after a power cut), logs of rides that are already complete, and meta files with
   nothing behind them are deleted. A ride this process is writing (`RideStore` keeps the set of ids it opened) is never
   touched, so a ride that starts while the scan runs is safe.
2. **Finished but not exported** (a finished log, or a stored record without its FIT file): completed **silently**, nothing is
   asked; it becomes the last ride.
3. **Interrupted** (a log without the `finished` line): **under 60 seconds of samples it is deleted without asking.** A minute
   is shorter than anything the rider would call a ride (a sensor test, a tap on Start by mistake) and longer than the few
   seconds a log typically has when a start fails; the threshold is `RideLibrary.MINIMUM_RECOVERABLE_SECONDS`. From 60 s on,
   the ride is offered. The interrupted ride's end is the start plus one second per sample (ADR 0007), exact to the second in
   progress.
4. **The offer** is one `Notice` at the top of the Ready screen: "An interrupted ride was found" over "Wednesday 18 November
   · 18:04 · 32:17 ridden. Save it to keep the ride, or discard it.", with **Save** and **Discard**. **Save** recovers the
   ride (`RideLog.recover(...).toRecord()`), stores it exactly like a finished one and makes it the **last ride**, where Share
   and Save to folder are; while it is stored the notice says "Saving the ride…" with no buttons; if that fails it says "The
   ride could not be saved" with **Retry** (when retrying can help) and **Discard**. **Discard** asks "Discard this ride?" in the
   usual bottom sheet (**Discard** / **Keep it**, the destructive one is the secondary button) and only then deletes.
   Several interrupted rides are offered **one after the other, newest first**; the text then says how many more wait
   ("2 more after this one"). The notice component got an optional second button for this (design/system.md §7.6).
   **Save** and **Discard** are called with the id of the ride in the notice, so a second tap that arrives after the first
   has finished never saves or deletes the next ride by accident.
5. **Last ride.** `RideLibrary.lastSaved` is the newest saved ride at launch and then each ride as it is saved (a finished
   ride, a recovered one). The Ready screen shows a small row when it is not null: "Last ride", "Sweet spot 3×10 · 1:05:00",
   **Share** and **Save to folder**. It is not a list and there is no history screen (that is not Phase 1).

### 5. Share and Save

- **Share:** `FileProvider` (`androidx.core`), `exported=false`, `grantUriPermissions=true`, paths **only** `files-path
  rides/` (`res/xml/file_paths.xml`). The action is an `Intent.ACTION_SEND` chooser with one `content://` URI, a read-only
  grant (`FLAG_GRANT_READ_URI_PERMISSION` and the URI in the clip data, which is what carries the grant through the chooser),
  MIME type `application/octet-stream` (FIT has no widely registered type; a generic one reaches every app). The receiver sees
  the friendly name, not the id: `FileProvider.getUriForFile(..., displayName)`.
- **Save to folder:** `ActivityResultContracts.CreateDocument`, suggested name **`YYYY-MM-DD HH-mm <title>.fit`** (local
  start time; the title with `\ / : * ? " < > |` and control characters made hyphens, spaces collapsed, cut at 60 characters),
  then the file is copied to the URI the rider picked, on the IO dispatcher. No storage permission is needed. The ride the
  picker was opened for is saved state, so a rotation while the picker is open does not lose it.
- Both go through `RideFileActions` (an interface), so the screens are tested with a fake; failures become a plain message
  under the buttons ("The file could not be saved. Try another folder.", "No app can take the file. Use Save to folder
  instead.") and never an exception. Buttons: the finished screen says **Share FIT file** (the design's wording) and **Save to
  folder**; the compact last-ride row says **Share** and **Save to folder**. ("Save" alone would read as the recovery
  notice's Save, which keeps the ride.)

### 6. Privacy

Rides are private app data. Nothing leaves except a file the rider shares or saves, one at a time. No network permission and
`allowBackup=false` (a test reads both from the installed package). Nothing logged contains ride values. The tests' rides are
formulas of the second; no recorded data of anyone is in the repository.

### 7. Tests

JVM and Robolectric, no device (`make android-check`): `RideStoreTest` (24 tests, and 4 for the file names: the lifecycle, a stop after
every finalize step, a stop while writing a file, recovery, torn and headerless logs, unsafe ids, a failing sink, deletion),
`AsyncRideRecorderTest` and `AsyncRideRecorderThreadTest` in core (9), `RideLibraryTest` (19: scan, thresholds, order, save, discard, retry, the saving state, storing
after the caller left), `RideHostPersistenceTest` (13: a full simulated ride leaves a JSON equal to the engine's record and a FIT file
with the right header, data size and CRC-16 checked by a local implementation and byte-equal to what the writer gives for that
record and offset, no stray log; a ride cut off mid-way is recovered by a second library with the first run's samples as a
prefix; a log that cannot be opened; a failed save and Retry; Done while saving), `RideHostDiskTest` (a stalled `fsync` on real
threads), `AppContainerRecoveryTest` (a real `AppContainer` over the same folder after the first run "dies", then a whole new
ride is stored), `StorageUiTest` (33, 11 of them screenshots: notice, confirmation, last-ride row, the finished screen's states, the riding notice, and the
screenshots of finished saved, finished with a failed save, ready with the recovery notice, ready with the last-ride row),
`RideFilesTest` (7: the share intent, the URI reads the file under the friendly name, the provider is private and serves only the
rides folder, no network permission). The screenshots are in `app-ride/build/shots/` and never committed.

### 8. What changed in existing code

- `RideHost` has a last constructor parameter `persistence: RidePersistence = RidePersistence.None` (every existing call
  compiles), a new `retrySave()`, and publishes `RideUi.logFailed` (default false) and `FinishedRide.save` (default
  `Saving`). `RideRequest` and `RideSessionFactory` are unchanged.
- `FinishedScreen`, `ReadyScreen` and `ReadyActions` gained trailing parameters with defaults; the ui-kit `Notice` an optional
  second button. `AppContainer` has a `rides: RideLibrary` and two optional parameters (the rides folder, the settings file name)
  so a test can build a second container over the same folder.
- `core`: `AsyncRideRecorder` is new; nothing else changed and no wire format moved.

## Unverified on the tablet

| Claim | Settled by |
|---|---|
| Force-stopping the app mid-ride leaves a log the next launch recovers, within a few seconds of the stop | bench 6.1 to 6.3 |
| The log writes and `fsync`s on the IO thread do not show in ride or overlay smoothness on the tablet's flash | bench 2.8 and 2.11 (the hour-long ride and its battery use) |
| `FileChannel.open` on a directory and `force` work on Android's file systems (the folder sync is best effort and silent) | not observable; the file-level `fsync` does not depend on it |
| The share sheet offers the FIT file with its friendly name, and the receiving app can read it | bench 7.1 |
| ZUI's file picker creates `YYYY-MM-DD HH-mm <title>.fit` without changing the extension for `application/octet-stream` | bench 7.1 |
| intervals.icu accepts the file and the local start time is right (the offset is the tablet's) | bench 7.2 to 7.4 (ADR 0007) |
| Swiping the app away from recents mid-ride leaves a recoverable log, not a lost ride | bench 6.4 |

## Consequences

- A ride is on disk within about a second of every second of riding; a kill loses what was still in the queue (a few
  records) and a power cut at most the last five seconds (ADR 0007's `fsync` cadence).
- The only thing that deletes a ride is the rider's **Discard** (after confirming) or an interrupted ride shorter than 60 s.
- The sync stub's outbox (spec §4.9, M4) and Phase 2's upload read `<id>.json` and `<id>.fit` from this folder; the sync state
  belongs in a file or a table of its own, not in the record (spec §8.2). A history screen needs a list the store can already give (`RideStore.list()` is
  newest first) and a retention decision.
- A new FIT option found on the bench changes `FitOptions` defaults, not this storage; stored rides can be regenerated from
  their JSON (`finalizeStored` writes a missing FIT file again; a bulk "regenerate" would be a small addition).
- The `rides` package of `app-ride` is the one place that knows both the engine's seam and the store; `core` stays
  platform-free and `data` stays Android-free.
