# Brand: the mark, the accents, the icons

Decided with the owner on 2026-11-19 (ADR 0030). The look follows [Raycast](https://www.raycast.com/): near-black, a luminous
white shape, soft coloured light, and **noisy** gradients, never smooth ones. This is the only place in the project where
colour other than the power zones is allowed (`system.md` §2 stays true inside the apps).

## The mark

A two-turn spiral: a ring wound twice, the outer turn leaving as a sharp tail to the upper right (a wheel, a flywheel, speed).
It is **always white**; the accent lives in the light around it. The geometry is code (`tools/brand/mark.mjs`), so every size
and every accent has the same shape. `logo/overdrive-mark-white.svg` and `-black.svg` are the vector forms for a dark and a
light background.

## The accents

Each app has its own pair of colours; the mark is the same everywhere.

| For | Name | Colours | Notes |
|---|---|---|---|
| Ride app (tablet) | gold | `#FFB020`, `#FF5A1F` | gain 1.4 |
| Companion (phone) | lime | `#B6FF00`, `#00FFA3` | gain 1.8 |
| Telegram bot and the service | lime and gold | `#B6FF00`, `#FFB020` | gain 1.6 |

Gain is how strongly the colour glows. The noise is coarse on purpose (about 3 px at 1024): fine grain turns to moiré when an
icon is scaled down. Never draw a small icon by scaling a large one in one step: the tool halves repeatedly.

## What is built, and where

`node design/tools/brand/build-brand.mjs [icons] [android] [logos] [kotlin]` (needs Chromium: `CHROMIUM_PATH`, or Playwright's).

| Output | Where | Use |
|---|---|---|
| `icon-{tablet,phone,server}-512.png` | `design/logo/` | Store listings and any place that wants an icon; the 1024 px version is rebuilt by the tool, not kept (2 MB each) |
| `bot-avatar.png` | `design/logo/` | The Telegram bot's picture (640 px; the mark is smaller because Telegram crops to a circle) |
| `overdrive-logo-{white,black}.png` | `design/logo/` | The mark with the name in Geist SemiBold, transparent background |
| `overdrive-mark-{white,black}.svg` | `design/logo/` | The mark alone, vector |
| `cover.png` | `design/logo/` | The README and the GitHub social preview (1280 × 640) |
| `OverdriveMarkPath.kt` | `android/ui-kit/.../uikit/` | The mark's outline (generated, 360 points) for the `OverdriveMark` composable: flat white, no glow, no noise. It is shown before the name on the ride app's Ready screen and above the first run's title in the companion, never instead of the name |
| `ic_launcher_{background,foreground,monochrome}.png` | `android/app-ride` and `app-companion`, `res/mipmap-{xhdpi,xxhdpi,xxxhdpi}/` | The adaptive launcher icon; `mipmap-anydpi/ic_launcher.xml` ties the layers together, and the monochrome layer is the Android 13 themed icon. minSdk is 31, so there is no legacy icon |

The foreground is the mark at 52% of the 108 dp canvas, which keeps the ring inside the 66 dp safe circle; only the hairline
tip of the tail may be cut by a round mask.

## The launch animation

A short film that opens each app on a cold start (about 4.9 s, 30 fps, WebM/VP8, no sound): the camera looks straight down, a white
road bike rides in from the bottom edge to the centre, the camera flies round it to a side view and backs away (the bike centred,
handlebars to the right), and from below the app's colour rises as a noisy gradient with the mark and the name. The bike is a 3D
model made with Meshy from a picture of a white road bike; it is rendered white, with no tint (owner's decision), and only the
light behind the name carries the app's colour.

| App | Video | Size |
|---|---|---|
| Ride app (tablet) | `android/app-ride/src/main/res/raw/overdrive_intro.webm`, 1600 × 1000, gold | about 1.5 MB |
| Companion (phone) | `android/app-companion/src/main/res/raw/overdrive_intro.webm`, 720 × 1600, lime, "Companion" under the name | about 1.5 MB |

`design/tools/intro/build-intro.mjs` renders both from `intro.html` (see the header of the script for what it needs: three r160,
the bike's GLB, Chromium and the ffmpeg that Playwright installs). The 44 MB GLB is not in the repository.

**How the apps play it** (ADR 0031; `IntroVideo` and `IntroHost` in `android/ui-kit`). Android's own `VideoView`: no library and no
Play Services. The film plays once, muted and without taking audio focus, filling the screen with its aspect ratio kept on the
film's own background (`#05070A`), so a screen of another shape shows no bars. It plays on a **cold start** only (the activity has
no saved state, so a rotation does not replay it) and **not** when the system's animations are off (animator duration scale 0). The
app's own start-up is not delayed: the app is composed from the first frame under the film, and the film's layer is opaque and takes
every touch, so nothing under it can be used by mistake.

- **Ride app (tablet):** when the film ends, or when the rider taps the screen during it, a primary **Continue** button (64 dp, the
  ride height of design/system.md §7.1) appears at the bottom end of the screen inside the system bars, with initial focus so that
  Enter on a keyboard presses it. The film's last frame stays behind it. Pressing it fades the layer out in 300 ms.
- **Companion (phone):** no button. The end of the film, or a tap, fades the layer out in 400 ms.
- **The fade** is drawn by Compose in two halves, because the video's own surface cannot be faded the same way on every device:
  the last frame dips into `#05070A`, then that colour fades away to show the app.
- A film that cannot be played (an error, or no frame within 1.5 s) removes the layer at once; the app is simply there.
- A film that is in the background when the activity stops pauses and carries on when it comes back.
