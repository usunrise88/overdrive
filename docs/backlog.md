# Backlog

Things the owner wants later, not scheduled in any phase. Newest first. Move an item into `docs/roadmap.md` when it gets a
stage; delete it when it ships.

## Telegram bot

1. **Workout visuals and links to intervals.icu.** The bot's `/today`, `/week` and the reminders show a text summary only.
   Add a picture of the workout (the step profile as intervals.icu draws it: power over time, filled by zone, with the zone
   share and the target in watts) and a link to the event in intervals.icu. The picture is drawn from the converted native
   workout by code (an image rendered on the server, sent with `sendPhoto`), not taken from intervals.icu. Needs an ADR: how
   the image is rendered (SVG to PNG, which library fits the image), and which URL intervals.icu gives to an event
   (`https://intervals.icu/calendar` with the event id; to check against a real account).
   Reported 2026-11-18, after the first `/today`.
2. **Line breaks of the workout description are lost in Telegram.** The description that the owner or an AI client wrote
   (several paragraphs, a list of steps one per line) arrives in the bot as one run-on line, and the step list is joined
   with commas. The description should keep its paragraphs and put each step on its own line. Look at how
   `server/src/telegram/` builds the message from `workoutSummary` (the steps are summarised by code, the free text of the event
   is not carried at all) and decide: carry the event's own description (with its line breaks, escaped for the chosen parse mode)
   under the summary, and list the steps one per line. Reported 2026-11-18 with a screenshot of `/today` beside the same
   workout in intervals.icu.
