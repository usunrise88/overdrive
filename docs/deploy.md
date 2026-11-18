# Deploying the server

How the owner runs the web service (`server/`, spec §7.2) on the owner's host, at `https://overdrive.llmto.day`.
The host runs Docker and Caddy and is shared with other projects, so the ports come from `.env`. The design is in
ADR 0014 (deployment, storage, authentication). Commands run on the host, in the repository root unless they say otherwise.

What runs where:

- **Caddy** (on the host, not in Docker) terminates TLS and proxies `overdrive.llmto.day` to `127.0.0.1:27180`.
- **`server`** (Docker) listens on `127.0.0.1:27180` and talks to the database on the Docker network.
- **`db`** (Docker, PostgreSQL 18) listens on `127.0.0.1:27181` and keeps its data in the `db-data` volume.

## Prerequisites

- Docker with Compose v2 (`docker compose version`).
- Host Caddy, with the main Caddyfile importing `/etc/caddy/sites.d/*` and defining a `common` snippet. The site file
  imports `common`.
- DNS for `overdrive.llmto.day` pointing at the host.
- Ports 27180 and 27181 free on the host's loopback.
- A clone of this repository on the host, and `openssl`.

## 1. Caddy

```bash
sudo cp deploy/overdrive.llmto.day.caddy /etc/caddy/sites.d/
```

Validate **as the `caddy` user**, not as root:

```bash
sudo -u caddy caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

Do not validate as root. Root creates `/var/log/caddy/overdrive.log` owned by root, and the Caddy service cannot open
it afterwards. If that has already happened, give the file back:

```bash
sudo chown caddy:caddy /var/log/caddy/overdrive.log
```

The port in the site file (27180) is `SERVER_PORT` in `.env`. If you change one, change the other.

## 2. Configuration file

```bash
cp .env.example .env
chmod 600 .env
```

Edit `.env` in an editor. Set:

- `POSTGRES_PASSWORD`: URL-safe, because it goes into the database URL. Make one with `openssl rand -hex 24`.
- `SERVER_PORT=27180` and `POSTGRES_PORT=27181` (the host's ports; the defaults are taken there).
- `DEVICE_TOKENS`: see the next step.

`.env` is git-ignored. Never commit it, and never paste its contents into a chat, an issue or a commit.

## 3. Device token

Each device that uploads rides needs a token. This one is for the tablet, named `tablet`. Make it and write it into
`.env` without printing it:

```bash
printf 'DEVICE_TOKENS=tablet:%s\n' "$(openssl rand -base64 36)" >> .env
```

If `.env` already has a `DEVICE_TOKENS` line, edit that line instead of adding a second one. The name (`tablet`) is what
the server writes to its logs and to `rides.uploaded_by`; the token is the secret.

To give the tablet its token, read the value out of `.env` in your own terminal and type it into the tablet's
Settings, Sync (the token alone, not `tablet:`):

```bash
sed -n 's/^DEVICE_TOKENS=tablet://p' .env
```

Run that only where you can see the screen. Do not paste the output into a chat, an issue or a commit.

## 4. Start

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail 30 server
```

The server applies the database migrations when it starts. The log should say `database migrated` (the first time) and
then that it is listening. An invalid setting stops the start with `Invalid configuration:` and the name of the variable;
the value is never printed.

## 5. Checks

The health endpoint is open:

```bash
curl -fsS https://overdrive.llmto.day/healthz
# {"status":"ok"}
```

An authenticated request, with the token read from `.env` into a shell variable (not typed inline):

```bash
TOKEN=$(sed -n 's/^DEVICE_TOKENS=tablet://p' .env)
curl -sS -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer ${TOKEN}" https://overdrive.llmto.day/v1/rides/check
unset TOKEN
# 404: the token was accepted (the path is not an endpoint, and the answer says so)
curl -sS -o /dev/null -w '%{http_code}\n' https://overdrive.llmto.day/v1/rides/check
# 401: no token, as it should be
```

A `401` with a token means the token in the server's `.env` and the one the tablet holds differ, or the server was not
restarted after `.env` changed (see "Rotating a token").

## 6. Updating

```bash
git pull --ff-only
docker compose up -d --build
docker compose logs --tail 30 server
```

Take a backup first (section 8). Migrations run at start and are not reversed: a rollback to an older commit does not
undo a newer schema, so restore from a backup if the database itself has to go back.

## 7. Reading logs

- The server: `docker compose logs -f server`. Lines carry the device's name, never its token; the `authorization` header
  is redacted.
- The database: `docker compose logs --since 1h db`.
- Caddy's access log for the site: `sudo tail -f /var/log/caddy/overdrive.log` (JSON; 20 MiB per file, five kept).

## 8. Backups

The rides live in the `db-data` volume. Dump the database to a file outside the repository (the dump is personal data:
keep it in a private place and do not commit it):

```bash
docker compose exec db pg_dump -U overdrive overdrive | gzip > ~/backups/overdrive-$(date +%F).sql.gz
chmod 600 ~/backups/overdrive-*.sql.gz
```

Make the backup directory first (`mkdir -p ~/backups`). Keep a copy off the host as well.

## 9. Restoring

A restore goes into an empty database, because the dump creates the tables without checking for them. Stop the server,
then restore into a database that has no tables yet:

```bash
docker compose stop server
docker compose down
docker volume ls                  # find the name ending in db-data, for example overdrive_db-data
docker volume rm <that name>      # destroys the current database: only when you mean to replace it
docker compose up -d db
docker compose exec db pg_isready -U overdrive        # wait until it says "accepting connections"
gunzip -c ~/backups/overdrive-2026-11-18.sql.gz | docker compose exec -T db psql -U overdrive -d overdrive -v ON_ERROR_STOP=1
docker compose up -d server
```

The server finds every migration already recorded in `schema_migrations` and starts. To check a backup without touching
the live database, restore it into a scratch copy of this project first.

## 10. Rotating a token

To change a device's token, or to revoke a device:

1. Edit its line in `.env`: put a new token for the same name (`openssl rand -base64 36`), or delete the line to revoke.
2. Apply it: `docker compose up -d server`. Use `up -d`, not `restart`: `restart` does not reread `.env`.
3. For a new token, type it into the tablet's Settings, Sync.

The old token stops working at once. Tokens are at least 32 characters; startup fails otherwise.

## 11. Environment variables

The server reads these variables (`server/src/config.ts`), and compose reads the ones in the first table from `.env`.
Values in `.env` are never in the repository.

### Set in `.env` (read by compose)

| Variable | Default | Meaning |
|---|---|---|
| `POSTGRES_PASSWORD` | required | Password of the `overdrive` database user. URL-safe (`openssl rand -hex 24`). Compose builds `DATABASE_URL` from it |
| `DEVICE_TOKENS` | empty | `name:token,name:token`: the devices allowed to call `/v1`. Empty means every `/v1` request is `401`. Tokens are at least 32 characters |
| `INTERVALS_API_KEY` | unset | Personal API key of intervals.icu (Settings, Developer Settings). Unset or empty: **the integration is off** (rides are queued, no plan is read, the MCP plan tools say so). ADR 0016 |
| `INTERVALS_ATHLETE_ID` | `0` | `i12345`; `0` is the athlete the key belongs to |
| `INTERVALS_BASE_URL` | `https://intervals.icu` | Only to go through a proxy |
| `PLAN_REFRESH_MINUTES` | `15` | 1 to 1440: how often the plan (today and 14 days) is read |
| `TIMEZONE` | `UTC` | The rider's IANA time zone, for example `Europe/Paris`: which day is "today" for the plan, which day a ride, a night of sleep, a reading or a watch workout belongs to, the default ranges of the MCP tools |
| `WELLNESS_SOURCE_ORDER` | `healthconnect,intervals` | Which source a daily wellness figure comes from: the first in the list that has one. `…;restingHr=intervals,healthconnect` gives one metric an order of its own; a source left out is not used for that metric. ADR 0023 |
| `WELLNESS_PULL_HOURS` | `6` | 1 to 168: how often intervals.icu's wellness is read (with an intervals.icu key) |
| `WELLNESS_BASELINE_DAYS` | `42` | 28 to 60: the window of a personal baseline |
| `WELLNESS_NIGHTS_NEEDED` | `14` | 7 to 28: nights before any recovery verdict (the companion says "Learning your usual range" until then) |
| `INSIGHTS_ENABLED` | `true` | `false`: no written note or alert is written or sent (the ones already written are still served). ADR 0025 |
| `LLM_BASE_URL` | unset | The address of an OpenAI-compatible chat-completions server, for example `https://api.example.com/v1` (https; http only for localhost). **With `LLM_MODEL`, turns the language model on**: the facts of your training and recovery are sent there and to no other address. Unset: notes are written from templates, nothing leaves the server. Section 17 |
| `LLM_API_KEY` | unset | The key of that endpoint, if it needs one. **Secret: only in `.env`**, never in a chat, an issue or a commit |
| `LLM_MODEL` | unset | The model's name at that endpoint |
| `LLM_TIMEOUT_SECONDS` | `60` | 5 to 600: how long one request to the endpoint may take (a failed or slow one is retried twice, then the note is written from the template) |
| `INSIGHTS_LANGUAGE` | `en` | A language name or code put in the prompt (`en`, `French`, `pt-BR`). The fallback template is always English |
| `INSIGHT_MORNING_TIME` | `07:00` | When the morning note is written, in `TIMEZONE` (`HH:MM` or `off`); the morning alerts are checked then too |
| `INSIGHT_DENY_WORDS` | empty | Words a written note may never contain, added to the built-in list (comma-separated) |
| `ALERT_STREAK_DAYS` | `3` | 2 to 14: mornings in a row a metric is outside its usual range before an alert |
| `ALERT_MISSED_SESSIONS` | `2` | 1 to 7: planned sessions missed in the last 7 days before a plan-drift alert |
| `ALERT_LOAD_ABOVE_PERCENT`, `ALERT_LOAD_BELOW_PERCENT` | `30`, `40` | Plan drift: the load of the last 7 days this many percent above (5 to 300) or below (5 to 95) the load planned for them |
| `PUBLIC_BASE_URL` | unset | `https://overdrive.llmto.day`: the service's public address (https, no path). **With `OWNER_PASSPHRASE_HASH`, turns the MCP server on.** ADR 0018 |
| `OWNER_PASSPHRASE_HASH` | unset | The hash of the owner's passphrase, `scrypt:…` (section 12). **With `PUBLIC_BASE_URL`, turns the MCP server on.** The plain passphrase is never configured |
| `MCP_STATIC_TOKENS` | empty | `name:token,name:token`: fixed bearer tokens for an MCP client that cannot do OAuth; at least 32 characters each; open `/mcp` only |
| `STRAVA_CLIENT_ID` | unset | The number shown as Client ID on strava.com/settings/api. **With the next two, turns the Strava upload on.** Write-only; ADR 0020 |
| `STRAVA_CLIENT_SECRET` | unset | The Client Secret of that application. Secret: only in `.env` |
| `STRAVA_REFRESH_TOKEN` | unset | Printed by `strava-auth` (section 15). Only **seeds** the first run: Strava rotates the token and the server keeps the newest in its database. Put a new value here after running `strava-auth` again and it replaces the stored one |
| `STRAVA_BACKFILL` | `false` | `true` also posts the rides stored before Strava was turned on, at the next start. Leave it off unless you want the history on Strava |
| `STRAVA_BASE_URL` | `https://www.strava.com` | Only for tests and benches |
| `SERVER_PORT` | `8080` | Host loopback port for the server. Here `27180`; the Caddy site file must match |
| `POSTGRES_PORT` | `5432` | Host loopback port for the database. Here `27181` |
| `LOG_LEVEL` | `info` | `fatal`, `error`, `warn`, `info`, `debug`, `trace` or `silent` |

### Set by compose inside the server container

| Variable | Value | Meaning |
|---|---|---|
| `DATABASE_URL` | built from `POSTGRES_PASSWORD` | `postgres://overdrive:…@db:5432/overdrive` |
| `DEVICE_TOKENS` | from `.env` | As above |
| `INTERVALS_*`, `PLAN_REFRESH_MINUTES`, `TIMEZONE` | from `.env` | As above; empty means the default |
| `WELLNESS_*` | from `.env` | As above; empty means the default (wellness and recovery need no key: only the intervals.icu pull and the forwarding of the watch's workouts do) |
| `INSIGHTS_ENABLED`, `LLM_*`, `INSIGHTS_LANGUAGE`, `INSIGHT_*`, `ALERT_*` | from `.env` | As above; empty means the default or unset (written notes need no key: without `LLM_BASE_URL` and `LLM_MODEL` they are written from templates) |
| `PUBLIC_BASE_URL`, `OWNER_PASSPHRASE_HASH`, `MCP_STATIC_TOKENS` | from `.env` | As above; empty means unset |
| `STRAVA_*` | from `.env` | As above; empty means unset (the upload is off unless the first three are all set) |
| `LOG_LEVEL` | from `.env` | As above |

### Set by the image (`server/Dockerfile`)

| Variable | Value | Meaning |
|---|---|---|
| `NODE_ENV` | `production` | |
| `HOST` | `0.0.0.0` | Inside the container; outside Docker the default is `127.0.0.1` |
| `PORT` | `8080` | Inside the container; compose publishes it as `SERVER_PORT` on loopback |

### Integrations: optional, each off while its variables are empty

All set in `.env` (compose passes them as `${VAR:-}`, so an empty value means the default or off). Never paste their values into
a chat, an issue or a commit. The invalid ones stop the start with the variable's name and never its value.

| Variable | Default | Meaning |
|---|---|---|
| `INTERVALS_API_KEY` | unset (off) | intervals.icu personal API key: rides are uploaded there and the plan is read from its calendar (ADR 0016) |
| `INTERVALS_ATHLETE_ID` | `0` | `i12345`; `0` is the athlete the key belongs to |
| `INTERVALS_BASE_URL` | `https://intervals.icu` | Only to go through a proxy |
| `PLAN_REFRESH_MINUTES` | `15` | How often the plan is read, 1 to 1440 |
| `TIMEZONE` | `UTC` | Your IANA time zone: which day is "today", what "07:00" and "Sunday 18:00" mean |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | unset | The OAuth client you create in Google Cloud (section 14) |
| `GOOGLE_REFRESH_TOKEN` | unset | Printed once by `google-auth` (section 14) |
| `GOOGLE_CALENDAR_ID` | unset | The dedicated calendar's id, `….@group.calendar.google.com`. The calendar is **on only when all four `GOOGLE_*` are set** |
| `GCAL_WORKOUT_START` | `07:00` | The time a workout starts, in `TIMEZONE`: the calendar event's start and the reference of the lead reminder |
| `TELEGRAM_BOT_TOKEN` | unset (off) | From BotFather (section 13) |
| `TELEGRAM_OWNER_CHAT_ID` | unset | The one chat the bot answers; without it the bot only listens and logs chat ids (section 13) |
| `REMINDER_EVENING_TIME` | `20:00` | The evening-before reminder, `HH:MM`, or `off` |
| `REMINDER_LEAD_MINUTES` | `60` | The second reminder this long before the start; `0` is off |
| `WEEKLY_SUMMARY_DAY`, `WEEKLY_SUMMARY_TIME` | `sun`, `18:00` | When the weekly summary comes; the time can be `off` |
| `GOOGLE_API_BASE_URL`, `GOOGLE_TOKEN_URL`, `TELEGRAM_BASE_URL` | the real ones | Only for tests and proxies |

### Planned: not read yet (marked planned, names as the stages settle them)

None at the moment: the language-model endpoint of spec §7.2 is `LLM_BASE_URL`, `LLM_API_KEY` and `LLM_MODEL` (section 17).

## 12. The MCP server

The service can also be an MCP server for AI clients (Claude's apps and others), at `https://overdrive.llmto.day/mcp`, behind an
OAuth server for which **you** are the only user (ADR 0018). It stays off until you give it an address and a passphrase. Caddy needs no
change: the site file proxies everything, including `/mcp`, `/.well-known/…` and `/oauth/…`.

1. Make the hash of a passphrase you will remember (a few random words, at least 12 characters). On the host, from the image:

   ```bash
   docker compose run --rm --no-deps server node dist/hash-passphrase.js
   ```

   It asks twice without showing what you type and prints one line, `OWNER_PASSPHRASE_HASH=scrypt:32768:8:1:…`. (Any machine with the
   repository can run `pnpm --filter @overdrive/server run hash-passphrase` instead.)
2. Put that line and the address into `.env`, without printing them anywhere else:

   ```bash
   # paste the line the command printed:
   #   OWNER_PASSPHRASE_HASH=scrypt:32768:8:1:…
   #   PUBLIC_BASE_URL=https://overdrive.llmto.day
   docker compose up -d server
   docker compose logs --tail 20 server        # "MCP server is on", with the address
   ```

   The hash has colons and no dollar sign on purpose: compose would read a `$` in `.env` as a variable.
3. Check that the front door answers (both documents are public):

   ```bash
   curl -fsS https://overdrive.llmto.day/.well-known/oauth-protected-resource
   curl -fsS https://overdrive.llmto.day/.well-known/oauth-authorization-server
   curl -sS -o /dev/null -w '%{http_code}\n' -X POST https://overdrive.llmto.day/mcp     # 401: a token is needed
   ```
4. In the AI client, add a custom connector (a remote MCP server) with the address `https://overdrive.llmto.day/mcp`. The client opens a
   page of the service in the browser, **Connect to Overdrive**: type the passphrase and press Approve. Five wrong passphrases lock
   that page for 15 minutes, then 30, and so on up to a day (tokens already issued keep working).
5. For a client that cannot do OAuth but can send a fixed header, add `MCP_STATIC_TOKENS=name:<openssl rand -base64 36>` to `.env`
   and `docker compose up -d server`; give the client `Authorization: Bearer <that>`.

To end every client's access at once (they must ask for approval again), run in the database:
`DELETE FROM oauth_tokens;` (`docker compose exec db psql -U overdrive -d overdrive`). To change the passphrase, make a new hash and
put it in `.env` (grants already issued stay; delete them as above if you want them gone). To turn the MCP server off, empty
`PUBLIC_BASE_URL` or `OWNER_PASSPHRASE_HASH` and `docker compose up -d server`: its routes disappear.

The plan tools (`create_workout` and the others) need `INTERVALS_API_KEY`; without it they answer `plan source not configured` and
the profile and ride tools still work.

## 13. The Telegram bot

The bot (spec §5.4, ADR 0019) sends reminders, a summary after each ride and a weekly summary, and answers `/today`, `/week`,
`/skip`, `/move` and `/help`. From stage P3-3 it also sends the **written notes and alerts** (section 17): the morning note, a note
after each ride (with the opening question of a discussion topic in a block you can tap to copy), the weekly review, and the alerts for a
metric outside your usual range and for a plan that is drifting. It answers **only your chat** and ignores everyone else without a word. The token and the chat id
stay in `.env`: you type them into the file on the host and never into a chat.

1. **Make the bot.** In Telegram open `@BotFather`, send `/newbot`, give it a name and a user name ending in `bot`. BotFather
   answers with a token that looks like `123456789:AA…`. Put it in `.env` with an editor on the host:

   ```
   TELEGRAM_BOT_TOKEN=<the token BotFather gave you>
   ```

2. **Start with the token only.** `docker compose up -d server`, then `docker compose logs --tail 20 server`: it says `Telegram bot
   started in setup mode`. In this mode the bot listens and replies to nobody.
3. **Find your chat id.** In Telegram open your new bot and send it any message (for example `/start`). It does not answer. Read
   your chat id from the server's log:

   ```bash
   docker compose logs --tail 50 server | grep 'TELEGRAM_OWNER_CHAT_ID is not set' | grep -o '"chatId":-\?[0-9]*'
   # "chatId":123456789
   ```

   Use the id of your own private chat (a positive number). Other people who find the bot get the same log line, and nothing else.
4. **Make that chat the owner.** Put `TELEGRAM_OWNER_CHAT_ID=123456789` in `.env`, then `docker compose up -d server`. Send `/help`:
   the bot now answers.
5. **Optional.** Set `REMINDER_EVENING_TIME`, `REMINDER_LEAD_MINUTES`, `WEEKLY_SUMMARY_DAY`, `WEEKLY_SUMMARY_TIME` and
   `GCAL_WORKOUT_START` (the time a workout is assumed to start, shared with the calendar). Reminders and summaries are in
   `TIMEZONE`, so set that too.

Check: with a workout planned for tomorrow, the evening reminder arrives at `REMINDER_EVENING_TIME` with **Skip** and **Move**
buttons. `/today` and `/week` need the plan source (`INTERVALS_API_KEY`); without it the bot says so.

To change the token, send `/revoke` to BotFather, update `.env` and `docker compose up -d server`. If the bot stays silent, look in
the log for `another chat ignored` (the chat id there is not the one in `.env`) and for `polling failed` (network or token).

## 14. Google Calendar

The server pushes your planned workouts one way into a calendar of its own (spec §5.3, ADR 0019). Nothing is read back from Google.
You do this once, in a browser and a terminal of your own. The client secret and the refresh token go from your terminal into
`.env` and nowhere else.

1. **The calendar.** In Google Calendar make a new calendar (Other calendars, plus, Create new calendar), for example
   "Overdrive". Open its settings, **Integrate calendar**, and copy the **Calendar ID** (`…@group.calendar.google.com`) into `.env`
   as `GOOGLE_CALENDAR_ID`. While there, under **Event notifications** add the reminder you want (the events carry none of their
   own, the calendar's apply).
2. **The Google Cloud project.** At `console.cloud.google.com` create a project, then APIs and services, **Library**, enable
   **Google Calendar API**.
3. **The consent screen.** APIs and services, OAuth consent screen: user type **External**, an app name, your own address as
   support and contact. Add the scope `…/auth/calendar.events`. Then **publish the app** ("In production"): an app left in
   *Testing* gets refresh tokens that stop working after seven days. An unpublished-to-the-public (unverified) app is fine for your
   own use: Google shows a warning when you allow it, choose Advanced and continue.
4. **The OAuth client.** APIs and services, Credentials, Create credentials, **OAuth client ID**, application type **Desktop app**.
   Copy the client ID and the client secret into `.env` as `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`.
5. **The refresh token.** On a computer with this repository, Node 24 and pnpm (`pnpm install` once), run in a terminal:

   ```bash
   pnpm --filter @overdrive/server run google-auth
   ```

   It asks for the client ID and secret (the secret is not shown) unless they are in the environment, prints an address, and waits.
   Open the address in a browser, sign in with the account that owns the calendar and allow access. If the terminal and the browser
   are on the same computer the helper picks the answer up by itself. If the page then cannot be reached (the browser is on another
   computer), copy the **whole address** from the browser's address bar and paste it into the terminal. It then prints one line:

   ```
   GOOGLE_REFRESH_TOKEN=1//0…
   ```

   Nothing is written to a file. Put that line into `.env` with an editor.

   To run it on the host instead, from the repository root: `docker compose run --rm -it server node dist/gcal/auth-cli.js`
   (compose hands it the client ID and secret from `.env`; use the paste-the-address way).
6. **Start.** `docker compose up -d server`. The log says `calendar event created` for each workout, and the calendar shows the
   next 14 days' workouts at `GCAL_WORKOUT_START` (default 07:00 in `TIMEZONE`) for as long as each one lasts, with the steps in the
   description. Change or delete a workout in intervals.icu (or skip it from Telegram) and the event follows within a minute or two.

If Google refuses the credentials the log has one error line telling you to run `google-auth` again, and nothing more is tried for
six hours (a restart tries at once). Revoke the access any time at `myaccount.google.com/permissions`. Edits you make to the events
in Google Calendar are not read back and are overwritten the next time that workout changes.

## 15. Strava

Each ride is posted to your Strava account as a **virtual ride** with a short text description (ADR 0020). The server only
**posts**: it never reads anything from Strava (spec §5.5), and the only permission it asks for is `activity:write`. It stays off
until you give it the three values below. You do the Strava side once, in your own terminal; none of these values belongs in a
chat, an issue or a commit.

1. **Create the Strava API application** (you need a Strava account with an active subscription: Strava requires one to create an
   application). At <https://www.strava.com/settings/api> create an application (any name, any website) and set **Authorization
   Callback Domain** to `localhost`. Note the **Client ID** (a number) and the **Client Secret**. A new application works for your own
   account only, which is all this needs.
2. **Get the refresh token**, once, in a terminal of yours (from a clone of this repository, or on the host from the image):

   ```bash
   pnpm --filter @overdrive/server run strava-auth
   # on the host, from the image:  docker compose run --rm --no-deps -it server node dist/strava-auth.js
   ```

   It asks for the Client ID and the Client Secret (nothing is shown as you type the secret) and prints an address. Open it in a
   browser where you are signed in to Strava and press **Authorize** (it asks only to upload activities). The browser then lands on
   a page on `localhost` that does not load: that is expected. Copy the **whole address from the address bar**, paste it into the
   terminal, and it prints one line, `STRAVA_REFRESH_TOKEN=…`. It prints only to the terminal and refuses to run when its output is
   a file or a pipe.
3. **Put the three values in the server's `.env`** (on the host, never in the repository) and start the server:

   ```bash
   # STRAVA_CLIENT_ID=<the number>
   # STRAVA_CLIENT_SECRET=<the secret>
   # STRAVA_REFRESH_TOKEN=<the line step 2 printed>
   docker compose up -d server
   docker compose logs --tail 20 server        # no "Strava upload is off" line; the first ride shows "ride sent to Strava"
   ```

4. **Ride.** The next ride that reaches the server goes to Strava a few seconds later. Rides you made **before** this step are not
   posted. To post them as well, add `STRAVA_BACKFILL=true`, `docker compose up -d server`, let it finish (Strava allows about 100
   status checks per 15 minutes, so a long history takes a while and the server paces itself), then remove the line.
5. **Look at the first real upload** and tell the project what you see (ADR 0020 section 7): is it a Virtual Ride with the right
   start time and length, do the title and description read as written, are the power and heart-rate graphs there, and does Strava show
   a map (none is expected).

If your intervals.icu account is also linked to Strava (Settings, Connections), turn its upload to Strava off: Strava would see the same
ride twice. The server counts "duplicate of activity N" as sent, but whichever arrives first wins, and that may be intervals.icu's
copy, without the description this server writes.

The refresh token changes by itself: Strava rotates it and the server keeps the newest one in its database, so `.env` holds an old
value after the first ride and that is fine. **If the value in `.env` and the database ever disagree**, the rule is: a value in `.env`
that is different from the one the database was started with wins at the next start. So to recover from any trouble (a revoked
application, a token the log says Strava refused) run `strava-auth` again, replace `STRAVA_REFRESH_TOKEN` in `.env` with its new line
and `docker compose up -d server`.

To see where each ride is: `docker compose exec db psql -U overdrive -d overdrive -c "SELECT ride_id, state, attempts, last_error, external_id
FROM ride_destinations WHERE destination = 'strava'"`. A ride in `pending` with a `last_error` that mentions Strava refusing the
token, the scope or the account (HTTP 401 or 403) needs you: check the subscription and the application, run `strava-auth` again
if needed; the server asks again after a restart or six hours, and says it only once in the log. To turn the upload off, empty any of
the three values.

## 16. Wellness and recovery

The companion app on your phone reads your watch's records from Health Connect and uploads them to `POST /v1/wellness` (spec §6.1,
§6.2, ADR 0023); the service works out your daily figures, your personal usual ranges and a morning state, and keeps the workouts
the watch recorded. It needs **no key and no setting**: the phone's device token (`DEVICE_TOKENS`, make one named `companion`) is
enough. Optional:

- **intervals.icu wellness** is read too when `INTERVALS_API_KEY` is set (every `WELLNESS_PULL_HOURS`, six by default), as a second source
  of the same figures. `WELLNESS_SOURCE_ORDER` says which source wins for each figure (the watch first by default).
- **The watch's workouts** are forwarded to intervals.icu as activities when the key is set (a session older than a week when it arrives, and a
  watch session that overlaps a ride from the tablet, are not). Nothing goes to Strava.
- For the heart-rate load of those workouts the service needs your maximum and threshold heart rate: set them in the companion's
  Settings, or `PATCH /v1/profile` (`maxHeartRate`, `thresholdHeartRate`), or `update_profile` through MCP. Until you do, it uses estimates
  from your rides (the highest heart rate, and 95% of your best 20 minutes) and marks the load approximate.

**This is health data.** The database holds your raw records, daily figures and workouts: the backups of section 8 contain it, so keep
them as private as the database. The logs hold counts and ids only, never a measured value. It leaves the server only to the MCP clients
you approved and, as average and maximum heart rate of a workout, to intervals.icu with your own key.

To look at what has arrived (counts, not values): `docker compose exec db psql -U overdrive -d overdrive -c "SELECT kind, count(*),
max(received_at) FROM wellness_records GROUP BY kind"`; the queue of workouts for intervals.icu: `SELECT activity_id, state, attempts,
last_error FROM activity_destinations`. A workout in `skipped` has the reason in `last_error`. The recovery state says `learning` until
14 nights have arrived (`WELLNESS_NIGHTS_NEEDED`).

## 17. Written insights, alerts and the language model

The service writes short notes from facts it has computed (spec §6.5, §6.6, §12.3, ADR 0025): a **morning note** at `INSIGHT_MORNING_TIME`
(state, why, what to do with today's workout), a **note after each ride** (against its plan), a **weekly review** at the weekly summary's
time, **alerts** when a metric stays outside your usual range or the plan drifts, and **discussion topics** for the coach agent. They go to
your Telegram chat (when the bot is on) and to the companion's Today screen (`GET /v1/insights`), and the planning agent can read them
and the facts behind them through MCP (`list_insights`, `get_facts`, `list_discussion_topics`). The notes never change the plan.

**It works with no setting.** Without a language model the notes are written from fixed templates (`source: template` in the `insights`
table), and **no health data leaves the server**. That is the default.

**To have a language model write the notes** (more natural wording; the same facts, the same checks), point the server at an
OpenAI-compatible chat-completions endpoint (MiniMax M3.1 or any other: the one you chose, hosted or on your own machine):

1. On the host, in an editor (never in a chat, an issue or a commit; the key is a secret), add to `.env`:
   `LLM_BASE_URL=https://<the endpoint>/v1` (the address the path `/chat/completions` is added to), `LLM_MODEL=<the model's name>` and,
   if it needs one, `LLM_API_KEY=<the key>`. https is required (http only for `localhost`).
2. `docker compose up -d server`. The start-up log says "written insights use the configured language model"; if only one of
   `LLM_BASE_URL` and `LLM_MODEL` is set it says which is missing and the notes stay on templates.
3. **Health data leaves the server only to that address**, as the facts of the day: recovery state and signals, planned workout, load,
   fitness and form, a ride's figures against its plan. Nothing else, nothing to anyone else; the log holds no fact, text, key or address.
   Read your provider's terms on what it does with what it receives before turning this on.
4. The server checks every text the model writes: a number that was not in the facts, a date or time that was not, or a word of the
   deny list (no diagnosis, no medical condition) rejects it; the model is asked once more with the problem named, and a second
   rejection (or any failure of the endpoint) stores the template's text instead, so Telegram is never empty. `INSIGHT_DENY_WORDS`
   adds your own words. `INSIGHTS_LANGUAGE` is put in the prompt as you write it; the template stays English.

To see how notes were written: `docker compose exec db psql -U overdrive -d overdrive -c "SELECT kind, source, prompt_version, count(*)
FROM insights GROUP BY 1, 2, 3"`. Many `template` rows with a model configured means its text is being rejected or the endpoint is failing:
the log says which ("a note was rejected", "model-server", "model-timeout"; counts and kinds, never the text).

What the alerts mean, and their thresholds: ADR 0025. Change them with `ALERT_*`. `INSIGHTS_ENABLED=false` switches the whole feature off.

## Troubleshooting

- `Invalid configuration:` at start: the message names the variable. Fix it in `.env`, then `docker compose up -d server`.
- `password authentication failed` in the server log: `POSTGRES_PASSWORD` changed after the volume was created. The
  database keeps the password it was made with. Put the old value back in `.env`, or change the password inside the
  database to match (`docker compose exec db psql -U overdrive -d overdrive`, then `ALTER USER overdrive PASSWORD '…';`).
- The tablet says the token is rejected: check section 5, then the token in the tablet's Settings, Sync.
- The tablet says it cannot reach the service ("Service unreachable" in v0.2.0; from v0.2.1 the line names the cause, for example
  "Cannot find the service address (DNS)") while the tablet's browser opens `https://overdrive.llmto.day/healthz`: the tablet's own
  name resolution is the usual cause (the browser has its own secure DNS, apps use the system's). Settings, More connections,
  **Private DNS**, hostname `dns.google` fixed it on the owner's tablet (2026-11-18). Nothing reaches the server's log in this case;
  `docker compose logs server` shows no `/v1` request from the tablet.
- The log says `MCP server is off: … is not set`: `PUBLIC_BASE_URL` and `OWNER_PASSPHRASE_HASH` must both be in `.env`, and the
  server restarted with `docker compose up -d server` (not `restart`).
- `Invalid configuration:` naming `OWNER_PASSPHRASE_HASH`: the value is not a hash from `hash-passphrase` (a plain passphrase, a
  line cut in the middle, quotes around it that were not meant). `PUBLIC_BASE_URL` must be `https://` and only the address.
- The page says `Too many wrong passphrases`: wait for the time it names. The lock is for the whole service, not for one browser.
- The log says `Strava upload is off: … is not set`: all of `STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET` and `STRAVA_REFRESH_TOKEN` must be
  in `.env`, and the server restarted with `docker compose up -d server`.
- The log says `Strava upload stopped: it needs the owner`: the reason is in the line and in `last_error` of the waiting rides (a token
  or scope Strava refused, or an account that may not upload: section 15). Fix it and restart; nothing is lost.
- The log says `oauth refresh token reused`: a client used an old refresh token after its grace period, so that client's sign-in was
  ended; approve it again.
