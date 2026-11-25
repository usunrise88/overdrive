#!/usr/bin/env bash
# Rehearsal of the multi-tenant move (docs/deploy.md section 22). Read deploy/rehearsal/README.md first.
# Production is only read: one pg_dump. Everything else runs in project `ovd-rehearsal` on an internal docker network.
# Nothing secret is written to the repository; generated secrets live in this process's environment only.
set -Eeuo pipefail

PROD_DIR="${PROD_DIR:-$HOME/overdrive}"            # the production compose project directory (holds compose.yaml and .env)
ENV_FILE="${ENV_FILE:-$PROD_DIR/.env}"             # read by docker itself via env_file, never by this script
PROD_SERVER="${PROD_SERVER:-overdrive-server-1}"   # only `docker inspect` is run on it
BACKUPS="${BACKUPS:-$HOME/backups}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${WORK:-}"                                   # where results go; without it a temp dir that is deleted on exit
KEEP_WORK=1
if [ -z "$WORK" ]; then WORK="$(mktemp -d)"; KEEP_WORK=0; fi
P=ovd-rehearsal
NET=ovd-rehearsal-net
OLD_IMG=ovd-rehearsal-old:latest
NEW_IMG=ovd-rehearsal-new
DUMP="${DUMP:-}"                                   # reuse a dump instead of taking one

mkdir -p "$WORK"; chmod 700 "$WORK"
COMPOSE_FILE_PATH="$WORK/compose.rehearsal.yaml"
RH_DB_PW="$(openssl rand -hex 16)"; RH_TOKEN="$(openssl rand -hex 24)"; RH_ADMIN="$(openssl rand -hex 24)"
export RH_DB_PW RH_TOKEN RH_ADMIN

dc() { docker compose -p "$P" -f "$COMPOSE_FILE_PATH" --project-directory "$WORK" "$@"; }
step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

cleanup() {
  local rc=$?
  step "cleanup (exit $rc)"
  dc down --remove-orphans --volumes >/dev/null 2>&1 || true
  docker rm -f ovd-rh-client ovd-rh-srv-old ovd-rh-srv-guard ovd-rh-srv-new ovd-rh-srv-migrate >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker rmi "$OLD_IMG" "$NEW_IMG" >/dev/null 2>&1 || true
  unset RH_DB_PW RH_TOKEN RH_ADMIN
  rm -f "$COMPOSE_FILE_PATH"
  if [ "$KEEP_WORK" = 1 ]; then echo "results kept in $WORK (they hold the owner's ride data: delete them when done)"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

# jq filter: drop the fields that change from call to call (jq -S sorts the keys)
CLEAN='walk(if type=="object" then del(.generatedAt, .computedAt) else . end)'

# ---------------------------------------------------------------------------------------------------------------------
step "0. internal network, images"
docker network create --internal "$NET" >/dev/null
OLD_ID="$(docker inspect "$PROD_SERVER" --format '{{.Image}}')"
docker tag "$OLD_ID" "$OLD_IMG"
docker build -q -t "$NEW_IMG" -f "$REPO/server/Dockerfile" "$REPO" >/dev/null   # the context compose.yaml uses: the repository root

cat > "$COMPOSE_FILE_PATH" <<YAML
x-server: &server
  env_file:
    - path: $ENV_FILE
      required: true
  pull_policy: never
  restart: "no"
x-env: &env
  HOST: 0.0.0.0
  PORT: "8080"
  ADMIN_PORT: "8081"
  DEVICE_TOKENS: "rehearsal:\${RH_TOKEN}"
  RH_TOKEN: \${RH_TOKEN}
  TELEGRAM_BOT_TOKEN: ""
services:
  db-old: &db
    image: postgres:18
    container_name: ovd-rh-old-db
    environment: {POSTGRES_USER: overdrive, POSTGRES_DB: overdrive, POSTGRES_PASSWORD: "\${RH_DB_PW}"}
    tmpfs: [/var/lib/postgresql]
  db-new:
    <<: *db
    container_name: ovd-rh-new-db
  srv-old:
    <<: *server
    image: $OLD_IMG
    container_name: ovd-rh-srv-old
    environment: {<<: *env, DATABASE_URL: "postgres://overdrive:\${RH_DB_PW}@ovd-rh-old-db:5432/overdrive"}
  srv-guard:
    <<: *server
    image: $OLD_IMG
    container_name: ovd-rh-srv-guard
    environment: {<<: *env, DATABASE_URL: "postgres://overdrive:\${RH_DB_PW}@ovd-rh-new-db:5432/overdrive"}
  srv-migrate:
    <<: *server
    image: $NEW_IMG
    container_name: ovd-rh-srv-migrate
    environment: {<<: *env, MIGRATE_ONLY: "1", ADMIN_TOKEN: "\${RH_ADMIN}", DATABASE_URL: "postgres://overdrive:\${RH_DB_PW}@ovd-rh-new-db:5432/overdrive"}
  srv-new:
    <<: *server
    image: $NEW_IMG
    container_name: ovd-rh-srv-new
    environment: {<<: *env, ADMIN_TOKEN: "\${RH_ADMIN}", DATABASE_URL: "postgres://overdrive:\${RH_DB_PW}@ovd-rh-new-db:5432/overdrive"}
networks:
  default: {name: $NET, external: true}
YAML

docker run -d --name ovd-rh-client --network "$NET" --entrypoint sleep -e RH_TOKEN "$NEW_IMG" infinity >/dev/null

step "0b. the network has no way out"
if docker exec ovd-rh-client curl -s -m 5 -o /dev/null https://1.1.1.1; then fail "the internal network reaches 1.1.1.1"; fi
if docker exec ovd-rh-client curl -s -m 5 -o /dev/null https://github.com; then fail "the internal network reaches the public host"; fi
echo "ok: neither 1.1.1.1 nor github.com is reachable"

# ---------------------------------------------------------------------------------------------------------------------
step "1. backup (read-only on production)"
if [ -z "$DUMP" ]; then
  mkdir -p "$BACKUPS"
  DUMP="$BACKUPS/overdrive-rehearsal-$(date +%F-%H%M).sql.gz"
  (cd "$PROD_DIR" && docker compose exec -T db pg_dump -U overdrive overdrive | gzip > "$DUMP")
  chmod 600 "$DUMP"
fi
gzip -t "$DUMP"
echo "dump: $DUMP ($(stat -c %s "$DUMP") bytes gzip)"

# ---------------------------------------------------------------------------------------------------------------------
step "2. two scratch databases, same dump"
dc up -d db-old db-new >/dev/null
ready() { for _ in $(seq 90); do docker exec "$1" pg_isready -U overdrive -d overdrive -h 127.0.0.1 >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
for db in ovd-rh-old-db ovd-rh-new-db; do
  ready "$db"; sleep 3; ready "$db"   # the image restarts once after its init
  gunzip -c "$DUMP" | docker exec -i "$db" psql -U overdrive -d overdrive -q -v ON_ERROR_STOP=1 >/dev/null
done
psqlq() { docker exec "$1" psql -U overdrive -d overdrive -At -c "$2"; }
echo "rows: rides=$(psqlq ovd-rh-old-db 'select count(*) from rides') profile=$(psqlq ovd-rh-old-db 'select count(*) from profile')"

# One line per table (count and md5 of its rows ordered by primary key), per sequence, per table's columns; then indexes, constraints.
CHECKSUM_SQL=$(cat <<'SQL'
select 'table ' || c.relname || ' ' || (xpath('/row/n/text()', c.x))[1] || ' ' || (xpath('/row/h/text()', c.x))[1]
from (select cl.relname, query_to_xml(format(
  'select count(*) as n, md5(coalesce(string_agg(md5(t::text), %L order by %s), %L)) as h from %I.%I t', '',
  coalesce((select string_agg(quote_ident(a.attname), ',' order by array_position(i.indkey::int2[], a.attnum))
            from pg_index i join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)
            where i.indrelid = cl.oid and i.indisprimary), 't::text'), '', n.nspname, cl.relname), false, true, '') as x
      from pg_class cl join pg_namespace n on n.oid = cl.relnamespace
      where cl.relkind = 'r' and n.nspname = 'public') c
union all
select 'sequence ' || sequencename || ' ' || coalesce(last_value::text, 'null') from pg_sequences where schemaname = 'public'
union all
select 'columns ' || table_name || ' ' || md5(string_agg(column_name || ':' || data_type || ':' || coalesce(column_default, '') || ':' || is_nullable, ',' order by ordinal_position))
from information_schema.columns where table_schema = 'public' group by table_name
union all
select 'indexes ' || md5(string_agg(indexdef, ',' order by indexname)) from pg_indexes where schemaname = 'public'
union all
select 'constraints ' || md5(string_agg(pg_get_constraintdef(oid), ',' order by conname)) from pg_constraint where connamespace = 'public'::regnamespace
order by 1
SQL
)
psqlq ovd-rh-old-db "$CHECKSUM_SQL" > "$WORK/checksum-old.txt"
psqlq ovd-rh-new-db "$CHECKSUM_SQL" > "$WORK/checksum-new.txt"
diff -q "$WORK/checksum-old.txt" "$WORK/checksum-new.txt" >/dev/null || fail "the two restores differ"
echo "checksums equal: $(grep -c '^table ' "$WORK/checksum-old.txt") tables, $(grep -c '^sequence ' "$WORK/checksum-old.txt") sequences"
grep '^table ' "$WORK/checksum-old.txt" | awk '{printf "%s=%s ", $2, $3}'; echo

RIDE_ID="$(psqlq ovd-rh-old-db 'select id from rides order by start_epoch_millis desc limit 1')"
[ -n "$RIDE_ID" ] || fail "no ride in the dump"

# ---------------------------------------------------------------------------------------------------------------------
# the range endpoints serve at most 62 days
ENDPOINTS=(/v1/rides "/v1/rides/$RIDE_ID" "/v1/rides/$RIDE_ID/metrics" "/v1/metrics?from=2026-10-01&to=2026-11-30" /v1/profile "/v1/workouts?from=2026-10-01&to=2026-11-30" "/v1/insights?from=2026-10-01&to=2026-11-30" /v1/notebook
  /v1/routes /v1/repeats /v1/power-skills /v1/progress "/v1/effort?weeks=12" "/v1/wellness?from=2026-10-01&to=2026-11-30")

wait_up() { # container name
  for _ in $(seq 90); do
    docker exec ovd-rh-client curl -sf -m 3 -o /dev/null "http://$1:8080/healthz" 2>/dev/null && return 0
    [ "$(docker inspect -f '{{.State.Running}}' "$1")" = true ] || { docker logs "$1" 2>&1 | tail -20 | sed 's/^/    /'; fail "$1 stopped"; }
    sleep 1
  done
  fail "$1 did not come up"
}

# fetch <host> <out-dir>: two rounds over every endpoint; JSON cleaned and key-sorted; the HTTP statuses beside them
fetch() {
  local host="$1" out="$2" round i ep n code
  mkdir -p "$out"
  for round in 1 2; do
    i=0
    for ep in "${ENDPOINTS[@]}"; do
      i=$((i+1)); n="$out/r$round-$(printf %02d "$i").json"
      code="$(docker exec ovd-rh-client sh -c 'curl -s -m 120 -o /tmp/body -w "%{http_code}" -H "Authorization: Bearer $RH_TOKEN" "$1"' _ "http://$host:8080$ep" || true)"
      docker exec ovd-rh-client cat /tmp/body | jq -S "$CLEAN" > "$n" 2>/dev/null || echo '"<not json>"' > "$n"
      echo "$code $ep" >> "$out/status-r$round.txt"
    done
  done
}

step "3. OLD side: the production image on the single-user layout"
dc up -d --no-deps srv-old >/dev/null
wait_up ovd-rh-srv-old
fetch ovd-rh-srv-old "$WORK/old"
dc rm -sf srv-old >/dev/null
echo "old statuses (round 2):"; sed 's/^/  /' "$WORK/old/status-r2.txt"

step "4. NEW side: MIGRATE_ONLY on the new database"
set +e
dc run --rm --no-deps -T srv-migrate > "$WORK/migrate.out" 2> "$WORK/migrate.err"
rc=$?
set -e
echo "exit code: $rc"
[ "$rc" = 0 ] || { tail -20 "$WORK/migrate.err" | sed 's/^/    /'; tail -20 "$WORK/migrate.out" | sed 's/^/    /'; fail "MIGRATE_ONLY exited $rc"; }
sed -n '/^{$/,$p' "$WORK/migrate.out" > "$WORK/report.json"   # the report is the JSON object printed after the log lines
jq -e . "$WORK/report.json" >/dev/null || fail "no JSON report in the output"
jq -r '"layout=\(.layout) owner=\(.owner|tojson) importedKeys=\(.importedKeys|length) migrations=\(.migrations|tojson)\nperUserKeysStillInEnv=\(.perUserKeysStillInEnv|join(","))"' "$WORK/report.json"
[ "$(jq -r .layout "$WORK/report.json")" = legacy ] || fail "layout is not legacy"
[ "$(jq -r .owner.id "$WORK/report.json")" = 1 ] || fail "owner id is not 1"
jq -e '.before == .after' "$WORK/report.json" >/dev/null || fail "before and after summaries differ"
echo "before == after (the whole summaries, every table)"
echo "warnings and errors in the log (messages only):"
grep -h '"level":\(40\|50\|"warn"\|"error"\)' "$WORK/migrate.out" "$WORK/migrate.err" 2>/dev/null | jq -r '.msg // .' 2>/dev/null | sed 's/^/  /' || true
cat "$WORK/migrate.out" "$WORK/migrate.err" > "$WORK/migrate.all"
grep -qF -- "$RH_TOKEN" "$WORK/migrate.all" && fail "the rehearsal token is in the output"
grep -qF -- "$RH_ADMIN" "$WORK/migrate.all" && fail "the admin token is in the output"
# No value of the production environment in the output: checked inside a container that holds that environment, so no value
# leaves it; only the NAME of a key whose value was found is printed.
dc run --rm --no-deps -T --entrypoint sh srv-new -c '
  cat > /tmp/out
  for k in $(env | cut -d= -f1); do
    case "$k" in PATH|HOME|PWD|HOSTNAME|NODE_*|YARN_*|SHLVL|_|TERM|HOST|PORT|ADMIN_PORT|LOG_LEVEL) continue;; esac
    v=$(printenv "$k"); [ "${#v}" -ge 6 ] || continue
    grep -qF -- "$v" /tmp/out && echo "OUTPUT CONTAINS THE VALUE OF $k"
  done; echo "value check done"' < "$WORK/migrate.all" > "$WORK/leakcheck.txt"
cat "$WORK/leakcheck.txt"
if grep -q 'OUTPUT CONTAINS' "$WORK/leakcheck.txt"; then fail "a secret value is in the output (key names above)"; fi

step "5. guard: the old image on the moved database must refuse to start"
dc up -d --no-deps srv-guard >/dev/null
for _ in $(seq 40); do [ "$(docker inspect -f '{{.State.Running}}' ovd-rh-srv-guard)" = true ] || break; sleep 1; done
[ "$(docker inspect -f '{{.State.Running}}' ovd-rh-srv-guard)" = false ] || fail "the old image still runs on the moved database"
GUARD_CODE="$(docker inspect -f '{{.State.ExitCode}}' ovd-rh-srv-guard)"
echo "exit code $GUARD_CODE; message:"; docker logs ovd-rh-srv-guard 2>&1 | tail -3 | cut -c1-400 | tee "$WORK/guard.txt" | sed 's/^/  /'
[ "$GUARD_CODE" != 0 ] || fail "guard exit code 0"
dc rm -sf srv-guard >/dev/null

step "6. NEW side: normal start"
dc up -d --no-deps srv-new >/dev/null
wait_up ovd-rh-srv-new
code="$(docker exec ovd-rh-srv-new sh -c 'curl -s -o /tmp/u -w "%{http_code}" -H "Authorization: Bearer $ADMIN_TOKEN" http://127.0.0.1:${ADMIN_PORT:-8081}/admin/users')"
echo "GET /admin/users -> $code"; [ "$code" = 200 ] || fail "the admin API answered $code"
docker exec ovd-rh-srv-new cat /tmp/u | jq -c '[.. | objects | select(has("handle")) | {id, handle}]' || true
fetch ovd-rh-srv-new "$WORK/new"
docker exec ovd-rh-client sh -c 'curl -s -m 5 -H "Authorization: Bearer $RH_TOKEN" http://ovd-rh-srv-new:8080/v1/me' > "$WORK/me.json" || true
echo "/v1/me: $(jq -c . "$WORK/me.json")"
jq -e '.handle == "owner" and (.userId == 1 or .id == 1)' "$WORK/me.json" >/dev/null || fail "/v1/me is not owner/1"

step "7. compare old and new"
bad=0; i=0
for ep in "${ENDPOINTS[@]}"; do
  i=$((i+1)); n=$(printf %02d "$i")
  for r in 1 2; do
    if ! diff -q "$WORK/old/r$r-$n.json" "$WORK/new/r$r-$n.json" >/dev/null; then
      bad=1; echo "DIFF round $r ${ep%%\?*}: first differing lines:"
      diff "$WORK/old/r$r-$n.json" "$WORK/new/r$r-$n.json" | head -4 | cut -c1-160 | sed 's/^/    /'
    fi
  done
done
if ! diff -q "$WORK/old/status-r2.txt" "$WORK/new/status-r2.txt" >/dev/null; then bad=1; echo "DIFF in HTTP statuses"; diff "$WORK/old/status-r2.txt" "$WORK/new/status-r2.txt"; fi
[ "$bad" = 0 ] && echo "IDENTICAL: ${#ENDPOINTS[@]} endpoints x 2 rounds, same statuses"

step "8. done; cleanup runs on exit"
exit "$bad"
