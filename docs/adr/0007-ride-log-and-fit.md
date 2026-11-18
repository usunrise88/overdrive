# ADR 0007: The ride record on the wire, the crash-safe ride log, and the FIT writer

Status: accepted (stage M2b-1, 2026-11-18)

## Context

M2a left the ride as an in-memory `RideRecord` behind the `RideRecorder` seam (ADR 0004). M2b makes it durable and
exportable: spec §4.2 asks that "records are appended to disk every second" and an interrupted ride is recovered
(acceptance criterion 5), and spec §4.8 asks for a FIT file per ride that intervals.icu opens with power, heart rate,
cadence and laps (criterion 6). M2b-1, this ADR, is the pure-Kotlin half in `core/` plus the shared contract in
`contracts/`; M2b-2 is the Android half (where the file lives, the recovery prompt, the share button).

Three things are decided here: the wire format of the ride record (contracts first, `CLAUDE.md`), the format of the log of a
ride in progress, and the FIT writer. They fit together: the log is what survives a crash, `RideRecordBuilder.recover`
turns it back into a `RideRecord`, and `FitWriter` turns that into a file.

## Decision

### 1. The ride record on the wire (spec §8.2)

- **One JSON document, schema 1**, `contracts/schema/ride-record.schema.json`: `schema`, `ride`, `samples`, `rrIntervals`,
  `laps`, `summary`. Property names are the Kotlin property names that spec §8.2 settled in M2a, so there is nothing to
  map. The record's parts (`RideHeader`, `RideSample`, `RrInterval`, `Lap`, `RideSummary`) are `@Serializable` as they
  are, like `Workout`; `RideRecordJson.encode/decode` wraps them with the `schema` number.
- **A gap is an absent key.** The writer omits null (`explicitNulls = false`) and never writes `"power": null`; the reader and
  the schema accept null as the same thing, so another producer may write it. Fields that have a meaning even at zero are
  always written (`speedKmh`, `distanceMeters`, `paused`, `devices`): in the schema they are optional with defaults,
  so a hand-written record can leave them out.
- **Strict on read.** Unknown keys, a wrong `schema`, out-of-range numbers and the structural rules below are rejected
  with an `IllegalArgumentException` in Kotlin and an error list in TypeScript (`validateRideRecord`). What a schema cannot
  say is checked in both languages by code, with fixtures: samples run 1, 2, 3, … in order; laps start at 0, each ends
  after it starts and the next starts where it ends, the last ends at the last second; `summary.durationSeconds` is the
  number of samples. `contracts/fixtures/rides/` has 5 valid and 22 invalid fixtures; both sides reject each invalid one for
  the reason in its name (checked while writing them).
- `RideSample` and `RideHeader` got a few `require`s that the schema's minimums need: speed and distance finite and not
  negative, gear at least 1, target power and step index not negative, start time not before 1970. The engine never
  produces anything else, and the full test suite is unchanged.
- **Known asymmetry:** kotlinx.serialization accepts a number written as a string (`"power": "150"`), the schema does not. No
  fixture relies on it, and nothing in the system writes quoted numbers.
- Doubles are written as Kotlin prints them (shortest digits that read back exactly), so a record survives a round trip bit
  for bit; JavaScript reads the same text.
- The wire document is for storing a finished ride and for the sync API of Phase 2 (spec §8.3, which uploads the FIT file
  and the summary). It is not what is written during the ride: that is the log.

### 2. The ride log (spec §4.2 crash safety)

**Encoding: one line per record, `<crc> <kind> <json>\n`.**

```
9bfe7ecc header {"schema":1,"ride":{"id":"g","startEpochMillis":1794996000000,"ftp":250,"devices":["Trainer"]}}
f2064d88 sample {"timeOffsetSeconds":1,"power":180,"heartRate":121,"cadence":90,"speedKmh":31.2,"distanceMeters":8.7,"paused":false}
e62575d4 rr [{"timeOffsetSeconds":1,"intervalMs":812}]
84a40202 sample {"timeOffsetSeconds":2,"speedKmh":0.0,"distanceMeters":8.7,"paused":true}
8e127e54 finished {"endEpochMillis":1794996002000}
```

- `crc` is eight lower-case hex digits: CRC-32 (zip, `0xEDB88320`) of the bytes from `kind` to the byte before the
  line feed. The JSON is the wire format of that part, on one line (JSON never contains a raw line feed), so
  `cut -d' ' -f3- ride.ridelog | jq` reads it and the log can be debugged with a text editor. About 235 bytes a second with workout
  fields and the strap's RR intervals, under a megabyte an hour.
- Kinds: `header` first (`{"schema":1,"ride":RideHeader}`, the log's own schema number), then `sample` once a second in
  order, `rr` (a list of `RrInterval`) when the strap sent some, and `finished` last. The order of the engine's calls
  (`onSample` for second *k*, then its `onRrIntervals`) is what is written.
- **Why this and not SQLite/Room or a binary format.** The log must survive the app being killed at any instant and be
  readable by core, which has no platform imports; a text log with framing and a checksum needs no library, is appended with
  one `write` per record, can be cut at any byte, and is read by a loop. A database would put the Android SQLite API (and
  its write-ahead log and its own recovery) under the crash-safety guarantee, which the record layer should not depend on,
  and core could not test it. CBOR/protobuf would save bytes nobody needs.
- **A record counts only if its line is complete**: ends in a line feed, has a well-formed prefix, and its checksum
  matches. Newline framing alone detects every torn tail (a torn line has no line feed); the checksum covers what newline
  framing cannot: a file system that grew the file but lost the data (zero-filled tail after a power cut), and a flipped
  bit in the middle. CRC-32 detects all single-byte errors and any burst of up to 32 bits.
- **Recovery** (`RideLog.recover(bytes | ByteSource): RecoveredRide?`) reads lines from the start and stops at the first
  that fails, with the same rule for every cause: not terminated, bad checksum, not the kind that may follow (the first must
  be the header; a sample must be exactly the next second; nothing may follow `finished`), unknown kind, JSON that is not
  of the schema, a header of another schema. It **never throws**; the result is the **longest intact prefix**, so earlier
  records are never lost and later ones are never taken across a damaged one (the sample sequence would have a hole). It
  returns null only when there is nothing to recover (no valid header: the ride never got going).
- `RecoveredRide` has the header, samples, RR intervals, `endEpochMillis` from the `finished` line (null if
  interrupted), `status` (`FINISHED` or `INTERRUPTED`), and `validBytes` and `discardedBytes`. `toRecord()` goes through
  `RideRecordBuilder.recover`, so laps and summary come out of the same code as in a live ride. **An interrupted ride's end time is
  estimated** as the start plus one second per sample (a sample exists for every second of the ride, pauses included),
  which is right to within the second in progress when it stopped.
- **Durability.** Each record is one `ByteSink.append`, handed to the operating system as one `write`: a process kill
  (SIGKILL) loses nothing already appended. Power loss is covered by `ByteSink.sync()` (`fsync`) after the header, after
  `finished`, and every `syncEverySamples` samples (**default 5**, so a power cut loses at most five seconds): an `fsync` a
  second would be safe too, but it is a flash round trip on the engine's thread, and the cost is not worth the four
  seconds. The number is a constructor argument.
- **A failing sink never ends the ride.** `RideLogRecorder` catches the sink's exceptions, keeps the first in `failure` and stops
  writing (so the file stays a clean prefix); the engine carries on, and the ride can still be finished and exported from
  memory when combined with `InMemoryRideRecorder` in a `CompositeRideRecorder`. (An exception that escaped `RideRecorder`
  would end the engine's coroutine, as its KDoc says.) Calls out of order (a sample before the start) are
  programming errors and throw `IllegalStateException`, like `InMemoryRideRecorder`.
- **Abstractions.** `ByteSink` (`append`, `sync`, `close`) and `ByteSource` (`readAll`) in `commonMain`, with
  `InMemoryByteStore` for tests; `FileByteSink` and `FileByteSource` over `java.io` in the **JVM source set** (`jvmMain`) of
  core, which Android uses as a plain JVM library. `FileByteSink` creates missing directories, appends (never truncates
  unless asked) and throws `IOException` from its constructor if the file cannot be opened, which M2b-2 has to handle.
- **Tests.** `RideLogFuzzTest`: a log of 60 seconds with gaps, a pause and RR intervals, **cut at every byte offset**,
  zero-filled from every offset, given a random garbage tail at every offset, and with every byte flipped in turn; in each
  case recovery does not throw and returns exactly the records before the damage, a prefix of what was written.
  `FileByteStorageTest`: the same on real files, and a child JVM that writes a log as fast as it can and is **killed with
  SIGKILL**; the file recovers as a prefix. `RideEngineLogTest`: the real engine's ride (workout, auto-pause, trainer
  outage, RR) recovers to a record equal to the engine's own.

**Wiring for M2b-2 (what a caller does):**

```kotlin
// at the start of a ride
val file = File(ridesDir, "${rideId}.ridelog")
val live = InMemoryRideRecorder()
val log = RideLogRecorder(FileByteSink(file))              // catch IOException from the constructor
val engine = RideEngine(scope, trainer, strap, workout, settings, recorder = CompositeRideRecorder(log, live))
// log.failure != null means the log stopped; show a warning, the ride goes on

// when the ride is over (RideState.FINISHED) or on the next launch
val record = live.record ?: RideLog.recover(FileByteSource(file))?.toRecord()
val fit = FitWriter(FitOptions(utcOffsetSeconds = timeZoneOffsetSeconds)).write(record)

// on every launch: any *.ridelog not yet exported is a ride to offer
RideLog.recover(FileByteSource(file))?.let { if (!it.isFinished) offerRecovery(it) }
```

### 3. The FIT writer (spec §4.8, `docs/protocols/fit.md`)

`FitWriter(options: FitOptions = FitOptions()).write(record: RideRecord): ByteArray`, in `app.overdrive.core.fit`
(commonMain, no platform API). Our own implementation of Garmin's published format from the facts of `fit.md`: no code of
Garmin's SDK, and **the SDK is not a dependency of the app, the build or CI**.

- **Deterministic.** Nothing depends on the clock: `file_id.time_created` is the ride's start time, and the same record
  and options give the same bytes.
- **File.** 14-byte header (size, protocol, profile 21222, data size, `.FIT`, header CRC), the records, the file CRC;
  CRC-16 is the nibble-table form of fit.md 1.4. Little-endian definitions, normal record headers only, no developer fields.
  Local types as in the worked example: 0 `file_id`, 1 `event`, 2 `record`, 3 `lap`, 4 `session`, 5 `activity`, 6 `hrv`.
- **Order (fit.md section 5).** `file_id`; `event` timer start; `record`s one per second with each `hrv` after the record of
  the second its intervals arrived in; each `lap` right after the last record of its step, except the last lap; `event` timer
  stop_all; the last `lap`; `session`; `activity`. (RR intervals stamped after the last sample go after the last
  record. An `hrv` definition is repeated whenever the number of intervals changes, as each message has its own array
  length; more than 100 intervals in one second are split.)
- **Fields.** Exactly the layouts of fit.md section 6 (which is also the golden): `record` has timestamp, heart rate,
  cadence, distance (cm), speed (mm/s) and `enhanced_speed` with the same value, power; `lap` and `session` as listed
  there; `session` adds normalised power, TSS (×10), intensity factor (×1000) and work (J) when the ride has a normalised
  power (`loadFields`, rides under 30 s of power have none). A reading that was not measured is the base type's invalid
  value, so every record has the same layout. A value above what a field holds is held at its largest real value, never at
  the invalid value; an RR interval over 65.5 s is written as invalid.
- **Every second is a record, paused ones too, as sampled** (the engine records power and cadence null for a trainer
  outage and 0 for a manual pause, and the strap goes on): no heart rate during a pause is lost and the file has no
  holes. Consequently **elapsed and timer time are both the ride's length**, and no stop/start events mark pauses. Lap and
  session averages of power and heart rate are the record's (over seconds that were not paused); maximum heart rate, average
  cadence and maximum power, which the record has no field for, are computed by the writer in the same way, over
  non-paused seconds with a reading. Whether intervals.icu then counts a pause as riding time (a second with 0 W) is a
  bench question; the alternative, timer events with no records inside the pause, would lose the strap's recovery data.
- **Time.** The sample of second *k* is stamped `start + k − 1`, the lap and session end at the timestamp of their last
  record (`SECOND_START`, the convention the worked example implies, see `fit.md` 6.3); `SECOND_END` stamps `start + k`.
  `activity.local_timestamp` is the end timestamp plus `utcOffsetSeconds`. **Deviation from the example:** its
  `local_timestamp` is its start, `t0`, while its `timestamp` is `t0 + 2`; read as "local = timestamp + offset" that is an
  offset of −2 s, which for a real ride would be wrong by the ride's length. The writer follows the profile, and the golden test
  passes `utcOffsetSeconds = -2` to reproduce the document's 376 bytes exactly. **The app must pass the tablet's real
  UTC offset** (`utcOffsetSeconds`, default UTC), because the record holds only UTC.
- A start time FIT cannot date (before 1998-07-01) or an empty ride throws `IllegalArgumentException`.

**The open questions of fit.md section 8 are the fields of `FitOptions`.** A bench finding is a change of one default:

| fit.md | Question | Option | Default (what the doc recommends) |
|---|---|---|---|
| 8.1 | protocol version byte | `protocolVersion` | `0x20` (Java SDK); JS SDK writes `0x02` |
| 8.2 | sport and sub-sport | `sport`, `subSport` | cycling (2), virtual activity (58); indoor cycling is 6 (`SUB_SPORT_INDOOR_CYCLING`) |
| 8.3 | speed fields | `speedFields` | `BOTH` (`speed` and `enhanced_speed`); `SPEED`, `ENHANCED_SPEED` |
| 8.4 | HRV placement | `hrvPlacement` | `AFTER_RECORD`; `BEFORE_RECORD` |
| 8.5 | manufacturer | `manufacturer`, `product`, `serialNumber` | 255 (development), 1, 1 |
| 8.6 | which events and laps import needs | `timerEvents`, `stepLapTrigger` | events on; step laps `manual` (0), last lap `session_end` (7) |
| 8.7 | distance and speed consistency | none: both come from the same samples | |
| 8.8 | profile source (the PDF was not read) | none: check field numbers against the PDF | |
| 8.9 | compressed headers, developer fields | not written | |
| (ours) | what a record's timestamp is | `recordStamp` | `SECOND_START` |
| (ours) | the UTC offset of the local time | `utcOffsetSeconds` | 0, the app must set it |
| (ours) | load fields in the session | `loadFields` | `true` |

- **Verification in CI** (`make check`), none of it using Garmin's SDK: an **independent test-only FIT reader**
  (`FitReader`, commonTest) that parses the file generically from its own definition messages and is strict (header
  size, signature, header CRC with a bitwise CRC-16 of its own, data size, file CRC, the CRC over the whole file is 0,
  every data message after its definition, base-type sizes and endian flags), plus a second transcription of the profile facts
  that checks every field's name, number and base type and decodes scales; **the worked example of fit.md section 6.1 as a
  byte-exact golden** (`FitGoldenTest`); structure, order, every record, lap, session, activity and hrv message
  against the record, gaps, clamping, determinism and a round trip back to the record's readings (`FitWriterTest`); each
  option moving exactly its own choice (`FitOptionsTest`); the reader's own strictness (`FitCrcAndReaderTest`).
  I mutated the writer and the log reader during development (a wrong field number, no checksum check, accepting an
  unterminated line) and the tests failed each time.
- **Opt-in oracle, local only** (`GarminFitOracleTest`, jvmTest): decodes our files with `com.garmin:fit` **if and only if**
  `-PfitOracleJar=/path/to/fit.jar` is given; otherwise every test in it is skipped, as in CI. The jar is not a dependency
  of the build and is never committed or fetched by it: the test loads it at run time from the path, through reflection on
  its public API (`Decode.read`, `isFileFit`, `checkFileIntegrity`, `Mesg.getFieldLongValue`, …). To run it:

  ```
  # outside the repository; the licence question of fit.md section 7 (test use of the jar) is the owner's to settle with Garmin
  mkdir -p ~/fit-oracle && cd ~/fit-oracle
  curl -fLO https://repo1.maven.org/maven2/com/garmin/fit/21.222.0/fit-21.222.0.jar
  cd <repo>/core && ./gradlew jvmTest --tests '*GarminFitOracleTest*' -PfitOracleJar=$HOME/fit-oracle/fit-21.222.0.jar
  ```

  I ran it once with the jar downloaded to a scratch directory outside the repository: all three oracle tests passed
  (Garmin's decoder reports the worked-example file and the synthetic ride as FIT files with valid integrity, decodes
  the same messages as the independent reader, with the same power, heart rate, cadence, distance, speed, lap, session and
  hrv values, and also decodes the file under every option variant). Garmin's decoder is not an importer: it checks the
  format, not what intervals.icu does with it.

### What is still unverified (until the owner imports into intervals.icu)

Everything in fit.md section 8: that intervals.icu and Strava accept the protocol byte and the development manufacturer; read
`speed` or `enhanced_speed`; show a virtual activity as indoor; read the `hrv` messages and where they place them; need the
events and the last lap; treat the pause seconds (records with 0 W and 0 speed) as riding time; show the correct local start
time from `local_timestamp`; and accept a one-second offset in the end timestamps. Also unverified: the field numbers against Garmin's
FIT Protocol PDF, which was never read (fit.md 8.8): they come from the SDK's generated profile and are confirmed by Garmin's
decoder, which reads them with the right names and values. Nothing here has touched a real recording of the owner's body or
bike; every fixture is synthetic.

## Consequences

- M2b-2 constructs a `RideLogRecorder(FileByteSink(file))` beside an `InMemoryRideRecorder`, runs `RideLog.recover` on launch,
  writes the FIT file from `live.record` or from the recovered record, passes the time zone offset, and decides the
  storage directory, the file names, when a log is deleted, and what the recovery prompt says. The log file name should carry
  the ride id; a log with no valid header can be deleted.
- A change to the record's fields starts in `contracts/` (schema and fixtures), then `RideRecord` and the TypeScript types, as
  for any wire format; the log reuses the record's parts, so it follows. The log's own `schema` number is for a change to
  the framing.
- `FitOptions` is the place a bench result is applied; its defaults are what `fit.md` recommends, and the tests that pin a
  default are the ones in `FitOptionsTest` and the golden.
- `RideSample` and `RideHeader` reject values the engine never produces; a new producer of samples must respect them.
- The log is not a second source of truth for a finished ride: once the FIT file and the stored record exist the log
  can go. Until then it is the only copy of a ride that was interrupted.
