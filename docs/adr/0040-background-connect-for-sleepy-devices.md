# ADR 0040: Background connect for sleepy devices

Status: accepted (release 0.4.1, 2026-11-22). **Written from the owner's Bluetooth logs of rides with 0.4.0-rc.1; not yet ridden
with this change** (the last section says what could not be checked without the devices).

## Context

The Zwift Click goes to sleep a few minutes after its last use; a press wakes it and it advertises for a short while. The app
connected with `connectGatt(autoConnect = false)` and gave each attempt 30 s (Android's own limit, status 147 or 133), then waited
2 to 40 s (backoff: 1 s, doubling, 30 s cap, jitter) before the next attempt. The logs show only that cycle: connecting, 30 s,
status 147, closed, wait, connecting again. The Click's short advertising window usually falls into the app's waiting gap, or the
attempt ends first, so a press does nothing, or wakes it and the connection arrives seconds later. The same cycle cost the trainer
three attempts (about 3 minutes) at the start of one ride and the strap two attempts in another.

## Decision

**A hybrid strategy for all three devices** (`ConnectStrategy.HYBRID`, in `LinkSupervisor`; `ConnectMode` in the transport factory):

1. **The first attempt of a connection is active**: `autoConnect = false`, limited by the supervisor to **10 s** (the platform's 30 s is for
   a device that is awake and slow, which a trainer in the room is not).
2. **When it does not succeed, a background connect**: `autoConnect = true`, **no limit of the app's own** while Bluetooth is on and the device
   is wanted. The controller keeps a pending connection and completes it the moment the device advertises, which is what a Click woken by a
   press needs. The transport has no connect timeout in this mode (`AndroidGattTransport`).
3. **A link that drops** (the Click going to sleep, status 8, 19 or 22) goes **straight to the background connect** after the usual short
   pause (the backoff's first step, 1 s, while a link that held under 30 s keeps backing off).
4. **Pitfalls of `autoConnect = true`, and what guards each:**

| Pitfall | Guard |
|---|---|
| A pending connection that has gone stale in the stack | A **watchdog of 10 minutes** (`ConnectStrategy.backgroundWatchdog`): the transport is closed and a new one opened after a 1 s settle. Not a failure: no backoff, the row keeps saying it waits. |
| Two outstanding connects for one device | The supervisor is one sequential loop and opens the next transport only after the previous one is closed; the fake radio fails the test if it ever sees two. |
| A failed or dropped GATT object reused (ADR 0009) | Unchanged: every attempt, background or not, is a new transport; a failed or dropped one is closed and replaced. |
| Status 133 | An ordinary failed attempt: the transport is closed, the usual backoff applies, the next attempt is a background one. |
| Bluetooth switched off or the permission revoked | The connect step watches the environment: the pending connect is closed at once, the existing state (`BluetoothUnavailable`) is shown, nothing is tried; when Bluetooth is back the first attempt is active again (the device may be awake). |
| **Retry now** | During the first, active attempt: nothing (as before). During a pending background connect or its pause: it is **replaced by an active attempt at once**, and the backoff starts over. |
| The rider un-pairing (Forget) | `LinkControls.close()` cancels the supervisor's job; the attempt's `finally` closes the transport (on Android `disconnect()` then `close()`, which cancels the pending connect). Tested through `PairedDevices.forget`. |
| The setting changing while the app runs | The strategy is read at the start of every attempt. |

5. **What the rider sees.** A new status, `LinkStatus.WaitingForDevice` (and `ClickState.WaitingForDevice`): the row says **"Waiting for the Click
   to wake up"** (the trainer: "Waiting for the trainer to wake up", the strap: "Waiting for the strap to wake up"), with no countdown and no
   word like "lost" or "retrying", no error toast. It is shown after the active attempt found nobody, after a link that dropped, after a
   connect that failed with 133 or timed out, and while a background connect is pending. A set-up that really fails (a service is missing) still
   says what is wrong, with its backoff.
6. **Switchable.** Settings, Developer, **Connect in the background** (default **on**). Off is the 0.4.0 behaviour exactly
   (`ConnectStrategy.LEGACY`: active attempts, 45 s total, exponential backoff). The timings are `ConnectStrategy` fields
   (active 10 s, watchdog 10 min, settle 1 s) and `BackoffPolicy` as before.
7. **The Bluetooth session log** says which connects were background ones (`S - - connecting-background`, replayed like `connecting`).

Core's default is `LEGACY` (so a caller that does not pass a strategy keeps the old behaviour); the app's container passes the setting.

## Tests

`BackgroundConnectTest` (supervisor, fake radio, virtual time): the active attempt then the background one; a wake after 5 minutes connects at
once with no churn; a drop goes straight to the background; the watchdog recycle (three connects in 25 minutes, one outstanding); Forget cancels
and nothing opens again; Retry now (pending and during the first attempt); Bluetooth off during a pending connect and back; status 133; a real
set-up failure still shown as one; the legacy strategy unchanged; the strategy switched while running. `BackgroundConnectDevicesTest`: the
Click, the strap and the trainer, the log tokens. Android: `AndroidGattTransportTest` (the flag reaches `PlatformGattOpener`, no limit in
background mode, close cancels, 133), `AdaptersOverAndroidTransportTest` (a sleeping trainer through the real transport over the fake radio),
`BleLogTest` (the logger passes the mode on), the Devices rows, the setting's default and persistence. The one line that is not unit-tested is
`BluetoothGattOpener` passing the flag to `connectGatt` (Robolectric's shadow does not keep it): it is a one-argument pass-through.

## What could not be verified without the devices

- **How long from a press to connected.** In the fake radio it is the connect latency; on a real controller it is the time the Click needs to advertise
  and the controller's scan duty for background connects. **Number of seconds: unknown until the bench.**
- **HyperOS (the Xiaoxin Pad) may batch, delay or throttle background connects**, or stop them when the app is in the background or the screen is
  off. If a press takes 10 s or more, or never connects, the setting can be turned off and the log read.
- That the controller really completes a pending `autoConnect` connect for a device that was never bonded, on this tablet (it should: the
  device is addressed by its address, which the app remembers).
- Whether 10 s is the right active limit and 10 minutes the right watchdog.
- That several pending background connects (trainer, strap and Click at once) are all served: a controller keeps a white list of limited size.

## Consequences

- A device that is off all day costs one transport opened and closed every 10 minutes instead of an attempt every 30 s: less radio use.
- A trainer that is simply not switched on at the start of a ride now shows "Waiting for the trainer to wake up" instead of "Not found" with a
  countdown; the ride's "Waiting for trainer" notices are unchanged (they read the connection state).
- A rider who wants the old behaviour has the setting.
