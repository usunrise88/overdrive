# ADR 0031: The launch animation in the apps

Status: accepted (2026-11-19). Playback has not been checked on a device yet (see "What cannot be checked here").

## Context

The owner asked for a short film that opens each app (`design/brand.md`, "The launch animation"; ADR 0030 for the mark and the
accents). The films exist as raw resources: a 1600 × 1000 landscape one in the ride app and a 720 × 1600 portrait one in the
companion, about 4.9 s each, VP8 in WebM, with no sound, on a near-black background (`#05070A`) that ends in the app's coloured,
noisy glow with the name. The apps have minSdk 31, the ride app has no dependency on Google Play Services (CLAUDE.md), and the
tablet is a ride computer: it must not wait for a film, and a rider with the animations switched off wants none.

## Decision

- **Android's own `VideoView`, inside `AndroidView`, and no library.** Media3 / ExoPlayer would be the usual choice, but it is a
  large dependency (with its own release cadence and its own build weight) for one muted 5 s clip that `MediaPlayer` plays
  natively with the platform's VP8 decoder. `VideoView` is the smallest thing that works and costs no new Gradle dependency.
  If a real device shows a problem that `VideoView` cannot answer, Media3 can replace the player behind the same `IntroVideo`
  signature.
- **`IntroVideo(rawRes, onEnded, onFailed)`** in `ui-kit` plays the raw resource once. It wraps the `VideoView` in a `FrameLayout` so
  that the video is centred at the largest size of its shape that fits (left to fill the host directly, a `SurfaceView` stretches to
  the box); the box behind is `#05070A`, the film's own colour, so the bars of another aspect ratio cannot be seen. It is muted
  (`setVolume(0, 0)`), and it asks for **no audio focus** (`setAudioFocusRequest(AUDIOFOCUS_NONE)`): the rider's own music or
  video, which is playing on the tablet while the app starts, is not paused by a silent film. The error listener returns true so that
  the platform's "Can't play this video" dialog never appears. A transparent surface is hidden behind a dark cover until the first
  frame is rendered (`MEDIA_INFO_VIDEO_RENDERING_START`). `onEnded` is called on completion and the last frame stays on screen;
  `onFailed` on a player error or when **1.5 s** pass with neither a rendered frame nor a position above 0. It pauses on `ON_STOP`
  and carries on at `ON_START` (a film that had ended goes back to its last frame and stays still), and releases the player when it
  leaves the composition.
- **`IntroHost`** layers it over an app. The app's root is composed from the first frame, under the film, so the app's own
  start-up is not delayed. The film's layer covers the whole window (the roots are passed their insets and background as a
  modifier, so the host sits outside them), is opaque, takes every touch, and the root under it cannot take focus (a
  `focusProperties { canFocus = false }` around the root only, since a focus property reaches the whole subtree). That the film is
  over is saved with `rememberSaveable`.
  - **Ride app:** when the film ends, or the rider taps during it (the film then plays on), a primary `OdButton` "Continue" (64 dp, the
    ride height) shows at the bottom end, inside `safeDrawing`, with a 2 dp focus ring at a 2 dp offset (design/system.md §7.1; the
    button component does not draw it yet, so the host does) and an initial focus request. Pressing it fades the layer out over
    300 ms. The closing frame stays behind the button. The Click remote is not wired to it.
  - **Companion:** no button. The end of the film, or a tap, fades the layer out over 400 ms.
- **The fade is drawn by Compose, in two halves.** The video draws on a surface of its own, below the window, and a `SurfaceView`
  does not take alpha from a Compose layer reliably across devices. So a cover of `#05070A` over the film grows to opaque in the
  first half (the glow dips into the dark), the player is removed when it is opaque, and in the second half the opaque layer's
  alpha goes to 0 and shows the app. The app's background (`#090C11`) is close to the film's, so it reads as one fade. The
  button goes with the first half.
- **Skip rules** (`shouldPlayIntro(coldStart, animatorScale)`, pure and tested in `IntroLogicTest`): the film plays only on a cold
  start (`savedInstanceState == null` in `MainActivity`, so rotation and a restored task do not replay it) and only when
  `Settings.Global.ANIMATOR_DURATION_SCALE` is not 0, the "remove animations" setting that already switches off the short UI
  animations (design/system.md §9). The root composables (`RideApp`, `CompanionRoot`) take `playIntro` with default `false`, so
  every existing test is unaffected; only `MainActivity` passes the answer.
- **Failure is quiet.** A film that fails removes the layer at once and the app is simply there; nothing is logged for the rider.
- The pure parts live in `IntroLogic.kt` and are unit tested on the JVM: the skip rule, the stage machine
  (`Playing`, `Waiting`, `Leaving`, `Finished`; with and without a button), the fade's two halves, the "did not start" test and the
  plan for a film that was in the background. The `VideoView` and the layering are not tested in code.

## What cannot be checked here

There was no device. These are written from the platform's documented behaviour and are for the owner's first install to confirm:

- That the films **play** on the real tablet and phone (a VP8 WebM from `res/raw` through `VideoView`; the release build keeps the
  file stored uncompressed, which `MediaPlayer` needs, and R8's resource shrinking keeps it because the code references it).
- **HyperOS** (Xiaomi) behaviour: the surface and the hole it punches in the window, the first-frame timing (the 1.5 s limit may be
  too short on a slow start, in which case the film is skipped and the app is simply there), and that audio is not interrupted.
- That the two-half fade looks like one fade and shows no flash where the player is removed.
- That a film that was sent to the background mid-way, or at its end, comes back on the right frame (the seek lands on the keyframe
  before the position, which may be a fraction of a second early).
- **The Continue button's look**: size, distance from the edge, the contrast of the near-white button on the film's glow, the focus
  ring, and Enter from a keyboard (touch mode first has to be left by a key press, which Android consumes).
- Back during the film leaves the app, as Back does on the ride app's home screen.

## Consequences

- Each APK grows by its film: about 1.5 MB.
- "Cold start" is the activity's: leaving the app with Back and opening it again while its process is still alive shows the film
  again. If that turns out to be too often, the test can become "first activity of this process" without touching the rest.
- A third film (the Telegram bot, the service) needs only a new raw resource and an `IntroHost` call.
- Replacing the player (Media3) is a change inside `IntroVideo`.
