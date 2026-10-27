# PIDEA Console (frontend)

React 19 + TypeScript + Tailwind CSS (Vite) console for the PIDEA Spring Boot API. It talks to the existing
`/api/v1/**` endpoints only; no backend, worker or database changes.

## Run locally

```sh
# 1. start the stack (repo root) so the API is on http://localhost:8080
docker compose up -d

# 2. start the console
cd frontend
npm install
npm run dev          # http://127.0.0.1:5173
```

Sign in with an API user (`admin`, `operator`, `viewer`) and the password you configured via the
`PIDEA_*_PASSWORD_HASH` environment variables. Nothing is hard-coded.

Other scripts: `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`.

## Configuration

| Variable | Where | Default | Purpose |
| --- | --- | --- | --- |
| `PIDEA_API_TARGET` | shell or `frontend/.env` (dev server only) | `http://127.0.0.1:8080` | Where the Vite dev proxy forwards `/api` |

The browser always calls the **same origin** (`/api/...`). In development Vite proxies that to the API, so
**no CORS configuration is needed**. If the API runs on a non-default port (`PIDEA_API_PORT`), set `PIDEA_API_TARGET`.

## Authentication, HTTPS and CORS

- The API uses stateless HTTP Basic. The console keeps the credential **in JavaScript memory only**
  (`src/api/client.ts`); it is never written to localStorage, sessionStorage or cookies, so a page reload signs you out.
- Basic credentials are only base64-encoded, not encrypted. **Use HTTPS for any non-local deployment.**
- `npm run build` outputs static files in `dist/`. Serve them from the same origin as the API behind a TLS-terminating
  reverse proxy that routes `/api` to Spring Boot (preferred, no CORS). If the console is hosted on a different origin,
  the API must list that exact origin in `pidea.security.allowed-origins` (default: empty, i.e. denied). The API's CORS
  config allows only GET, POST, PATCH and the headers `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Correlation-Id`.
- There is no "who am I" endpoint. After sign-in the console probes two harmless requests against a nonexistent id
  (403 vs 404) to infer ADMIN / OPERATOR / VIEWER. This only controls which buttons are shown; **every call is still
  authorised by the server**, and 401/403 responses are shown clearly (401 returns you to sign-in).
- Rejected-record diagnostics are requested only for ADMIN/OPERATOR (the endpoint returns 403 for VIEWER).

## Routes

| Route | Purpose |
| --- | --- |
| `/` | Overview: source count, 24h job/record totals (from per-source health), latest runs |
| `/sources` | List/create sources (ADMIN), enable/disable (ADMIN, OPERATOR) |
| `/sources/:id` | Health, config, and the submit panel matching the source type (JSON push, CSV/JSON upload, HTTPS pull) |
| `/runs` | Job list with source, status and submitted-from/to filters (kept in the URL) |
| `/runs/:id` | Live status (polling), attempts, rejections, replay (FAILED) and cancel (PENDING/RETRY_SCHEDULED) |
| `/schedules` | List schedules; create/enable/disable (ADMIN) |

## API endpoints used

`GET/POST /api/v1/integration-sources`, `GET /{id}`, `PATCH /{id}/status`, `GET /{id}/health`;
`POST /api/v1/ingestion/jobs`, `POST /jobs/files`, `POST /sources/{id}/jobs`, `GET /jobs`, `GET /jobs/{id}`,
`GET /jobs/{id}/attempts`, `GET /jobs/{id}/errors`, `POST /jobs/{id}/replay`, `POST /jobs/{id}/cancel`;
`GET/POST /api/v1/integration-schedules`, `PATCH /{id}`.

Submissions send an `Idempotency-Key`; the same key is reused if the identical payload is retried after a failure.
