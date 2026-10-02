# Seat Reservation Service

# I want to disclose upfront that a substantial part of the code and documentation was drafted with an AI model.

A JSON HTTP API that sells assigned seats under heavy concurrency. Every hot seat goes to exactly
one buyer, nobody gets a 5xx, and `available + held + confirmed == total_seats` holds during and
after a burst.

**Live:** https://paytm.vishal.me.in

| | |
|---|---|
| Health (liveness) | https://paytm.vishal.me.in/actuator/health/liveness |
| Health (readiness, checks the DB) | https://paytm.vishal.me.in/actuator/health/readiness |
| Prometheus metrics | https://paytm.vishal.me.in/actuator/prometheus |
| Live logs (read-only, app container only) | https://paytm.vishal.me.in/logs |

Stack: Java 21, Spring Boot 4, PostgreSQL 17, plain JDBC (`JdbcTemplate`), Flyway, Micrometer,
Docker Compose, nginx + Let's Encrypt.

Design decisions are explained in [WRITEUP.md](WRITEUP.md).

---

## How to authenticate

The exercise doesn't define how tokens are issued, so the service includes a **test identity provider**
that signs a JWT for any user name. It stands in for a real IdP (Keycloak, Auth0, ...), which would
replace it in production.

```bash
curl -s -X POST https://paytm.vishal.me.in/auth/token \
  -H 'Content-Type: application/json' -d '{"user":"u123"}'
# {"token":"eyJhbGciOi...","expires_in":7200}
```

Send it as `Authorization: Bearer <token>`. The user id is taken **only** from the token's signed `sub`
claim; any `user_id` in a request body is ignored. Tokens are valid for 2 hours.

Creating shows is an admin action and needs the `X-Admin-Key` header.
**Admin key:** `[TODO: shared in the submission email]`

Access rules (deny by default):

| Endpoint | Access |
|---|---|
| `POST /auth/token`, `GET /shows/{id}`, `/actuator/**`, `/logs` | public |
| `POST /shows` | `X-Admin-Key` |
| everything else | valid bearer token |

---

## API

Money is always in integer paise.

### Create a show (admin)

```bash
curl -s -X POST https://paytm.vishal.me.in/shows \
  -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN_KEY" \
  -d '{"name":"Evening show","price_paise":25000,"per_user_limit":4,"seats":["A1","A2","A3","B1"]}'
# 201 {"id":1,"name":"Evening show","price_paise":25000,"per_user_limit":4,"total_seats":4}
```

`per_user_limit` is optional (default 4). `name` is optional.

### Show state

```bash
curl -s https://paytm.vishal.me.in/shows/1
# {"id":1,...,"total_seats":4,
#  "counts":{"available":3,"held":0,"confirmed":1},
#  "seats":[{"label":"A1","status":"confirmed"},{"label":"A2","status":"available"},...]}
```

Counts are computed from the same rows as the seat list, so a single response is always internally
consistent, even mid-burst. Seat owners are never exposed.

### Reserve

```bash
curl -s -X POST https://paytm.vishal.me.in/shows/1/reserve \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-42' \
  -d '{"seats":["A2","A3"]}'
# 201 {"reservation_id":"9b1c...","show_id":1,"seats":["A2","A3"],"amount_paise":50000,"status":"confirmed"}
```

- **All-or-nothing:** if any requested seat is taken, nothing is reserved.
- **Idempotency key:** `Idempotency-Key` header or `idempotency_key` body field (both may be sent if
  equal). Optional: without one, the request simply isn't idempotent.
- A retry with the same key and same seats returns the original reservation with **200** and
  `Idempotent-Replayed: true`. Only the first, real reservation returns 201.

### Cancel

```bash
curl -s -X POST https://paytm.vishal.me.in/reservations/9b1c.../cancel -H "Authorization: Bearer $TOKEN"
# 200 {...,"status":"cancelled"}
```

Frees the seats and gives the user's quota back. Only the owner can cancel; anyone else gets 404.
Cancelling again returns the same result.

### Status codes

| Status | `error` | Meaning |
|---|---|---|
| 201 | | reservation confirmed |
| 200 | | idempotent replay, or cancel |
| 409 | `seat_taken` | at least one requested seat is already sold |
| 409 | `per_user_limit` | would exceed the show's per-user limit |
| 409 | `idempotency_conflict` | key reused with a different request |
| 400 | `invalid_request` | malformed body, unknown seat, duplicate labels |
| 401 | `unauthorized` | missing/invalid/expired token, or missing admin key |
| 404 | `show_not_found` / `reservation_not_found` | |

Every response carries an `X-Request-Id` header (yours if you send a safe one, otherwise generated).
Every log line of that request carries the same id.

---

## Burst test

One command creates a fresh show, mints ~5,700 user tokens, fires ~20,000 reserve requests released
at the same instant, polls the reconciliation invariant during the burst, then verifies every rule.

```bash
ADMIN_KEY='...' ./burst/burst.sh https://paytm.vishal.me.in
```

Uses a local JDK 21+, or falls back to the `eclipse-temurin:21` Docker image (no install needed).
Optional env: `TOTAL` (default 20000), `MAX_IN_FLIGHT`, `SEED`. Exit code 0 = all checks passed.

| Scenario | Requests | Expected |
|---|---|---|
| Hot seats A1–A5 | 5 × 500 different users | exactly one 201 per seat, rest 409 `seat_taken` |
| Idempotent retries | 100 users × 5 identical, in parallel | one 201 + four 200s, one reservation per key |
| Same key, different seats | 25 users × 2, in parallel | one 201 + one 409 `idempotency_conflict` |
| Per-user limit | 20 users × 10 parallel | exactly 4 × 201 each |
| Spoofed `user_id` in body | 10 | reservation belongs to the token's user; the named victim can't cancel it |
| Stampede | the rest (~16.7k), front rows weighted | 201 / 409 only, zero 5xx |

**Run it from a Linux host with a direct public IP.** Clients behind NAT (home routers, GitHub
Codespaces, many cloud dev environments) cap concurrent outbound connections well below 20k. That
shows up as client-side `HTTP connect timed out` errors, not as server errors (see WRITEUP, "What the
load tests found").

Result against the live service (20,000 requests, all in flight at once):

```
Outcomes
  200 idempotent_replay                   400
  201 confirmed                           779
  409 idempotency_conflict                 25
  409 per_user_limit                      120
  409 seat_taken                       18,676

Checks
  zero 5xx                                     PASS  0 reserve 5xx, 0 poll 5xx
  zero transport errors                        PASS  0
  only 200/201/409                             PASS  0 other 4xx
  hot seats: exactly one 201 each              PASS  A1: 1/500  A2: 1/500  A3: 1/500  A4: 1/500  A5: 1/500
  no seat sold twice                           PASS  0 seats in more than one 201
  reconciliation after burst                   PASS  available 146 + held 0 + confirmed 854 = 1000
  API confirmed == seats in 201s               PASS  API 854, from 201 responses 854
  reconciliation during burst                  PASS  5 polls, 0 mismatches
  idempotent retries: one reservation per key  PASS  100 keys, 0 wrong
  same key, different body -> 409              PASS  25 keys, 0 wrong
  per-user limit holds                         PASS  20 users at exactly 4 (0 wrong), max held by anyone 4
  identity from token only                     PASS  10 spoofed requests, 0 wrong
  metrics: confirmed counter == 201s           PASS  counter +779, 201s 779

RESULT: ALL CHECKS PASSED
```

---

## Observability

**Metrics** (`/actuator/prometheus`):

| Metric | Meaning |
|---|---|
| `reservations_confirmed_total` | reservations confirmed (each 201) |
| `reservations_declined_total{reason}` | 409s by `seat_taken`, `per_user_limit`, `idempotency_conflict` |
| `reservations_replayed_total` | idempotent replays (200) |
| `reservations_cancelled_total` | cancellations |
| `seats{show,status}` | seats per show by `available` / `held` / `confirmed`, read from the database every 5s |
| `http_server_requests_seconds` | latency per endpoint and status (Spring) |
| `hikaricp_connections_*` | DB pool usage and waits (Hikari) |

**Logs:** structured JSON on stdout (Spring Boot structured logging, logstash format). Each request
logs one access line with method, path, status and duration, plus `request_id` and `user_id` from the
log context. View live at `/logs` or with `docker compose logs -f app`.

**Health:** liveness reports the process; readiness includes the database and returns 503 when
Postgres is unreachable (fails closed), recovering automatically when it returns.

---

## Run locally

```bash
docker compose up --build          # app on http://localhost:8080, Postgres, log viewer
./gradlew test                     # integration tests against a real Postgres (needs Docker)
./burst/burst.sh http://localhost:8080
```

Local defaults: admin key `dev-admin-key`, a dev JWT secret. Production values come from `.env`
(see `.env.example`), which is never committed.

Tests (Testcontainers, real Postgres, real HTTP):

| Test | Proves |
|---|---|
| `HotSeatConcurrencyTest` | 500 users on one seat → exactly one 201; overlapping multi-seat requests in both orders → no deadlock, no double sale |
| `IdempotencyTest` | 20 parallel retries → one reservation; same key + different body → 409; retry after success → replay |
| `PerUserLimitTest` | 10 parallel requests at limit 4 → exactly 4; cancel gives quota back |
| `AuthSpoofingTest` | no/garbage/forged token → 401; body `user_id` ignored; only the owner can cancel |

---

## Deployment

- `compose.yaml`: app, Postgres, Dozzle (log viewer). App and Dozzle bind to `127.0.0.1` only.
- Reverse proxy: the host's **nginx** (it already served this machine), config in
  [`deploy/nginx/seats.conf`](deploy/nginx/seats.conf). TLS certificate from Let's Encrypt via certbot.
- Tuning needed for a 20k-connection burst:
  - nginx: `worker_connections 16384`, `worker_rlimit_nofile 65535`, `listen ... reuseport backlog=16384`
  - kernel: `net.core.somaxconn`, `net.ipv4.tcp_max_syn_backlog` raised to 16384
  - app: Tomcat `max-connections 20000`, Hikari pool 40 with a 30s wait, virtual threads
  - nginx `proxy_read_timeout 60s`, longer than the app's pool wait, so a queued request never becomes a 504
- Cold start to ready: `[TODO: measure with docker compose down && up -d]`

```bash
cp .env.example .env               # set POSTGRES_PASSWORD, JWT_SECRET (openssl rand -hex 32), ADMIN_KEY
docker compose up -d --build
```

---

## Repository layout

```
src/main/java/in/me/vishal/seats/
  api/            controllers, DTOs, exception → HTTP mapping
  domain/         ReservationService (the atomic path), ShowService, decline reasons
  repo/           all SQL (JdbcTemplate)
  security/       JWT issue/verify, deny-by-default auth filter
  observability/  request ids + access log, metrics
src/main/resources/db/migration/V1__schema.sql
src/test/...      Testcontainers integration tests
burst/            Burst.java + burst.sh
deploy/nginx/     reverse proxy config
```
