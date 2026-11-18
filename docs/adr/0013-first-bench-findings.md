# ADR 0013: First bench findings: a bare `RideOn` is the plain reply, silence is not a lock, and the D500 resets after its own Request Control

Status: accepted (after the first bench ride, 2026-11-18)

## Context

The owner rode the signed `v0.1.0` for the first time on the real devices: the Van Rysel D500, a Coospo strap and the right
puck of a Zwift Click v2. Everything that follows is in `docs/bench-reports/2026-11-18-first-ride.md` (facts only; the raw
files stay with the owner). It was not run as the steps of `docs/bench-checklist.md`, so **no row of that checklist's results
table is filled in and no acceptance criterion is confirmed by it**. It did disprove three things the code assumed. Until now
every default was the notes' reading, tested against a fake written from the same notes (ADR 0009, 0011, 0012):

1. **The Click's reply to `RideOn` is the bare six bytes `RideOn`**, within about 50 ms, with no `02 03` and no key. The default
   check (**Reply check: Exact**, `EXPECTED_VERSION`) wanted `RideOn 02 03`, so the set-up failed and the row said "Could not
   connect": twenty attempts in a row, until the owner switched the check to Any RideOn in Developer · Click.
2. **A connected, answered Click sends nothing until a button is pressed** (sessions of 15, 67 and 33 seconds had no frame
   before the first press). The rule "answered and then silent for 30 s means locked" (`silentMeansLocked`, on by default)
   would show "Click sends no presses" on a healthy, idle Click.
3. **The D500 answers every Request Control with a Fitness Machine Status Reset (`01`)** and every Start with Started (`04`).
   The adapter takes a Reset for lost control and asks again, so each Reset starts another Request Control and Start pair: five
   pairs in the first 1.5 seconds. Harmless in the ride, but wasteful and noisy.

**Numbering.** The report labels some items with other numbers than the two lists they come from. The documents use the lists'
own numbers (`click-v2.md` §9 for CK, `ftms-hr.md` §9 for FT); this is how they map:

| The report says | In this repository |
|---|---|
| FT 8 (MTU 251) | FT 12 |
| FT 4 (Feature: power target and simulation offered) | bench 3.2 (`enforceFeatureBits`); FT 4 is the Status opcodes |
| FT 5, FT 6 (every Control Point command answered with success, replies fast; Start accepted) | FT 6 (timing), FT 7 (Start) |
| FT 7 (simulation values accepted) | FT 5 (resolution) and FT 8 (signedness) stay open; no rejection was seen |
| FT 9 (notification rates), FT 3 (the unannounced 14-byte tail) | none; FT 3 is the "More Data" question |
| FT 10 (the D500's statuses) | FT 4 |
| FT 11, FT 12 (heart-rate RR and contact bits) | FT 10, FT 11 |
| CK 9 (heartbeat-like stream after a press; the unknown `0x2A` message; battery by read) | CK 7, CK 10, CK 11 |

## Decision

### A1. The default reply check accepts a bare `RideOn`

Under `HandshakeReplyCheck.EXPECTED_VERSION` (the default, label **Exact**) `ClickDevice` accepts a reply that is the bare
`RideOn` (`HandshakeReply.version == null`, the plain, unencrypted path) **or** `RideOn 02 03` (a key after it is ignored, as
before). It still refuses any other version (`01 03`, the encrypted variant), a seven-byte reply (half a version) and bytes
that are not `RideOn`, naming the first eight bytes in `HandshakeReplyRejected`. `ANY_RIDE_ON` and `NONE` are unchanged. The
codec already parsed a bare reply as `version = null`; only the check changed.

### A2. Silence is not a lock: the rule is opt-in

`ClickOptions.silentMeansLocked` is **off by default**. An answered handshake is `ClickState.Active` at once, with no frame.
With the rule on (opt-in) nothing changes from before: `Waiting`, then `NeedsUnlock(NO_EVENTS)` after `noEventTimeout`, a late
frame clears it. **`serviceHiddenMeansLocked` is unchanged**: a Zwift service missing from discovery is still
`NeedsUnlock(SERVICE_HIDDEN)` (the report saw the service on a right puck that needed no unlock, so a hidden service stays the
only sign of a lock that has ever been described).

In the app (`app-ride`) the Click's row says "press any button" for `Waiting`, and the notice "Click sends no presses" exists for
`NO_EVENTS`: both are kept, and both now appear **only with the rule on**. Developer · Click gets a switch, **Silence means
locked** (default off), persisted under its own key (`dev_click_silent_means_locked`) and read as off when the key is absent, so
the settings stored by `v0.1.0` (the owner's Reply check among them) load unchanged. **Silence limit** applies only with the
switch on; its description says so.

### A3. The fake Click is the real puck by default

`FakeClick`'s default behaviour is what the report saw: a bare `RideOn` reply, **no frame until a press** (no heartbeat, no
battery frame), the battery by read only (`0x64`, 100 %). The old behaviour is kept as an explicit variant
(`FakeClickOptions.byNotes()`: `RideOn 02 03` with an optional key tail, a battery frame, a heartbeat every five seconds).
`FakeClickOptions.realPuck()` adds what the report lists of the real GATT table and set-up: the three extra vendor
characteristics `…0100`, `…0101` and `…0102` (the report gives properties for `…0101` and `…0102` only; `…0100` has the same as
`…0101` here, nothing uses them), SYNC_RX write-without-response only, an ATT MTU of 251 whatever is asked, and the unknown
`0x2A` message right after the notifications are enabled. Tests that exercise the silence rule or the `Waiting` state build their
options with `silentMeansLocked = true`; the scenario `click-silent` is that opt-in rule, and `click-real-puck` (synthetic, the
protocol facts above only) is new. The fixtures stay synthetic and anonymous; no byte of a real recording was copied.

### B. The trainer adapter ignores the D500's own Reset

`FtmsSession` records when it completed a Request Control. A Reset status within **`FtmsOptions.selfResetGrace`** (default
1500 ms) of that moment is the trainer's answer to our own request and is **ignored** (it is still reported as
`StatusReceived`; no `ControlLost`, no recovery). A Reset outside the window, and **Control Permission Lost (`0xFF`) at any time,
are not ignored**: they set control to not held and are recovered as before, and a real loss is also caught by the next command
failing and the engine's retry. The fake trainer reproduces the D500 with `resetsAfterRequestControl` (a Reset a few
milliseconds after the Response Code) and `statusRepeatInterval` (the last status again every 240 ms). The codecs are untouched:
Indoor Bike Data already decodes on the real D500.

## Consequences

- A real right puck connects with the default settings, and an idle one is shown as connected. The only visible lock sign left is
  the hidden service.
- A Click that is truly locked **and** silent (never seen) is no longer announced by default: the rider sees an active Click that
  does nothing, and presses a button, which is what the old row asked for anyway. The opt-in switch brings the notice back for
  the bench.
- The D500 gets one Request Control and one Start at connect. The grace is a window, not a state: a Reset that is the D500's
  repeated last status (it re-sends the last status about every 240 ms) after a Request Control with **Start switched off**
  (`startAfterRequestControl = false`) would outlast it; that combination was not seen and is not covered.
- `docs/bench-checklist.md` 5.2, 5.3 and the Click settings table say the defaults now fit the observed Click.

## Still open (nothing below was settled by the report)

- **Acceptance criteria.** None is confirmed: not 1 (overlay frame times), not 2 (the ERG hold looked good, a sign only), not 3
  (the ten actions with a video app in front), not 4, 5, 6 (the FIT file has not been imported anywhere) or 7.
- **The Click.** The advertisement (CK 1), the left puck and encryption (not tried), `0x1000` (Plus, shift up) and `0x0080` (Z)
  not pressed in the files, the firmware version and how long since Zwift last touched the puck, what the Click does after 210
  seconds (three linked sessions ended with status 8 at 135, 183 and 210 seconds; range or sleep), the heartbeat after a press,
  the meaning of `0x2A` and of `FF 04 00`.
- **What the report does not explain.** (a) It names the puck as the right one, yet a Minus press (`0x0100`) was seen, which the
  notes give the left puck; (b) the Plus it saw is `0x2000` ("shift down") and never `0x1000`, while `ButtonMapping.DEFAULT` has
  Plus as `0x1000`, so Plus is not the default's gear up unless the buttons were learned (the report says the gear buttons
  work and does not say how they were mapped). The next bench run should record the learned mapping and settle CK 5 before the
  default is touched; nothing in the code changed for it.
- **The trainer.** Whether Start/Resume is needed, the unannounced 14-byte tail of Indoor Bike Data, the trainer's behaviour with
  Bluetooth switched off or unplugged (AC 4), and long rides. FT 1 is settled: bit 2 is cadence.
- **The Reset's order.** The fake sends the Reset after the Response Code, which is the order that produces the ping-pong
  through the adapter's recovery; the report does not say which came first on the D500. A Reset before the response is
  harmless (control is granted right after it), at worst one needless re-send of the last target.
