# Design

Design comes before UI code (spec §7.4). This directory holds the deliverables of the design step
at the start of Phase 1; nothing here is code that ships.

## Deliverables

1. **Design system** — tokens, type, components and charts, to be implemented in `android/ui-kit`.
2. **Mock-ups** — the overlay (compact and detailed), the ride screen, and the companion's main
   screens (today, fitness, power, wellness, rides).

## Constraints from the spec

- Dark theme first: the ride app is used in a dim room next to video.
- Numbers use tabular figures and never shift width as they change.
- One accent system: power-zone colours. Everything else is neutral.
- Motion is short and purposeful, and absent from the overlay at rest.
- The overlay must stay readable over video: solid or blurred backing, power-zone colour as the
  only accent.
- The ride app and the companion share one design system and the same visual quality bar.
