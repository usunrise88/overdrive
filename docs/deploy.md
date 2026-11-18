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
| `SERVER_PORT` | `8080` | Host loopback port for the server. Here `27180`; the Caddy site file must match |
| `POSTGRES_PORT` | `5432` | Host loopback port for the database. Here `27181` |
| `LOG_LEVEL` | `info` | `fatal`, `error`, `warn`, `info`, `debug`, `trace` or `silent` |

### Set by compose inside the server container

| Variable | Value | Meaning |
|---|---|---|
| `DATABASE_URL` | built from `POSTGRES_PASSWORD` | `postgres://overdrive:…@db:5432/overdrive` |
| `DEVICE_TOKENS` | from `.env` | As above |
| `LOG_LEVEL` | from `.env` | As above |

### Set by the image (`server/Dockerfile`)

| Variable | Value | Meaning |
|---|---|---|
| `NODE_ENV` | `production` | |
| `HOST` | `0.0.0.0` | Inside the container; outside Docker the default is `127.0.0.1` |
| `PORT` | `8080` | Inside the container; compose publishes it as `SERVER_PORT` on loopback |

### Planned: not read yet (marked planned, names as the stages settle them)

| Variable | Added by stage | Meaning |
|---|---|---|
| `INTERVALS_API_KEY`, `INTERVALS_ATHLETE_ID` | planned: P2-1, the intervals.icu upload | intervals.icu access (ADR 0014, section 5) |
| `TELEGRAM_*` (names not fixed) | planned: P2-4, the Telegram bot | Telegram bot token and chat settings (spec §5.4) |
| `GOOGLE_*` (names not fixed) | planned: P2-4, Google Calendar | Google OAuth client for the calendar (spec §5.3) |
| `STRAVA_*` (names not fixed) | planned: P2-5, Strava upload | Strava access; write-only (spec §5.5) |
| LLM endpoint (name not fixed) | planned: insights, not yet in the roadmap | Endpoint and key of the model that writes insight text (spec §7.2) |

## Troubleshooting

- `Invalid configuration:` at start: the message names the variable. Fix it in `.env`, then `docker compose up -d server`.
- `password authentication failed` in the server log: `POSTGRES_PASSWORD` changed after the volume was created. The
  database keeps the password it was made with. Put the old value back in `.env`, or change the password inside the
  database to match (`docker compose exec db psql -U overdrive -d overdrive`, then `ALTER USER overdrive PASSWORD '…';`).
- The tablet says the token is rejected: check section 5, then the token in the tablet's Settings, Sync.
