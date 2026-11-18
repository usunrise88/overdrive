# ADR 0009: The BLE transport boundary, the device adapters and the session recorder

Status: accepted (stage M3-1, 2026-11-18)

## Context

Stage M3 puts real Bluetooth under the ride engine (spec §4.1) without the trainer, the strap or the tablet in reach
(roadmap, "Hardware verification is deferred"). Correctness therefore rests on `docs/protocols/ftms-hr.md` (research notes
from the SIG GATT XML and two open-source decoders, **nothing observed on the bench**), on the engine's device contract
(ADR 0003, ADR 0004) and on a fake that replays sessions (spec §7.5). M3-1 is the part that needs no Android: everything
in `core` that the Android transport of M3-2 plugs into. The questions this ADR settles are where the platform boundary runs
and why, how the adapters meet the engine's contract, how reconnecting works, what a recorded session is, and above all how
every guess is kept **isolated, named and cheap to flip** when the owner's bench disagrees with the notes.

## Decision

### 1. One boundary, `GattTransport`, and nothing Android above it

`app.overdrive.core.ble` holds the only seam between `core` and the Bluetooth stack. M3-2 implements these four interfaces
on `BluetoothGatt`, `BluetoothLeScanner` and the adapter-state broadcasts; everything else in this ADR is above them and
runs in plain JVM tests.

| Interface | One line | Android side (M3-2) |
|---|---|---|
| `GattTransport` | one GATT client session: `connect`, `discoverServices`, `requestMtu`, `read`, `write` (with response), `enable` (a CCCD write), `close`; `state` and a hot `notifications` flow of raw bytes with a monotonic timestamp | one `BluetoothGatt` and its callback |
| `GattTransportFactory` | `open(DeviceId)`: a new, unconnected transport | `getRemoteDevice(address)` then a transport; the address lives only here |
| `GattScanner` | `scan(filter)`: a cold flow of `ScanEvent.Found(Advertisement(id, name, serviceUuids, rssi))` and at most one `Failed(reason)` | `BluetoothLeScanner` |
| `BleEnvironment` | `availability: StateFlow<BleAvailability>` (`READY`, `BLUETOOTH_OFF`, `PERMISSION_DENIED`, `UNSUPPORTED`) | adapter state receiver and the "Nearby devices" permission |

Why a boundary this small and shaped like this:

- **It is where the platform's rules are, so it is where the fake enforces them.** A transport is **single-use**: after a
  failed connect or a dropped link (`GattLinkState.Disconnected`) it is dead and has no way to connect again, so no caller can
  reuse a `BluetoothGatt` after status 133 (ftms-hr §7); the supervisor asks the factory for a new one. Operations return
  `GattResult` (a typed failure with the platform's `Int` status) instead of throwing, because failure is routine. **One
  operation at a time** is the platform's rule, so a transport answers an overlapping call with `BUSY`, and the adapters
  serialise everything behind one lock (`GattIo`), CCCD writes included. `FakeBle` is strict in the same way and records
  `violations` (an operation while another is outstanding, an operation after `close` or before the link is up, `connect`
  on a transport that was already used, a second transport opened before the first was closed). A peripheral also delivers a
  value only to a central that enabled it, in the mode it enabled. A test that ends with an empty list has proved the code
  under test is a legal Android client.
- **Characteristics are handles from discovery** (`GattCharacteristicInfo(service, characteristic, properties)`), so M3-2 looks
  them up by (service, UUID) without searching and the adapters can pick notify or indicate from the discovered properties.
- **`DeviceId` is an opaque value class whose `toString` prints a 16-bit FNV fingerprint, never the address.** Nothing in
  `core` can leak a hardware address into a log, an exception message or a recorded file, because nothing in `core` has one.
- **No Android type, no `java.util.UUID`**: `GattUuid` is a value class over the canonical lower-case string.
- **Bluetooth off and a revoked permission are a state, not a failure.** They come from `BleEnvironment`, and the supervisor
  turns them into `LinkStatus.BluetoothUnavailable(availability)` that the Devices screen can explain, without Android types.

### 2. Package layout (`app.overdrive.core.ble`)

| Package | Contents |
|---|---|
| `ble` | `GattTransport`, `GattTransportFactory`, `GattResult`/`GattFailure`/`GattStatus`, `GattUuid` and the known UUIDs, `GattScanner`, `BleEnvironment`, `DeviceId` |
| `ble.codec` | pure, exception-free codecs: `IndoorBikeDataCodec`, `FitnessMachineFeatureCodec`, `ControlPointCodec`, `FitnessMachineStatusCodec`, `HeartRateCodec`, `DecodeResult` (typed `Malformed` for truncated or empty input), `Hex` |
| `ble.link` | `BackoffPolicy`, `ReconnectBackoff`, `LinkSupervisor`, `LinkStatus`, `LinkControls` |
| `ble.adapter` | `FtmsTrainerDevice` (a `TrainerDevice`), `HeartRateStrapDevice` (a `HeartRateDevice`), `FtmsOptions`, `HeartRateOptions`, `TrainerEvent`, `TrainerCommandException`, the internal sessions |
| `ble.recording` | `BleSessionFormat` (reader and writer), `BleSessionRecorder`, `RecordingGattTransport` (decorator), `BleRecordingPolicy` |
| `ble.fake` | `FakeBle` (world, factory, scanner, environment), `FakeFtmsTrainer`, `FakeHeartRateStrap`, `ReplayPeripheral`, `FakeFaults` |
| `device` (existing) | new: `RrIntervals`, the domain layer's RR conversion |

The fakes are in `commonMain`, like `SimulatedBike`, so the Android modules' tests (and a debug build) can use them.
No dependency was added; existing public APIs are unchanged.

### 3. The codecs are the notes' §2 to §6 as code, and the vectors of §8 are the oracle

All 44 vectors of ftms-hr §8 are test cases with their IDs (`VectorsTest`); random bytes of every short length, every proper
prefix of every Indoor Bike Data vector, and encode-decode round trips of random notifications run as fuzz tests
(`CodecFuzzTest`). A payload that ends before a field its flags announce is `Malformed(TRUNCATED)`, never a partial record
(§2.3 step 5). Decoding returns raw units where the notes say the domain decides: Heart Rate RR intervals stay in 1/1024 s.
**The rounding rule lives in `RrIntervals.rawToMillis` in the domain layer**, as §6.3 and §10 ask: `raw · 1000 / 1024`,
halves up, integer arithmetic; a raw 0 is dropped because `HeartRateReading` takes positive intervals only.

### 4. The adapters meet the engine's existing contract, and the engine does not change

- **`FtmsTrainerDevice`** is a `TrainerDevice`. Set-up per connection, in this order: discover; require Indoor Bike Data and the
  Control Point; request an MTU of 64; read Fitness Machine Feature; enable the **Control Point (indications), Indoor Bike Data
  (notifications) and Status (notifications), one CCCD write at a time**; then **request control (and Start or Resume)**.
  `connection` becomes `CONNECTED` only after that, so the engine's `READY` means the trainer can be commanded. A missing
  Status or Feature does not stop the connection (optional); a missing Indoor Bike Data or Control Point, or a failed
  subscription to either, does (`SERVICE_MISSING`, `SETUP_FAILED`), and the supervisor backs off.
- **Control Point procedure.** One command in flight; each is written with response and answered by its Response Code
  indication within `controlPointTimeout`. `requestControl()` is free when control is held, so the engine's call after
  every (re)connect costs nothing and the adapter's own request in set-up covers a connection the engine never saw drop.
  Response codes are handled as ftms-hr §4.4 proposes: *operation failed* is re-sent twice with a 500 ms wait; *control not
  permitted* takes control again once and re-sends; *op code not supported* fails the command and the opcode is remembered
  (later tries fail at once, without traffic); *invalid parameter* and unknown codes fail the command. A failure throws
  `TrainerCommandException` (kinds `NOT_CONNECTED`, `WRITE_FAILED`, `TIMEOUT`, `REJECTED`, `UNSUPPORTED`, `NOT_UNDER_CONTROL`,
  `FEATURE_MISSING`), which is what the engine's contract says it retries after a second, **starting over with
  `requestControl()`**; after a timeout or a write failure the adapter forgets that it holds control so that really happens.
- **Status.** `0x01` and `0xFF` (control lost) make the adapter take control back after a second and **re-send the last
  target or simulation by itself**, because the engine does not re-send an unchanged target. The other status opcodes become
  `TrainerEvent`s (`events` flow) that nothing consumes yet; a Status `0x08` that differs from the target just sent is
  reported as `TargetPowerAltered`.
- **Readings.** Indoor Bike Data becomes `TrainerReading`: power (a negative sint16 is 0, a `TrainerReading` cannot hold it),
  cadence rounded **half up** from the half-rpm resolution, the trainer's own speed carried and ignored. Malformed
  notifications are dropped and reported, never thrown.
- **`HeartRateStrapDevice`** is a `HeartRateDevice`: it enables only Heart Rate Measurement, and each notification becomes a
  `HeartRateReading` with RR in whole milliseconds. A strap that says "supported, not detected" reads as **0 bpm without RR**,
  which is the engine's "no signal" (ADR 0004); an implausible rate (above 250) is 0 as well. A strap never pauses or stops the
  ride: the engine already turns its absence into a gap.
- **Threading.** Everything is coroutines on the scope the host gives. Commands are safe from any coroutine (a mutex, flows,
  deferreds); the ride host's single-threaded dispatcher is fine and not required.

### 5. Reconnecting: `LinkSupervisor`, `BackoffPolicy`

One loop per device (spec §4.1: "Each device reconnects on its own with backoff. A dropout never stops the ride"): wait until
Bluetooth is usable, `open` a **fresh** transport, `connect`, run the device's set-up, publish `CONNECTED` and the session,
wait for the link to end, close the transport, back off, repeat. A failed or dropped transport (status 133 included) is
always closed before a new one is opened; the fake asserts it.

| Parameter | Default | Why |
|---|---|---|
| first attempt | immediate | later launches connect without user action |
| initial delay | 1 s | long enough for the stack to tear a failed GATT object down |
| factor | 2 | 1, 2, 4, 8, 16, 30, 30 … |
| cap | 30 s | a trainer switched back on is found within a breath |
| jitter | ±20 % | spreads retries; **0 in tests** (`BackoffPolicy.EXACT`) |
| stable time | 30 s | a connection that held this long counts as healthy: the next drop starts at 1 s; a link that drops again within seconds keeps backing off |
| attempt limit | none | a dropout never stops the ride |
| establish timeout | 45 s | from `connect` to ready; the backstop for a stack that never answers (Android's own connect timeout is about 30 s, unverified) |
| per-operation timeout | 10 s | a callback that never comes must not hang a set-up |

`ReconnectBackoff` is the pure part with an injectable `TimeSource`; the supervisor runs on `delay`, so it is deterministic
under a test scheduler. **`retryNow()`** (the Devices screen's Retry button, `LinkControls`) cuts a backoff wait short and
starts the sequence over; it does nothing while connecting, connected or without Bluetooth. **Bluetooth off or the
permission revoked** drops the link, publishes `LinkStatus.BluetoothUnavailable`, tries nothing and accrues no backoff, and
connects at once when `BleEnvironment` says ready again. `connection: StateFlow<ConnectionState>` is the engine's three-state
view (`DISCONNECTED` while waiting, `CONNECTING` during an attempt); `status: StateFlow<LinkStatus>` is the reason behind it
(`Connecting(attempt)`, `Connected`, `WaitingToRetry(failures, retryInMillis, failure)`, `BluetoothUnavailable`, `Stopped`).

### 6. The raw session recorder and its format

`RecordingGattTransport` is a decorator at the boundary: wrap the transport (or the factory, `factory.recording(recorder,
scope)`) and every event crossing it is written, so the adapters need no cooperation, the same code records on the tablet
and in a test, and a file replays through the fake. `BleSessionRecorder` is a queue in front of a `LineSink`, so a slow disk
never delays a BLE callback or the ride (spec §7.3), and a failing sink stops the recording and nothing else.

Format (`BleSessionFormat`, version 1), docs/protocols/ftms-hr.md §7.1 as implemented, one event per line:

```
# overdrive-ble-session 1
# model D500
0 S - - connecting
200000000 S - 0 connected
220000000 D 2AD9 - 1826/WI
240000000 S - 0 mtu=64
260000000 R 2ACC 0 0654000008200000
280000000 C 2AD9 0 0200
340000000 W 2AD9 0 00
390000000 I 2AD9 - 800001
1200000000 N 2AD2 - 44008C0AB4006400
21000000000 S - 133 disconnected
21000000000 S - - closed
```

`t_ns dir char status data`. **Differences from the first draft of §7.1:** times are **nanoseconds since the recording began**,
not since boot (a file carries no clock that could date or identify a device); there is a seventh direction **`D`**
(a discovered characteristic with its service and properties, `1826/WI`: letters R, W, w, N, I) because a replay needs to
know what the peripheral offered; `S` lines carry a token in the last field (`connecting`, `connected`, `disconnected`
with the GATT status, `closed`, `mtu=64`). `W`, `R` and `C` lines are written at completion with their status.
The reader never throws: a line it cannot read is skipped and counted (`BleSessionParse.skippedLines`).

**What is never written** (`BleRecordingPolicy`, ftms-hr §7.1, `CLAUDE.md`: the repository is public): the recorder has no
`DeviceId`, so no address; the header's model is the advertised model only, cleaned (ASCII letters and digits; a word with four
or more digits, a long hex word or a run of hex pairs is dropped; cut at 24 characters); the **values** of identifying
characteristics (serial number, system id, device name, firmware, hardware and software revision, manufacturer and model
number strings) become `*`. Real recordings stay out of git; the fixtures are generated.

### 7. The fake and the replay (`app.overdrive.core.ble.fake`)

`FakeBle` is a simulated radio on virtual time: it implements `GattTransportFactory`, `GattScanner` and `BleEnvironment`,
holds `FakePeripheral`s and is as strict as the platform (section 1). Three peripherals:

- **`FakeFtmsTrainer`**: Indoor Bike Data every second with **power that follows the commands** (ERG target, simulation power,
  0 without cadence), a Control Point that grants control, answers every command with a Response Code and echoes Status, the
  Feature word, control that ends with the link. It can follow either reading of the disputed flag bits
  (`FakeFtmsTrainerOptions.flagMap`) so a test shows what the adapter does when the bench says the other.
- **`FakeHeartRateStrap`**: Heart Rate Measurement with generated RR in 1/1024 s and contact bits that follow `onChest`.
- **`ReplayPeripheral(session)`**: plays a `BleSession` from the device's side. The session is cut into **attempts** at its
  `S connecting` lines: one that reaches `connected` is a segment, one that goes straight to `disconnected` is a failed connect
  replayed with the recorded status; the *n*-th connect plays the *n*-th attempt. Notifications and indications are pushed
  at their recorded time since the link came up, reads and write acknowledgements come from the recording in order, a
  recorded CCCD failure fails that write, and a recorded `disconnected` drops the link with its status. **Limit:** a recorded
  Control Point indication is replayed at its recorded moment, not in answer to a write, so a replay is exact when the engine
  writes when it wrote at recording time, which deterministic virtual time guarantees; for tests that drive the trainer
  differently use `FakeFtmsTrainer`.

Scripted failures (`FakePeripheral.faults`, `dropLink`, `dropLinkAt`, `powerOff`, `FakeBle.setAvailability`): connect
failures with a status (133 by default), failed discovery, failed CCCD and ordinary writes, a drop at a time with a status,
notification gaps in a time window, **Control Point silence (timeout)** and **Control Point error results**, a peripheral that
never answers or fails with 133 after a delay, Bluetooth off, permission denied.

Fixtures: `contracts/fixtures/ble/*.session.txt` are **generated** by running a ride live over the fake with the recorder on
(`SyntheticRide`: a 90 s ERG workout, and a 60 s ride with a 133 drop, a failed reconnect and a strap off the chest). They are
golden (`BleSessionFixturesTest` regenerates and compares; `-PregenerateBleFixtures=true` rewrites them), and each pair
replays through the real adapters and the real `RideEngine` into the very `RideRecord` the live run produced. They hold
synthetic bytes only, the advertised models `D500` and `HR7`, and nothing else.

### 8. Every guess, named and cheap to flip

Each row is a default the notes could not settle. **A bench result changes one constant or one option, never the adapter.**
The constants are in the codec objects (a one-line change that applies everywhere) and the same choice is an option of
`FtmsOptions` or `HeartRateOptions` (per device, so a developer setting can flip it without a build).

| Choice | Name | Default | Evidence | Settled by |
|---|---|---|---|---|
| Bit 1 / bit 2 of Indoor Bike Data flags: Average Speed or Instantaneous Cadence | `IndoorBikeFlagMap.DEFAULT` (`DECODERS`; the other is `SIG_XML_LABELS`), `FtmsOptions.flagMap` | bit 1 average speed, bit 2 cadence | pycycling and Ewoc agree, against the SIG XML's labels (ftms-hr §2.4) | **FT 1**, bench 3.8 |
| Is the simulation status echo signed | `FitnessMachineStatusCodec.DEFAULT_SIMULATION_SIGNED`, `FtmsOptions.simulationStatusSigned` | signed | the Control Point field is signed; pycycling's status parser reads it unsigned (§5) | **FT 8**, bench 3.7 |
| Contact bits of the heart-rate flags | `HeartRateContactRule.DEFAULT` (`SIG_XML`; the other is `ANY_NONZERO`), `HeartRateOptions.contactRule` | only `3` is contact detected | SIG XML against pycycling's `flags & 0x06` (§6.5) | **FT 11**, bench 4.3 |
| Off the chest reads as no signal | `HeartRateOptions.noSignalWithoutContact` | on: 0 bpm and no RR | ADR 0004 (0 bpm is "no signal") | **FT 11**, bench 4.3 |
| Send Start or Resume (`0x07`) after Request Control | `FtmsOptions.startAfterRequestControl` | on; a refusal is only logged | Ewoc never sends it, FTMS defines it (§4.2) | **FT 7**, bench 3.5 |
| Refuse ERG or simulation commands when the Feature word lacks them | `FtmsOptions.enforceFeatureBits` | off: the Response Code decides; the gap is reported (`FeaturesMissing`) | Feature bits are single-source (§3), a wrong word must not stop a ride | bench 3.2 |
| Control Point response timeout | `FtmsOptions.controlPointTimeout` | 3 s | unmeasured (§4.4) | **FT 6**, bench 3.4 |
| Requested ATT MTU | `FtmsOptions.requestedMtu` | 64 | a 30-byte Indoor Bike Data does not fit the default 23 (§7) | **FT 12**, bench 4.5 |
| Retries after "operation failed", and their wait | `FtmsOptions.operationFailedRetries` 2, `operationFailedBackoff` 500 ms | 2 and 500 ms | our reading of §4.4's "a small fixed number" | bench 3.4 |
| Wait before taking control back after a loss | `FtmsOptions.controlRecoveryDelay` | 1 s | Status `0x01` and `0xFF` (§5) | **FT 4**, bench 3.3 |
| Per-operation timeout | `FtmsOptions.operationTimeout`, `HeartRateOptions.operationTimeout` | 10 s | a platform callback that never comes | none |
| An implausible heart rate | `HeartRateOptions.maxPlausibleBpm` | 250 (reported as 0) | a 16-bit field can say 65535 | none |
| Reconnect schedule | `BackoffPolicy` (section 5) | 1 s, ×2, 30 s cap, ±20 %, stable 30 s | spec §4.1 asks for backoff, not for numbers | bench 3.11, 3.12 |
| Establish timeout | `LinkSupervisor.DEFAULT_ESTABLISH_TIMEOUT` | 45 s | Android's connect timeout is about 30 s (unverified) | bench 3.12 |
| Notify or indicate | `updateModeFor`: the specification's mode unless the discovered properties offer only the other | Control Point indicate, the rest notify | SIG service table (§1) | FT 9, bench 3.11 |
| Cadence rounding, negative power | `FtmsSession.toReading` | half up; negative is 0 | ADR 0004 says "rounded to whole rpm"; `TrainerReading` forbids negatives | FT 1 |
| Total Distance units | `IndoorBikeData.totalDistanceMetres` | metres (nothing in the ride reads it) | SIG against Ewoc's decimetres (§2.5) | **FT 2**, bench 3.8 |

Choices that are *not* options because the notes agree: bit 0 of the Indoor Bike Data flags is inverted (speed present when it
is 0), power is sint16, distance is uint24, RR intervals run to the end of the payload, the energy field comes before them.

## Consequences

- M3-2 is mechanical: implement the four interfaces on Android, then build the BLE session factory from
  `FtmsTrainerDevice` and `HeartRateStrapDevice` (the Devices screen's Retry buttons call `retryNow()`; the offline simulated
  factory stays). The recorder is a one-line wrap of the transport factory.
- The ride engine, the simulated devices and every existing test are unchanged. A trainer disconnect in the engine is the
  adapter's `CONNECTED` going away; the seconds in between are paused samples with no power, which the engine already does.
- Right after a reconnect the ride may have a second or two of "riding" samples with no power until the trainer's first
  notification arrives; that is the engine's existing behaviour (held readings are cleared on a link change), now visible
  because the first notification is a second away on the real thing.
- `TrainerEvent` and `StrapEvent` carry what the trainer says (stopped by the user, safety key, target altered); the engine
  does not consume them. Pausing the ride when the trainer's own stop is pressed is a later decision.
- A late Response Code for a command that already timed out is ignored unless it names the opcode now pending; two timeouts
  in a row on the same opcode could therefore mis-pair a response. The adapter forgets control after a timeout, so the next
  command starts over with Request Control, which narrows the window; the bench's FT 6 timings tell whether it matters.

## Unverified until the bench

Everything above was built from the notes and tested against a fake that is written from the same notes, so **a green
test proves consistency with the notes, not with the D500 or the HR7**. In particular: the flag bits (FT 1), distance units
(FT 2), the "More Data" bit and splitting (FT 3: each notification is treated as a complete record and `MoreDataSeen` is
reported), which Status opcodes the D500 sends (FT 4), the grade resolution and the `0x2AD5` range (FT 5: the adapter does not
read it; the gear model keeps the grade within ±15 %), response timing (FT 6), whether Start or Resume is needed (FT 7),
the signedness of the simulation echo (FT 8), indications alongside notifications (FT 9), whether the HR7 sends RR and how
many (FT 10), its contact bits (FT 11), the negotiated MTU (FT 12), the Battery Service (FT 13, unused), Android's GATT
status codes and its connect timeout, that `autoConnect = false` plus our backoff reconnects reliably on the Xiaoxin Pad, and
the Control Point behaviour of a real trainer after a reset or another app taking control.
