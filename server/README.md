# server

The self-hosted web service (spec §7.2): sync API, MCP server, scheduler, integrations, Telegram
bot, insights. One deployable with internal modules. Currently only the skeleton: configuration
loading and `GET /healthz`.

```bash
pnpm --filter @overdrive/server dev      # node --watch src/main.ts, http://127.0.0.1:8080
pnpm --filter @overdrive/server test
pnpm --filter @overdrive/server build    # tsc -> dist/, then: node dist/main.js
```

Node runs the TypeScript sources directly in development (type stripping), so source files use
`.ts` import extensions and avoid enums and parameter properties; `tsc` rewrites the extensions
for the build.

## Configuration

Environment variables, validated at startup in `src/config.ts`:

| Variable | Default | |
|---|---|---|
| `HOST` | `127.0.0.1` | The container image sets `0.0.0.0` |
| `PORT` | `8080` | |
| `LOG_LEVEL` | `info` | `fatal` … `trace`, `silent` |

Secrets for intervals.icu, Strava, Google, Telegram and the LLM endpoint live in the server
environment only, never in the repo.

## Deployment

`docker compose up -d --build` from the repository root builds `server/Dockerfile` (the build
context is the repo root) and starts PostgreSQL next to it. The service listens on
`127.0.0.1:8080`; the existing Caddy reverse proxy on the host fronts it:

```
overdrive.example.com {
    reverse_proxy 127.0.0.1:8080
}
```
