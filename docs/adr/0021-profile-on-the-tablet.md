# ADR 0021: The rider's profile on the tablet

Status: accepted (Phase 2 fix, 2026-11-18, found on the owner's tablet with v0.2.0). The service's half (`GET`/`PATCH /v1/profile`,
the MCP tools `get_profile` and `update_profile`) is ADR 0018 section 8 and is unchanged; this ADR is the ride app's.

## Context

An AI client set the rider's FTP to 175 W through `update_profile`, and the tablet went on riding at its own 200: a planned
"63% FTP" warm-up was 126 W instead of 110 W, and every planned workout was distorted. The profile existed on the service (ADR
0018) and spec §8.3 says both apps use it, but the tablet never read it: FTP, mass, CdA and Crr were only the tablet's own
settings. The ride app must work fully offline and has no dependency on anything but our own service (CLAUDE.md), so the answer
cannot be "ask the service before every ride".

## Decision

### 1. The profile is the source of truth for FTP and the physics, when there is one

Where a profile is **in force** the ride, the Ready screen and the Settings rows use it:

| Value | In force when a profile is |
|---|---|
| FTP | the profile's `ftp`; **a profile whose `ftp` is null leaves the tablet's own** (the owner has not set one) |
| mass, CdA, Crr | the profile's `physics.systemMassKg`, `cdA`, `crr` (it always has them, the engine's defaults where nobody set them) |
| air density, drivetrain efficiency | the profile's; the tablet has no setting for them, so they are the engine's defaults without a profile |

A profile is **in force** when the tablet has an address and a token and the profile known is the one of that address: fresh from
the service, or the last good one from the cache. With no address or token, or while the service was never reached and nothing is
cached, **no profile is in force and the tablet's own values apply exactly as before**: that is the offline-first rule, and the
tests assert it (a planned 63% step is 126 W at the tablet's 200 W).

The tablet's **stored settings are never overwritten** by the service's values. The effective value is **derived** (`AppSettings.
withProfile`) every time, so clearing the address returns the rider to what they set on the tablet. `AppSettings` gained two fields
that are never stored, `airDensity` and `drivetrainEfficiency`, so that the ride engine's `PhysicsSettings` is complete
(`toRideSettings` passes all five constants); both are in `rideInputs()`, so a change of either rebuilds a ready ride.

The derived value is **not** snapped to the tablet's grid: the service holds an FTP of 177 W or a mass of 82.5 kg and the ride uses
it as it is. (The Settings steppers step from it onto the tablet's grid; an FTP the service holds above the stepper's 600 W has a
stepper that cannot step, which is accepted.)

### 2. Where it lives

- **`core`** (`app.overdrive.core.profile`, no Android): `ProfileRepository` (read, cache, edit, send, state), `ProfileCache` and
  `ProfileCacheFormat` (what the cache file holds), `ProfilePatch.andThen` and `ProfileApiJson.encodePatch` (the tablet writes
  patches; they are tested against every `contracts/fixtures/profile/patch` fixture). In `core.sync`: `HttpPatchTransport` beside
  `HttpGetTransport`, and `reasonForStatus` (the plain words for an answer that is not the one asked for), which the plan
  repository now shares.
- **`android/data`**: `UrlConnectionTransport` implements the third transport interface (one class, one stack, the same timeouts, no
  redirects, nothing logged); `FileProfileCache`; and `AtomicTextFile`, the atomic whole-file write the plan cache had, now shared.
- **`android/app-ride`**: the container owns the repository and exposes `effectiveSettings`; `ProfileUi` turns the state into the
  words of the Settings rows; the Settings screen shows the markers.

### 3. When it asks, and what (the plan's rules, one gesture for both)

`GET /v1/profile` at app start (once the settings have been read from disk and the cache read); when the Ready screen comes on
screen, **at most once a minute**; after a change of address or token that has **settled for 1.5 s**; and on the plan's refresh
button, which refreshes **both** (one gesture). One request at a time; a settings change and an edit that arrive during one are made
afterwards, none is lost (the same lock-then-look-again rule as the plan, ADR 0017 section 2). A rejected token (401) is remembered
with the address and token and not asked again until either changes or the rider asks. Same wording for failures (`TransportException.
plainReason()`: "Cannot find the service address (DNS) (UnknownHostException)", "Service error (503)", ...). Nothing logs, the token is
only in the header, the state never holds it, the cache file holds no address (a checksum of it) and no token.

Nothing runs on the main thread: the transport is `Dispatchers.IO`, the repository runs on the container's scope, the screens read a
`StateFlow`. The Ready screen and the Settings screen read `effectiveSettings`, which changes only when a value does, so nothing
recomposes at rest; the ride is rebuilt by the existing debounced rule (a ride that is ready is rebuilt 400 ms after a ride input
changes, **a ride that has started is never replaced**, so an FTP that arrives mid-ride applies to the next ride and the record of the
ride under way keeps the FTP it started with).

**The first ride built after a start waits for the profile to be known** (the cache read and the settings looked at: `ProfileState.
ready`), so it is built with the right FTP and not with the tablet's own for a moment. This costs the few milliseconds the cache takes
to read, **and never more than 3 s**: past that the tablet's own values apply until the profile is known (ADR 0029).

### 4. The cache file

`filesDir/profile/profile`: a header line `overdrive-profile 1 <source> <crc32>`, then the service's profile on one line, then the
**one unsent edit** on a second line (empty when there is none). `<source>` is a CRC-32 of the service address (a profile cached for
another address is not this rider's, so a changed address leaves it unused); the checksum is over everything after the header.
Written through a temporary file that is forced to storage and renamed over the cache (an atomic move), read strictly: a missing or
unreadable file, a truncated or edited one, an unknown version, a profile that is not valid by the schema, an edit that is not a patch
of the schema or does not make a valid profile of this one, all read as **no cache**, never as an exception and never as half a
profile. A write that fails costs the cache and nothing else; the next change or refresh writes again.

### 5. Editing on the tablet

The Settings rows for FTP, mass, CdA and Crr show the **effective** value, and beside it a small marker: **From service** when the
number is the profile's, **Not sent yet** when the rider changed it and the service has not taken it, nothing when it is the tablet's
own. (Quiet text, no colour: the zone colours are the only accent.) The note under FTP is replaced by one plain line when the last
request failed ("Profile: Service unreachable · showing the last known profile").

A step on one of the four is `AppContainer.changeRider`: the lambda is given the settings **as the screen shows them** (so a step goes
from 175 and not from the stored 200) and returns them changed.

- **With a profile in force** the values that differ become a `PATCH /v1/profile` (a merge patch: `{"ftp":180}`,
  `{"physics":{"systemMassKg":83.0}}`) and are put on top of the profile in force **at once**, so the number moves as the rider taps
  (a step repeats every 150 ms: the value never waits for the network). Mass, CdA and Crr go the same way as FTP; the path is
  the same code and cost nothing extra, so they are **editable and synced, not read-only**.
- **If the service cannot be reached** (or fails for a moment: 5xx, 408, 429) the edit stays: it is the one **pending patch**, the
  edits merged with the last write winning per field (`ProfilePatch.andThen`), written to the cache **before** the request is made
  (so a kill or an offline spell loses nothing), in force on the tablet, marked "Not sent yet", and sent first by the next refresh,
  start or screen opening. When it gets through, the answer to the `PATCH` (the whole profile) is the fresh profile and no `GET`
  follows.
- **If the service refuses it** (a 4xx other than 401, 408 and 429; in practice `422 invalid_profile`) the edit is dropped, so it is
  not sent again and again, and the profile in force goes back to the service's; the line says "The service refused the change (422)".
  The tablet's own ranges are inside the profile's, so this should not happen.
- **If a rejected token** (401) is the answer, the edit is kept and sent when the rider asks or the token changes.
- **With no profile in force** a step is an ordinary change of the tablet's own settings, as before.

An unsent edit belongs to the address it was made on: another address has its own profile and the edit is not sent there.

### 6. The ride record, and the plan's intensity hint

The ride engine is built from the effective settings, so `ride.ftp` of the record (`RideHeader.ftp`), the snapshot's `ftp`, the zones
on the ride screen, the target watts and the Ready screen's "Hardest step" all use the **effective FTP**; the record of a ride at
175 W says 175 (tested: the stored record file is read back). Nothing in the app stores the tablet's own FTP in a ride.

The planned row's **IF hint does not use FTP at all**: it is the normalised power of the workout's own targets, which are fractions
of FTP, over FTP (ADR 0017 section 5), so a planned workout is "IF 0.76" at 175 W and at 200 W. `PlanUi.of` has no FTP parameter, so
there is nothing to feed it. What changes with FTP is every number in watts: the targets on the ride, the Ready detail's step table.

### 7. The PATCH verb

`HttpURLConnection` on a plain JDK refuses the verb PATCH; Android's (OkHttp inside) accepts it. Production sets `requestMethod =
"PATCH"` and nothing more. The two test suites that run on a JVM (`android/data` and the container tests) pass the transport a way
round it (`setPatchMethod`, a test helper that writes the verb into the connection with reflection, allowed by `--add-opens
java.base/java.net=ALL-UNNAMED` in those modules' test tasks). So the transport is proven against a real local server on a JVM, and
**the Android side of the verb is the one thing no test here exercises** (below). A platform that refuses the verb is a failed request
(`Could not talk to the service (ProtocolException)`): the edit stays pending, the profile is still read, nothing crashes.

## Deviations from the brief

- **Mass, CdA and Crr are editable and synced** with the same path, as the brief allowed "if it is cheap".
- **The IF hint needs no change**: the brief said it must use the effective FTP, but it uses none (section 6). What was needed there
  is nothing.
- **`ready` also waits for the settings.** The brief did not ask for it; without it the first ride after a start could be built with
  the tablet's own FTP and replaced 400 ms later.
- **A refused edit is dropped** rather than kept: not in the brief, which only said "if the PATCH fails (offline), keep it".

## Not done, deliberately

- **No migration of the tablet's own values to the service.** A tablet whose owner tuned mass or CdA locally and whose service profile
  was never written rides, once the service answers, with the **service's** physics (the engine's defaults where nobody set them).
  That is the rule asked for ("the service is the source of truth") and the marker "From service" says it; to keep the tablet's
  values the rider changes them on the tablet, which sends them. A rule such as "a profile nobody has written (`updatedAt` null) does
  not override the tablet's physics" is one line in `AppSettings.withProfile` and is for the owner to ask for.
- **No zones from the profile.** The ride screen's zones are Coggan's, as `PowerZone`; the profile's `zones` are the service's (the
  plan and the AI client). Using them on the tablet is a decision about the overlay's colours, not about this fix.
- **No edit of the profile's availability, goals or zones on the tablet.** The patch writer supports every field of the schema (and is
  tested against all the fixtures); the screens edit four.
- **No background retry.** An unsent edit is sent when the app starts, when the Ready screen opens (within the minute rule), on the
  refresh button and on a settled settings change.

**Update (stage A1c, ADR 0034):** the mass is the one value the rider's weight can change: with a rider weight (7-day average) and a bike weight in the profile it is their sum; the precedence of the profile over the tablet's own is unchanged.

## What only the real tablet can settle (bench 7.11)

- Whether Android's `HttpURLConnection` sends `PATCH` with a body to the service as the JDK test server saw it, over TLS through
  Caddy. If it does not, the line under FTP says "Could not talk to the service (ProtocolException)" and the fix is a different
  transport for that one verb.
- That the tablet follows an FTP set through the MCP `update_profile`: the Ready screen's FTP and the warm-up's watts change after the
  refresh button or the next opening of the Ready screen, and a ride built after it is at that FTP.
- That a change of FTP on the Settings screen shows on the service (`get_profile`) within a second or two, and that one made in
  airplane mode shows "Not sent yet" and goes out when the network is back and the Ready screen is opened.
- Whether "From service" and "Not sent yet" read well at arm's length, and whether the row should say more about why a number the
  rider did not set is what it is.

## Consequences

- The ride app has two more network calls, a `GET` and a `PATCH` to the address the owner typed, with the token the upload already
  uses. With no address or token it makes none, and with no network everything works with the last known profile.
- The profile is data the owner chose to keep on the service, in a private file of the app (not backed up, `allowBackup` is off).
- A change of the profile's wire format is a change to `contracts/` first; the tablet reads and writes as strictly as the schema.
- The effective settings are a second view of the settings beside the stored ones; anything that needs what the rider rides with reads
  `effectiveSettings`, anything that edits what the rider set reads `settings`.
