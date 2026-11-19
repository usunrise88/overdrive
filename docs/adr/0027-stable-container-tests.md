# ADR 0027: Stable container tests, and a ride that never waits on the profile

Status: accepted (2026-11-18, between stages P3-7 and the companion's second half).

## Context

The Android job of CI failed in about two runs of five **on the same commit**, each time in a different container-style test of
`app-ride` or `app-companion` (`AppContainerPlanTest`, `AppContainerProfileTest`, `AppContainerSyncTest`, `CompanionContainerTest`):
a wait for a condition that ran out after 30 or 60 s, or a count of requests that was one too high. On a 32-core machine they never
failed. The tests run on real threads, real files and real loopback sockets, in one JVM per module with ~700 other tests' threads
alive, on a 4-vCPU runner that also runs the Gradle and Kotlin daemons and the other module's tests; a thread there can wait tens of
milliseconds for a core, and the order in which two coroutines run is whatever it happens to be.

Reproduced on one machine by running **the whole** `testDebugUnitTest` of the two modules pinned to two cores with seven busy loops
on the same cores (a single class, or an unloaded machine, does not do it): the companion's rejected-token test failed in the first
run (`expected:<1> but was:<3>`).

## Root causes

1. **`RideHost` took the settings in at two places** (a product bug, found by reading the failure of `AppContainerProfileTest`, "the
   simulated devices are in" never reached, with the profile ready, the tablet's own FTP in force and `ride=IDLE`). The first session
   was built after `settings.first()` in one coroutine, and a second coroutine followed every value into `currentSettings`.
   `first()` returns only after its collection has wound down, which takes several turns of the host's one thread; a newer value
   (the rider's change a few milliseconds after the start) could be stored in between, and then the first coroutine stored the
   **older** value over it and built the ride from that. No later value came to correct it, so the ride stayed on the old devices or
   FTP until the next change. `RideHostTest.aSettingThatArrivesRightAfterTheFirstIsNeverOverwrittenByTheFirstWhenItIsLate` reproduces
   it in virtual time with a flow that is slow to wind down: on the old host the ride stays at the first FTP (250) when the settings
   say 300. It is a narrow window on a tablet (the cache read and the first settings land within milliseconds of each other and
   nothing changes them that soon), wide on a starved CI runner.
2. **The first ride waited for the profile with no bound.** `effectiveSettings` is a cold flow that waits until `ProfileState.ready`
   (ADR 0021: the cache read and the settings seen). That is the right thing for a few milliseconds and the wrong thing for ever:
   if the cache read or the settings never said so, no ride was built and the rider could not start one.
3. **Tests that counted requests the interleaving decides** (the product rule that the rider's own refresh waits and asks once more
   made the counts depend on timing, and some already did):
   - `CompanionContainerTest` "a rejected token is said, asked once": it sampled the request count when the *figures* said the token
     was refused, while the workout's and the profile's start requests were still to be sent (`expected:<1> but was:<3>`, `<2>` and
     `<3>` in CI).
   - `AppContainerSyncTest` "a rejected token is said once": the ride's own kick of the upload (the coordinator is told on a
     coroutine of its own, a moment after the ride is saved) could arrive after the new token and ask by itself, so the later
     settings kick made a request more than the test counted.
   - Plan tests asserted `single()` and absolute counts (a start request that a settings change abandons is made again), and several
     tests pressed the rider's refresh in a loop "until it had asked", which was needed when it could be dropped and is not now.
   - `CompanionContainerTest` Connect: it asserted one profile request after Connect returned while the container's own first
     refresh, 50 ms after the settings were stored, could already have asked once more.
   - The pull-down test counted the launched "screen opened" call, which has no point at which it is over.
4. **`RidesRepository.loadMore` dropped a call made right after a page was shown** (a product race, found in the loops:
   `CompanionContainerTest` "the rides list pages by cursor" timed out waiting for the last page, in 3 runs of 8 on the old code).
   It guarded the fetch with a `Mutex` that it released in `finally`, **after** it had published the page; the Rides screen asks for
   the next page the moment it shows one (it is near the end of the list), and a call that landed in that gap found the lock held
   and was dropped, and nothing asked again until the rider scrolled once more. On a starved runner the fetching thread can be
   descheduled in the gap for tens of milliseconds. A fetch that was cancelled also left `loadingMore` true. And the refresh of the
   newest page dropped the older pages **after** it had published the new one, with `more.value = More()`: a page asked for in
   that gap (the screen does, for a short list) was wiped with them, and the test timed out waiting for the second page (once in
   two runs of the first fix).
5. **A restart test did not wait for the disk**: the companion's profile cache is written a moment after the answer is published, on
   a coroutine that stopping the container cancels (the cache files of `RemoteResource` are written before they are published, ADR
   0024; the profile's is not), and the test read the folder right after.

## Decision

1. `RideHost` takes the settings in through **one collector**: it stores each value in order and builds the first session in the same
   step; `observeRideInputs` follows the stored value, not a second collection. Nothing is built before the first settings arrive
   (a `select` or `reset` before that only records the choice, which is what the first session is built for). A test that fails
   inside `runTest` with a ride running no longer hangs: the new host tests close the host in `finally`.
2. `RidesRepository.loadMore` takes "a page is being fetched" from its **state** (`More.loading`), claimed with one
   `compareAndSet` and given back in the same update that publishes the page (or, for a fetch that was cancelled, that restores the
   pages as they were). There is no lock to be held a moment longer than the state, so a call made once a page is shown goes out.
   The refresh of the newest page drops only the pages that hang from the page it replaced (`it.after == before`), so a page asked
   for after the new one was shown is kept. `CompanionRepositoriesTest` covers the second call while one is on its way (not fetched
   twice), the call once the page is shown, the page asked for inside the refresh that shows the newest one (red on the old
   bookkeeping: 3 items for 6) and the cancelled fetch (the old code left `loadingMore` true).
3. **The first ride waits for the profile for [`PROFILE_WAIT_MILLIS`] = 3 s at most.** `effectiveSettingsOf` (`ride.settings`) is
   the flow the container used to build inline: the first value waits for `ready` and, past the bound, is the tablet's own settings;
   every later change of the profile is followed, so a ride that has not started is rebuilt by the existing debounced rule when the
   profile arrives, and one that has started keeps what it began with (ADR 0021). The bound is not a timer that fires again.
   Three virtual-time tests cover waiting, giving up and following, and the known-at-once case.
4. **Tests assert the contract, not the interleaving.** A count is taken **after** everything that can add to it has had its answer
   (every start request of the companion, the ride's own upload kick), or **relative** to a value sampled at such a point, or as a
   call that returns when it has decided (`runBlocking { repository.refresh(SCREEN_OPENED) }`, then the count). A test that must
   count a single request makes the container's own refresh an hour away (`settleMillis`), instead of racing it. The rider's refresh
   is pressed once. Where a test stops the container or reads a file after seeing a value in memory, it waits for the **disk** first.
5. Nothing is lengthened: no timeout, no sleep was added or increased. (`Thread.sleep` before an assertion that something does *not*
   happen stays; it can only miss a bug, never fail on a slow machine.)

## Not done

- `ProfileRepository` still persists its cache after publishing the state (`RemoteResource` writes first). Only stopping a container
  in a test can cancel that write, and the tests wait for it; changing the order would need the same care as ADR 0024 took for the
  other repositories and buys nothing on a tablet, where nothing closes the container.
- `effectiveSettings` (the state flow the Ready screen and the Settings rows read) still holds `AppSettings()` until the first value
  of the flow, as before; nothing is built from it (the host takes the flow itself).

## Consequences

- The first ride is held back for the profile for 3 s at most, and only if the cache or the settings do not answer at all; in every
  other case nothing changes.
- A new container test follows the rules in 4: a wait is for a condition, a count is taken at a quiet point, and a test that closes
  the container waits for the disk.

## How it was checked

- **The way to reproduce** (a single class, or an unloaded machine, does not): the whole `:app-ride:testDebugUnitTest` and
  `:app-companion:testDebugUnitTest` in one Gradle run, pinned to two cores (`taskset -c 20-21`) with seven `yes > /dev/null` loops
  on the same cores, `--no-daemon --rerun`, in a private copy of the tree. About six minutes a run, against one minute unloaded.
- **Before** (the code of 31400f1): 4 of 8 runs failed: the companion's rejected-token test in three runs (`expected:<1>` or
  `<2>` but was `<3>`) and the rides list "the last page" in two (one run had both). The ride app's start-up hang did not come up
  in these eight runs (see the next item).
- **The start-up hang, on the symptom.** On a copy of the old `RideHost` with only `first()` made slow to wind down (300 ms), 147 of
  150 starts that change the settings a few milliseconds after the container is built end in the state of the CI timeout: the
  profile ready, the settings in force saying Simulated, **a second ride built from the stale settings, `IDLE`, the devices rows
  "Not paired", `simulated=false`**. Without the slowdown, 550 starts under the same load did not hit it (the window is a few turns
  of one thread), which is why it came up in one CI run in a few. The virtual-time host test is red on the old host and green on the
  new one.
- **After:** the first loaded runs of the fixes each found one more race, which was fixed (the second page of the rides list wiped
  by the refresh that showed the first, once in 8 runs; the upload secrecy test reading `last-upload` before it was written, once
  in 36). Then **40 runs of the final code, 10 consecutive in each of 4 private copies on 4 core pairs, all green** (about 6
  minutes a run, 4 at a time).
- **CI:** five parallel runs of the final commit on throwaway branches, all four jobs green. One earlier run failed in the
  TypeScript job, not in the Android one: `telegram-notifications.test.ts` "stops
  asking Telegram when the token is refused" counted `sendMessage` once and found two (a second tick of the notifier is kicked by an
  event while the first is stopping at the refusal). It is a race of the same kind in the server's test, outside this ADR's scope.
