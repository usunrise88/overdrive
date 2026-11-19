# ADR 0030: The brand mark, its accents and how the icons are built

Status: accepted (owner's decision, 2026-11-19).

## Context

The apps had no launcher icon, and the first logo (a gauge in the seven power-zone colours) was rejected by the owner. The owner
asked for a stylish family of icons, the same shape with different accents, in the style of raycast.com, using Meshy's image
generation to explore.

## Decision

- **One mark**, a two-turn spiral whose outer turn leaves as a tail (`design/brand.md`). It was chosen from eight concepts
  generated with Meshy (`nano-banana-2`, text to image, then image to image for variants), then **redrawn as vector geometry**
  (`design/tools/brand/mark.mjs`): the generated images vary from picture to picture and are raster, so they are not shipped.
  The mark is always white; the glow around it carries the accent.
- **Accents** (owner's choice): gold for the tablet, lime for the phone, lime and gold for the Telegram bot and the service.
  They live in icons, logos and the cover only; `design/system.md` §2 still forbids any other hue inside the apps.
- **Noisy gradients**, coarse enough (about 3 px at 1024) not to alias when the icon is shrunk. Small sizes are made by repeated
  halving, never in one step.
- **Built by code**: `design/tools/brand/build-brand.mjs` renders everything with headless Chromium: the adaptive launcher
  layers of both apps (background, foreground at 52%, monochrome for the themed icon; minSdk 31, so no legacy icon), the 512 px
  icons, the bot avatar, the logo lockups in Geist SemiBold and the cover. The 1024 px icons are not kept (2 MB each).
- **In the apps** the mark is a flat white `OverdriveMark` composable in `ui-kit` (a generated path, `OverdriveMarkPath.kt`), shown
  before the name on the ride app's Ready screen and above the first run's title in the companion. The glow and the noise stay in the
  launcher icon: `design/system.md` §2 allows no other hue and no decoration inside a screen.
- The Meshy API key was given in a chat for this work and was used only through an environment variable; nothing of it is in the
  repository. The owner revokes it when the work is done.

## Consequences

- A change of accent or mark is one edit and one run of the tool.
- The repository gains about 5 MB of PNG (grain does not compress). Rebuilding needs a Chromium.
- The old zone-coloured gauge logos are removed.
