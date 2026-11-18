# Bench report 1: the first ride on real devices (2026-11-18)

The owner installed the signed `v0.1.0` on the tablet and rode a short workout ("Test ride, 10 minutes", stopped after
3 minutes 44 seconds) with the Van Rysel D500 trainer, a Coospo heart-rate strap and the right puck of a Zwift Click v2.
The app's own Bluetooth session recordings (`Settings, Developer, Share Bluetooth logs`) and the exported FIT file were
sent back. This report is what they show. **Facts only: no heart rate, no power series and no device address is copied
here** (the repository is public; `CLAUDE.md`). The raw files stay with the owner. This was not run as the steps of
`docs/bench-checklist.md`, so no row of its results table is filled in from it and no acceptance criterion is confirmed;
each section says which open question (FT, CK, SP9) the observation bears on and how firm it is.

What the owner reported by eye: the trainer connects, the overlay works over video, the Click's gear buttons change gears
and its media buttons work, and the Click link comes up only after a change in **Developer · Click** (below).

## 1. Zwift Click v2, right puck

Recorded in 28 Click sessions (`…-click.txt`), the first 20 of them failures.

| Observation | Evidence | Bears on |
|---|---|---|
| The advertised GATT has the Zwift service `FC82` with `…0002` (notify, the ASYNC characteristic), `…0003` (**write without response only**, SYNC_RX), `…0004` (read and indicate, SYNC_TX), and three more vendor characteristics `…0100`, `…0101` (write, write without response, notify) and `…0102` (write without response, notify) that the app does not use. Battery service `180F` with `2A19` (read, notify), device information with `2A26`. | every session | CK 12: the custom service **is visible** on this puck |
| The ATT MTU negotiates to **251** when the app asks for 128. | every session | CK 3 |
| Set-up as implemented works step by step: notifications on ASYNC, indications on SYNC_TX, `RideOn` (`52 69 64 65 4F 6E`) written **without response** (status 0). | every session | CK 2: a write without response is accepted |
| **The reply to `RideOn` is the bare six bytes `RideOn`** (`52 69 64 65 4F 6E`), within about 50 ms, as an indication on SYNC_TX. No `02 03` version bytes and no key. | every session | CK 3, CK 4: the plain, unencrypted path; **the notes expected `RideOn 02 03`** |
| With the default check (**Reply check: Exact**) the set-up failed and the row said "Could not connect": 20 attempts in a row, each ending with the app dropping the link about two seconds in. | first 20 sessions | the cause of the first report |
| The puck connected and delivered buttons **without any unlock through Zwift** (it is a right puck). | sessions 21 on | spec §9 "Click v2 unlock bypass", spec §2: **supported for this puck** (a single observation; the Click's firmware version and how long since Zwift last touched it were not recorded) |
| The post-handshake write `FF 04 00` was accepted (status 0); no visible effect. The firmware revision and the battery were read (battery `0x64`, 100 %). | sessions 21 on | CK 6 (no effect visible), CK 9 (battery by read) |
| **A connected, answered Click sends nothing until a button is pressed.** Sessions of 15, 67 and 33 seconds had no frame at all before the first press (they ended without one). The rule "answered and then silent for 30 s means locked" would have shown a false lock notice on a healthy, idle Click. | sessions 21 to 23 | CK 2, CK 14: **silence is not a sign of a lock** |
| Button frames are `23 08 <varint>`: the bitmap as a five-byte varint, `FF FF FF FF 0F` for all released. Pressed bits seen: `0x0010` (A), `0x0020` (B), `0x0040` (Y), `0x0100` (Minus), `0x2000` (Plus, shift down). `0x0080` (Z) and `0x1000` (Plus, shift up) were not pressed in these files. No single press set two bits. | 527 frames in 4 sessions | CK 5: the notes' table holds for the bits seen |
| After a press the Click keeps sending frames, mostly all-released, about every 90 to 120 ms for a second or more, then goes quiet; a press shows in one or two frames with its bit cleared. | sessions 24 on | CK 7, CK 9: a heartbeat-like stream **only around presses** |
| One unknown message `2A 08 03 12 12 12 10 08 18 10 00 18 D8 04 22 05 FF FF FF FF 1F 28 00` (opcode `0x2A`) arrived on ASYNC right after notifications were enabled in 3 of 28 sessions. | 3 sessions | CK 9: a message type not in the notes; ignored harmlessly |
| A connection attempt with the Click not reachable ends after the app's 30-second limit with status 147. Linked sessions ended with status 8 (link supervision timeout) after 135, 183 and 210 seconds; the app reconnected each time. No keep-alive was sent. | sessions 25 to 28 | CK 7: **no keep-alive was needed for three minutes**; why the links ended is not known (range, or the Click sleeping) |

## 2. Trainer: Van Rysel D500 over FTMS

One session of 1020 seconds (4059 Indoor Bike Data and 345 Status notifications, 103 Control Point writes).

| Observation | Bears on |
|---|---|
| Services: FTMS `1826` (Control Point `2AD9` write, write without response, indicate; Indoor Bike Data `2AD2` notify; Status `2ADA` notify; Feature `2ACC`; three range characteristics), Cycling Power `1818`, Cycling Speed and Cadence `1816`, plus vendor services (a Nordic DFU `FE59`, a Nordic UART and two others) the app does not use. Negotiated ATT MTU **251**. | FT 8 (MTU) |
| Fitness Machine Feature: machine features `0x00004003` (average speed, cadence, power measurement), target settings `0x0000A00C` (resistance target, **power target**, **indoor bike simulation**, spin down). | FT 4: both ERG and simulation are offered |
| **Indoor Bike Data: the flags word is `0x0044` in all 4059 notifications**: bit 0 clear (speed present), **bit 2 set (instantaneous cadence)** and bit 6 (instantaneous power). Decoded with the decoders' reading (bit 2 is cadence, the code's default) the numbers are consistent (cadence up to 84 rpm, power up to 343 W, no negative power, speed up to 23 km/h at 0.01 km/h). | **FT 1: bit 2 is cadence, confirmed** |
| Every notification is **22 bytes**: the 8 bytes the flags announce (flags, speed, cadence, power) followed by 14 bytes the flags do not announce; two of the trailing words repeat the cadence and the power (plus one). The codec reads the announced fields and ignores the rest, which worked. | FT 3 (layout): an unannounced tail; origin unknown |
| Notification rate: median **239 ms** (about 4 Hz), longest gap 1.84 s. | FT 9 (rates) |
| **Control Point: every command was answered with success**: Request Control `00` (5), Start or Resume `07` (5), Set Target Power `05` (55), Set Indoor Bike Simulation `11` (38); responses `80 <opcode> 01` arrive within about 60 ms, often before the write callback. No rejection. | FT 5, FT 6: Start is accepted (whether it is needed is not shown); replies are fast |
| **ERG holds the target**: in a ramp from 98 W to 138 W in 4 W steps every 12 seconds, the measured power four to nine seconds after each step was within about 3 W of the target (a last step ran 12 W high). Target zero (paused) read zero power. | AC 2: a good sign, **not** a confirmed criterion |
| Simulation parameters were accepted: wind 0, grade between about -4 % and +5 % in 1 % steps, rolling resistance `0.004`, drag `0.21`. | FT 7: no rejection of these values |
| **Status behaviour of the D500:** a Request Control is followed by a **Reset (`01`)** status, a Start by **Started (`04`)**, and the trainer **re-sends the last status about every 240 ms** (`08 <watts>` after a target power, `12 <parameters>` after a simulation set). | FT 10 (status): new |
| **The Reset after each Request Control makes the adapter treat control as lost and request it again**, which produced a ping-pong of 5 Request Control and Start pairs in the first 1.5 seconds before it settled. Harmless in the ride, but wasteful and noisy. | follow-up (ADR 0009 §8) |

Speed in the exported FIT is the app's physics speed (up to 36.6 km/h), not the trainer's own (up to 23 km/h): by design.

## 3. Heart-rate strap

Advertised model **H6M** (the owner expected an HR7). Standard Heart Rate service `180D` (`2A37` notify, `2A38`), battery, and a vendor service `FD00`. 950 notifications over 950 seconds.

| Observation | Bears on |
|---|---|
| Flags `0x16` in 939 notifications: 8-bit heart rate, **sensor contact supported and detected**, **RR intervals present** (several per notification: 1864 intervals in total). Flags `0x14`/`0x04` (contact supported, **not detected**) in 11 notifications, 10 of them with no RR. | FT 11, FT 12: RR and the contact bits work as the notes say |
| Notification rate: median 940 ms, longest gap 1.5 s. The link ended with status 19 (remote user terminated: the strap was switched off or taken off). | |

## 4. The exported FIT file

224 records, one per second, a 3 minutes 44 seconds activity; one session, two laps (one per workout step), a start and a stop event. Power, cadence, heart rate (14 seconds invalid, as the strap's gaps), speed, distance 1137 m. Structure and fields as `docs/protocols/fit.md` §6. **Not yet imported into intervals.icu** (FIT 1 to FIT 8, acceptance criterion 6 stay open).

## 5. What changed in the code because of this report

- The default handshake check accepts a bare `RideOn` (the unencrypted path) as well as `RideOn 02 03`; `01 03` still fails.
- A Click that answered the handshake is a working link; "silent means locked" is off by default.
- The trainer adapter ignores the D500's Reset that follows its own Request Control.
- The Click row says "press any button" while it waits for a first frame (only with the opt-in silence rule on).

## 6. Still not known

The advertisement of the Click (manufacturer data, CK 1), left puck and encryption (not tried), what the Click does after 210 seconds, the Click's firmware version, the unknown trailing 14 bytes of Indoor Bike Data, whether Start/Resume is needed, the trainer's behaviour on Bluetooth switched off or unplugged (AC 4), the overlay's frame times, hold timing, long rides, FIT import.
