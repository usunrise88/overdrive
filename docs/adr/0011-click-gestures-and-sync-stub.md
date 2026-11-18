# ADR 0011: The Click adapter, the gesture layer, the action router and the sync stub

Status: accepted (stage M4-1, 2026-11-18)

## Context

Stage M4 is the last of the Phase 1 MVP: the Zwift Click v2 handlebar controller, the gesture layer that turns its five
buttons into the ten ride actions of spec §4.7, the sync stub with its outbox (spec §4.9) and the release documents. The
Click is **not in reach** (roadmap, "Hardware verification is deferred"), and `docs/protocols/click-v2.md` is research notes
from two public codebases and **nothing observed on the bench**; its §7 says the plain path on the right puck is the only part
it supports ("Can we implement from these notes?"). So, as in ADR 0009 for the trainer and the strap, correctness rests on the
notes and on a fake radio written from the same notes, and every guess is kept **isolated, named and cheap to flip**.

M4-1, this ADR, is the part that needs no Android: everything in `core`. M4-2 wires it into the app (Click device ownership,
the Devices screen, background operation, the outbox on disk, settings, the final release documents). The API below is shaped
for that.

**Clean-room.** The bit table and the facts are those of `docs/protocols/click-v2.md`; no code was read, copied or ported from
BikeControl (any version), the Zwift Play library, trainer-control or any other project. The encryption hypothesis of the
notes' §3.3 is **not implemented**, and nothing here unlocks a controller.

## Decision

### 1. Packages, and what changed at the Bluetooth boundary

| Package (`app.overdrive.core.`) | Contents |
|---|---|
| `click` | `ClickCodec` (pure functions), `ClickMessage`, `HandshakeReply`, `ClickBits`, `ButtonEdges`, `ButtonChange`, `ClickAdvertisements`, `ClickOptions`, `ClickState`, `ClickEvent`, `ClickDevice` |
| `control` | `ClickAction`, `HandlebarButton`, `GestureTiming`, `GestureDetector`, `ButtonMapping`, `ButtonLearner`, `ActionRouter`, `MediaKeys`, `HandlebarControls` |
| `sync` | `SyncProvider`, `SyncResult`, `NoopSyncProvider`, `OutboxEntry`, `Outbox`, `InMemoryOutbox`, `SyncCoordinator`, `SyncStatus` |
| `ble` (existing, extended) | `ClickUuids`, `BatteryUuids`, `DeviceInformationUuids`, `ManufacturerData`, `GattTransport.writeWithoutResponse` |
| `ble.fake` (existing, extended) | `FakeClick`, `FakeClickOptions`, `FakeClickLock`, `ClickScript`, `ClickEncoder`; `ReplayPeripheral` refined |

`core` stays platform-free, adds no dependency and keeps explicit API mode. The changes to the existing boundary are additive
and source-compatible; four are worth stating because M4-2 meets them:

- **`GattTransport.writeWithoutResponse(characteristic, value)`.** The notes say the handshake is written *without response*
  (§3.1), and the boundary only had a write with response. The new method has a **default body that calls `write`**, so the
  Android transport of M3-2 compiles unchanged and, until M4-2 overrides it with `WRITE_TYPE_NO_RESPONSE`, the Click gets a write
  with response, which its `SYNC_RX` also offers (§2: "write, write-without-response"). The fake, the recording decorator and the
  replay already do it properly, and the fake answers a write without response to a characteristic that does not offer it with a
  GATT status, never a contract violation.
- **`Advertisement.manufacturerData`** (a list of `ManufacturerData(companyId, payload)`, default empty, value equality, `toString`
  prints the length only). A Click is recognised from the manufacturer data and the service, never from its name (§1), and the
  scan result had no manufacturer data. M4-2's `AndroidGattScanner` fills it from `ScanRecord.getManufacturerSpecificData`.
- **The recording policy cuts the Click's handshake key.** A reply that starts with `RideOn` keeps its first eight bytes (the word
  and the two version bytes) and the rest becomes zeros **of the same length**: a public key that is the same on every connection
  would name the unit (click-v2 §9 item 3 asks exactly whether it changes), and the length is what the bench question wants. Everything else follows the
  existing policy (`BleRecordingPolicy`): no address, the firmware revision (`0x2A26`) is `*`, the model is the advertised one.
  Unknown vendor frames are **not** scrubbed: the bench needs `0x3C` and the unlock message, and a raw recording never goes into
  the repository anyway.
- **`ReplayPeripheral` offers each connection's own characteristics** (a controller that hid its service until it was unlocked
  discovers differently on different connections) and **a recorded `disconnected` with status 0 is the central closing the
  link**, not a drop: a drop scheduled at the very moment the central closed could overtake the operation that led to the close
  and change what the replay shows. Existing fixtures replay unchanged.

### 2. The codec: the notes' §3.1 and §4 as pure functions

`ClickCodec` never throws: a malformed payload is a typed `DecodeResult.Malformed` (the codec result of ADR 0009), and **a type
it does not know is `ClickMessage.Unrecognised(type, body)`, never an error** (`0x3C` is documented as unknown, §4). It parses
`0x23` button state (a protobuf-style body; field 1 is the 32-bit word, **a clear bit is pressed**; other fields, in any order,
are skipped by their wire type; a varint longer than ten bytes or a cut one is malformed), `0x15` heartbeat, `0x19` battery
(field 2, `19 10 1F` is 31 %), `0xFE` disconnect notice, and the handshake reply (`RideOn`, two version bytes, an optional tail
that is ignored; a bare `RideOn` is accepted without a version). It writes `RideOn`, `FF 04 00` and the neutral frame
`23 08 FF FF FF FF 0F`. `ClickBits` is the table of §4 (A `0x0010`, B `0x0020`, Y `0x0040`, Z `0x0080`, Plus `0x1000`, and the
left puck's bits and the second Plus bit `0x2000`). `ButtonEdges` turns bitmaps into press and release edges: a repeat is no
change, a held button is reported once, all five buttons can change in one frame, and each change carries the **whole** held
state, so a consumer that missed one recovers from the next.

Vectors are **derived by hand from the notes** and documented in `ClickCodecTest` (CK-V1 to CK-V15: the neutral frame, each
button's frame by the varint rule, the notes' battery example, the handshake and the reply), plus random bytes of every short
length, every proper prefix and an encode-and-decode round trip, as `CodecFuzzTest` does for the other codecs. The fixture
files contain frames the fake encoded, and the hand-derived Plus frame `23 08 FF DF FF FF 0F` appears in them, which is a small
cross-check between the two.

### 3. `ClickDevice`: set-up, unlock and what the UI can explain

The shape is `HeartRateStrapDevice`'s: a `LinkControls` (`start`, `retryNow`, `close`, `status`), `connection`, a hot flow of
`buttons`, `events`, `battery` and `firmware` flows, and a `LinkSupervisor` that owns the reconnecting.

**Set-up per connection** (§3.1): discover; find the Zwift service (`FC82`, else the older one when `acceptLegacyService`) with
its three characteristics; ask for an MTU of 128 so that a reply with a 64-byte key arrives whole (a short MTU would still
carry `RideOn 02 03`); **enable ASYNC (notify), then SYNC_TX (indicate), one CCCD write at a time**; **write `RideOn` to SYNC_RX
without response**; wait for the reply, the first SYNC_TX value, which may arrive before the write returns (the wait is armed
first); check it (`HandshakeReplyCheck`); write `FF 04 00`; read the firmware revision and the battery level (optional steps).
"Read the reply" is waiting for the indication; the characteristic also offers a read (§2) but nothing says the reply is read.

**Unlock handling (spec §4.7).** `ClickDevice.state` is what the Devices screen and the ride's notice explain:

| State | Meaning |
|---|---|
| `Stopped` | not started |
| `BluetoothUnavailable(availability)` | Bluetooth off or the permission missing; nothing is tried, and it connects at once when fixed |
| `Connecting(attempt)` | an attempt is under way |
| `NotFound(failures, retryInMillis, wasConnected)` | could not be reached (off, asleep, away, held by another app), or a link that was up has dropped |
| `Waiting` | connected and set up, no frame yet, inside the no-event time |
| `Active` | connected and **delivering events** |
| `NeedsUnlock(SERVICE_HIDDEN)` | discovery lists no Zwift service (§6.1); the set-up backs off and tries again, so an unlock through Zwift is picked up by itself |
| `NeedsUnlock(NO_EVENTS)` | the handshake was answered and then **no frame at all** came within `noEventTimeout`; the link stays up and a late frame turns it into `Active`; `retryNow()` drops the link and starts over, for a rider who has just unlocked it |
| `SetupFailed(failure, retryInMillis)` | anything else in the set-up failed (the handshake reply, a subscription, a missing characteristic) |

Both unlock signals are options. Any well-formed frame counts as delivering (a button state, a heartbeat, a battery report, a
type nobody knows); a malformed one does not. The ride is **never** affected: `ClickRideTest` rides the same workout without a
Click and with a hidden-service and a silent one and asserts the three ride records are equal.

**A release lost in a drop never leaves a button held.** When a link ends with buttons down the adapter emits a synthetic
`ButtonChange` (`synthetic = true`, `held = 0`); the first frame after a reconnect is compared with "nothing down". Frames and
the end of a session go through **one coroutine in the device's scope**, in arrival order, so the synthetic release cannot
overtake a frame whatever thread the platform calls on, and nothing needs a lock.

### 4. The gesture rules, and why

There are no double clicks, so nothing waits to see whether a second click follows. `GestureDetector` is **pure**: no clock, no
coroutine; every call says what time it is and returns what fired, so the rules are tested in virtual time.

| Button | Fires on | Detail |
|---|---|---|
| Plus, Button 2, Button 3 (tap or hold) | **press: on release**, if down for **less than** 500 ms. **hold: once, at 500 ms**, while still down | elapsed of exactly 500 ms is a hold; nothing on the release after a hold |
| Button 4, Button 5 (volume) | **first step: on press**, no delay. **repeats: at 500 ms and every 150 ms** after it | the release does nothing; a two-second hold is 1 + 11 = 12 steps |

Why: the press of a tap-or-hold button cannot be told from the start of a hold, so the cost of having no double click is that
the press fires at the release (a tap is ~80 to 150 ms, so the delay is small and falls where the finger already is); the volume
buttons do not have the problem because a volume step is harmless to repeat, so the first step has no delay. The ten actions are
`ClickAction`; the table of which button fires which is `HandlebarButton` (one line each, so flipping a binding is one line).
The volume steps have their own actions (`VOLUME_UP_REPEAT`) so a log and a haptic can tell the first step from the held ones;
the router sends both to the same key.

The cases the rider's hands create, each tested (`GestureDetectorTest`):

- **Buttons are independent**, so two taps that overlap, as a thumb rolling from one button to the next does, both fire at
  their own release.
- **A hold needs the button alone.** If another button is down when a hold, or a repeated step, comes due it is skipped: a palm
  over the puck must not skip an interval and pause the ride at once. A skipped hold is gone for that press (the release does
  nothing either); a skipped repeated step is only missed. *A short chord is not guarded* (all five pressed and released within
  500 ms are five taps): the cost of guarding it is losing the rolling-thumb tap, and a short palm hit is the rarer and the
  cheaper accident.
- **No catch-up.** A caller that was late (the tablet slept, a coroutine stalled) gets one repeated step and the cadence
  resumes from the present, never a burst of the steps it missed; a hold fires once however late.
- **A very long hold** is one action for a tap-or-hold button and a steady 150 ms cadence for a volume button.
- **Duplicates** (a press of a button that is down, a release of one that is up) are ignored; **the clock stepping backwards**
  is treated as the last time seen: nothing fires early and nothing throws.
- **A lost release** (`cancelAll`, driven by the synthetic change) forgets every press and fires nothing for it: a press whose
  release was lost is not a press the rider finished.
- **Bounce** (`GestureTiming.bounceMillis`, **0 by default**): a release followed within that time by a press of the same
  button is one continuous press. The notes do not mention bounce and the controller reports debounced states, so it is off;
  turning it on delays every release by that time.

`HandlebarControls` is the coroutine around the detector: it sleeps until the detector's next deadline or the next change, reads
the **mapping at every change** (a different mapping forgets presses in progress), applies releases before presses with roles in
enum order, drops an action that throws, and reads time when a change is *processed* (the transport's clock is another one, and a
few milliseconds do not matter at 500 ms).

### 5. The button mapping, and *Learn buttons*

`ButtonMapping` is role to **mask** (a role is down while any of its bits is), never empty, never overlapping, with value
equality and a never-throwing text form (`serialize`, `parse`, exactly eight hex digits a role so a cut text is never read as
another valid mapping) for the settings file.

**The default is a guess, to be replaced by *Learn buttons*:** the right puck is a diamond (Y top, Z left, A right, B bottom)
with Plus under B (§4, "Physical layout"). The thumb rests at the bottom, so the two **gear** roles, used all ride, are the two
nearest: **Plus is gear up** (the symbol says so) and **B, directly above it, is gear down**. The **volume** pair is
left-right, like a slider: **Z quieter, A louder**. The rare, deliberate **play and pause / pause the ride** is the far one,
**Y**, which also keeps a stray thumb from pausing the video. Nothing in the notes says which button is which in the spec's
"Button 2 to 5".

`ButtonLearner` is the pure core of a *Learn buttons* screen: fed the Click's `held` mask after every change, it asks for each
role in turn and decides **on the release** of one press, so a second button joining is seen. It **rejects a combo** (two
buttons together, or one joining before the first is released; `allowMultiBitButtons` accepts several bits in the first frame, for
the notes' second Plus bit `0x2000`) and **a duplicate** (naming the role that has the button), and asks the same role again. It
can re-learn one role keeping the others (`roles`, `base`). `ignoredBits` is the escape hatch for a bit that reads pressed all
the time, the same as `ClickOptions.ignoredBits` which the device already applies.

### 6. The router

`ActionRouter(session, media)` (a `RideSession`, or a function that returns the current one, for an app whose session changes
from ride to ride) with `MediaKeys` (`playPause`, `volumeUp`, `volumeDown`) that the Android side implements. The rules are those
of the app's `RideActions`, restated in `core` and tested with the whole chain: in an **ERG** step (`RideSnapshot.mode`) the two
gear actions change **intensity by 5 points**; the pause toggle pauses while `RIDING`, resumes while `PAUSED` and does nothing
in any other state; the media actions need no ride. `RideActions` itself is untouched: M4-2 decides whether the app calls the
router instead, or the two are made to share the code.

### 7. The sync stub

`SyncProvider` (`upload(entry): SyncResult`, `Acknowledged`, `RetryLater`, `NotConfigured`) with `NoopSyncProvider`, which
**acknowledges nothing**, so every ride stays in the outbox for Phase 2. `Outbox` (`enqueue`, `pending`, `markDone`, idempotent
per ride id, suspending so that a disk-backed one is natural) with `InMemoryOutbox`; `OutboxEntry` is the ride id and a few facts
for a "waiting to upload" line, **no payload and no wire format** (Phase 2; spec §8.3 is not part of this stage).
`SyncCoordinator` drains the outbox oldest first and **never blocks or fails a ride**: `rideFinished(entry)` returns at once and
a failing outbox or provider is a `SyncStatus`, not an exception. It backs off like the Bluetooth links (`BackoffPolicy`, here
5 s, doubling, 15 minutes at most, ±20 %), a kick cuts the wait short, and `NotConfigured` stops without backing off, because
nothing changes by itself until a setting does. There is no network code.

### 8. The fake Click and the fixtures

`FakeClick` advertises the service and the manufacturer data, answers `RideOn` with `RideOn 02 03` (a key tail of any length,
a different version, a bare word, or silence, as options), delivers button frames, heartbeats and a battery report, and is
**locked** in the two ways the spec's unlock handling reads (`FakeClickLock.HIDDEN`: discovery lists no Zwift service;
`SILENT`: it answers and then says nothing; `unlock()` ends either). It is strict like the radio around it (frames only to a
host that subscribed, a write without response only to a characteristic that offers it) and records every write with its mode.
`ClickScript` is the small language: `press`, `release`, `tap`, `hold`, `drop`, `powerOff`, `silence`, `battery`, `notice`,
`unlock`, `lock`, `frame`, all at absolute times on the world's clock.

`contracts/fixtures/ble/click-*.click.session.txt` are five **generated** sessions (buttons, dropout, locked, unlock, silent):
golden (`ClickSessionFixturesTest`, regenerate with `-PregenerateBleFixtures=true`), anonymous (the model `Click`, the
firmware `*`, no key, no address), and each replays through the real adapter into the button changes, the states and the battery
the live run showed.

### 9. Every guess, named and cheap to flip

Each row is a default the notes could not settle. **A bench result changes one constant or one option, never the adapter.** The
options are `ClickOptions` (per device, so a developer setting can flip one without a build); `CK n` is item n of click-v2 §9
and `5.n` an item of `docs/bench-checklist.md` session 5.

| Choice | Name | Default | Evidence | Settled by |
|---|---|---|---|---|
| Device-type bytes of the two pucks | `ClickOptions.rightSideByte`, `leftSideByte` | `0x0A`, `0x0B` | §1, both "unconfirmed" | **CK 1**, 5.1 |
| Which pucks the app wants | `ClickOptions.acceptedSides` | right only | spec §4.7 | CK 1 |
| The Zwift service must be advertised | `requireAdvertisedService` | yes | §1 (checked before the manufacturer data) | **CK 12**, 5.2 |
| The older service counts | `acceptLegacyService` | yes | §2 | CK 12 |
| MTU asked for | `requestedMtu` | 128 | §3.1 (a key may follow the reply) | **CK 3**, 5.2 |
| Writes with response | `writeWithResponse` | no, without response | §3.1 | **CK 2**, 5.2 |
| How the reply is checked | `handshakeReply` (`EXPECTED_VERSION`, `ANY_RIDE_ON`, `NONE`) | `RideOn 02 03`, the tail ignored | §3.1, §3.2 | **CK 2, 3, 4**, 5.2 |
| Wait for the reply | `handshakeTimeout` | 5 s | unmeasured | CK 2 |
| Write `FF 04 00` after the handshake | `sendPostHandshake` | yes; a refusal is only reported | §3.1: trainer-control and the newer BikeControl do, the licensed tag does not; meaning unknown | **CK 6**, 5.9 |
| Read firmware and battery at set-up | `readStandardCharacteristics` | yes | §2 | CK 11 |
| Keep-alive toward the controller | `keepAliveInterval`, `keepAliveFrame` | none; the neutral frame `23 08 FF FF FF FF 0F` | §5 (none in the licensed code), §4 | **CK 7**, 5.8 |
| A hidden service means locked | `serviceHiddenMeansLocked` | yes | §6.1 | **CK 12**, 5.2, 5.3 |
| Silence after the handshake means locked | `silentMeansLocked` | yes | §6, §7 | **CK 2, 14**, 5.2 |
| How long to wait for a first frame | `noEventTimeout` | 30 s | §5: the heartbeat period is not known | CK 7 |
| A stuck bit of the bitmap | `ignoredBits` | none | §4 | CK 5, 5.5 |
| The bit table | `ClickBits` | the notes' table | §4 | **CK 5**, 5.5 |
| Which button is which role | `ButtonMapping.DEFAULT` | Plus gear up, B gear down, Y media, A volume up, Z volume down | §4 layout, section 5 above | 5.5, *Learn buttons* |
| A button that sets two bits | `ButtonLearner(allowMultiBitButtons)` | no (a combo) | §4 lists `0x2000` for Plus too | CK 5 |
| Hold threshold | `GestureTiming.HOLD_THRESHOLD_MILLIS` | 500 ms | spec §4.7 | 5.7 |
| Volume repeat | `GestureTiming.VOLUME_REPEAT_MILLIS` | 150 ms | spec §4.7 | 5.7 |
| Contact bounce | `GestureTiming.bounceMillis` | off (0) | not in the notes | 5.7 |
| Which button taps and which repeats | `HandlebarButton` (`press`, `hold`, `repeats`) | the spec's table | spec §4.7 | 5.6 |
| Upload backoff | `SyncCoordinator.DEFAULT_POLICY` | 5 s, ×2, 15 min, ±20 % | no source; Phase 2 | none yet |

Choices that are *not* options because the notes agree: a clear bit is pressed, the type byte first, the bitmap in field 1,
battery in field 2, the three characteristics and their UUIDs, `RideOn` written to SYNC_RX and answered on SYNC_TX.

## Consequences

For M4-2, the whole API:

```kotlin
// One Click, owned by the container like the trainer and the strap, started with them.
val click = ClickDevice(scope, clickId, transports, environment, name = "Click", options = ClickOptions())
click.start()                       // LinkControls: start(), retryNow() (the Devices screen's Retry), close(), status
click.state                          // ClickState: Active, NeedsUnlock(...), NotFound(...), Waiting, ... for the Devices row and the notice
click.buttons                        // hot SharedFlow<ButtonChange>: collect before start(); .held is the whole state
click.events, click.battery, click.firmware

// The ride: changes -> mapping -> gestures -> router -> session.
val router = ActionRouter({ host.session }, media)                   // media: a MediaKeys over AudioManager
val controls = HandlebarControls(scope, click.buttons, { settings.buttonMapping }, router::dispatch,
                                 nowMillis = { SystemClock.elapsedRealtime() })
controls.start(); controls.actions                                    // actions: GestureEvent flow, for the overlay's "what just happened"

// Pairing: ClickAdvertisements.recognise(advertisement) and .scanFilter(); Learn buttons: ButtonLearner.feed(change.held).
// Settings: ButtonMapping.serialize() / parse(); the Click's options as a developer setting.
// Sync: SyncCoordinator(scope, outbox, { provider }).start(); coordinator.rideFinished(OutboxEntry.of(record, now)).
```

M4-2 also has to: implement `writeWithoutResponse` in `AndroidGattTransport` and `BluetoothGattShim` (`WRITE_TYPE_NO_RESPONSE`),
fill `Advertisement.manufacturerData` in `AndroidGattScanner`, keep the Click session recording on the same decorator (its
redaction is done), write the unlock notice text, put the outbox on disk, and decide how `RideActions` and `ActionRouter` share
code. The ride engine, the simulated devices and every existing test are unchanged.

Known costs of the choices above: the press of Plus, Button 2 and Button 3 reaches the ride at the release, not at the touch;
a short palm hit on several buttons is several taps; a locked controller with a hidden service is reconnected to every 30 s at
most, which may interfere with an unlock in progress (unverified).

## Unverified until the bench

Everything above was built from the notes and tested against a fake written from the same notes, so **a green test proves
consistency with the notes, not with a Click**. In particular: that the right puck answers a plain `RideOn` without any unlock,
and with what bytes and what length (CK 2, CK 3); that the frames are plain and not encrypted (CK 4; the code does not decrypt
anything, so an encrypted Click would show as rejected replies or unrecognised frames in the events); the bit table, including
what `0x2000` is (CK 5); whether `FF 04 00` is needed, harmless or harmful (CK 6); what keeps the puck awake, how often it sends
a heartbeat and whether it needs one from us (CK 7); how long an unlock lasts on the device (CK 8); what `0x3C` is and whether
`0x19` is the battery percentage (CK 10, CK 11); whether a locked right puck hides its service or goes silent, or does neither
(CK 12); that the second connection after a drop works at all (a controller may refuse a reconnect until it has slept);
whether `connectGatt`'s write without response behaves like the fake's on the Xiaoxin Pad; that the media key and the volume
step reach the video app (overlay-notes §7, OV 9.9); and the hold timing as a hand feels it (5.7).
