# Rehearsal of the multi-tenant move

`rehearse.sh` proves, on a copy, that the move described in `docs/deploy.md` section 22 changes nothing the rider can see. Run it
on the production host before the cutover. Production is only read (one `pg_dump`); no `up`, `restart`, `build`, `down` or `rm` touches
the `overdrive` compose project.

```bash
./deploy/rehearsal/rehearse.sh                      # takes a dump, runs everything, cleans up
DUMP=~/backups/overdrive-x.sql.gz WORK=~/rh ./deploy/rehearsal/rehearse.sh   # reuse a dump, keep the results in ~/rh
```

Environment: `PROD_DIR` (default `~/overdrive`), `ENV_FILE` (default `$PROD_DIR/.env`; docker reads it through `env_file`, the script never
reads or prints it), `BACKUPS` (default `~/backups`), `DUMP`, `WORK`. Needs docker, `jq`, `openssl`. Exit code 0 means every step passed.

What it does, in order:

0. An internal docker network `ovd-rehearsal-net` (no route out; the script proves 1.1.1.1 and github.com are unreachable). The image
   production runs is retagged `ovd-rehearsal-old`; the new one is built with `docker build -f server/Dockerfile .` as `ovd-rehearsal-new`.
   `TELEGRAM_BOT_TOKEN` is empty, so no bot polls; Strava, intervals.icu, Google, LLM and TTS calls fail on the network, as expected.
1. The documented dump into `~/backups` (mode 600).
2. Two `postgres:18` scratch databases restored from the same dump; per-table row count and md5 over rows in primary-key order,
   sequences, column definitions, indexes and constraints must be equal.
3. Old image on the single-user layout: 14 GET endpoints, two rounds, with a generated token named `rehearsal`.
4. New image with `MIGRATE_ONLY=1`: exit 0, layout `legacy`, owner id 1, whole `before` and `after` summaries equal, no token and no
   value of the production environment in the output (checked inside a container that holds that environment; only a key name would be printed).
5. The old image on the moved database must refuse to start (checksum error on `schema_migrations`).
6. New image, normal start; the same GETs through `curl`, `GET /admin/users` with `ADMIN_TOKEN` on 127.0.0.1 inside the container, `/v1/me`.
7. The JSON of both sides, with `generatedAt` and `computedAt` removed and keys sorted, must be identical.
8. Cleanup on exit (also after a failure): containers, network, scratch images, the generated compose file.

Tokens and the database password are generated per run and live only in the script's environment. Results in `WORK` hold the owner's
ride data: delete them when done (a temporary `WORK` is deleted by the script). Never commit them or the dump.
