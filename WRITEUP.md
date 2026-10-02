# Write-up

# I want to disclose upfront that a substantial part of the code and documentation was drafted with an AI model.

## 1. The atomic decision

Every reservation is decided by **one PostgreSQL transaction**. Postgres is the single source of truth:
no cache, queue or second datastore takes part in the decision, so there is nothing to keep in sync and
nothing that can disagree with the database.

Inside the transaction, in this fixed order:

1. **Claim the idempotency key.**
   `INSERT INTO reservations (..., status='pending') ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id`.
   No row back means the key exists: same request hash → replay the original; different hash → 409.
2. **Per-user limit.** A conditional increment on one row per (show, user):
   `UPDATE user_show_quota SET used = used + n WHERE ... AND used + n <= limit`. Zero rows updated → 409.
   Parallel requests from one user serialize on that row, so the limit can't be overshot.
3. **Seats, last.** `SELECT ... WHERE label = ANY(:sorted) ORDER BY label FOR UPDATE`, check every seat is
   `available`, then a conditional `UPDATE seats SET status='confirmed' ... WHERE status='available'`.
   All-or-nothing: if any seat is taken, the whole transaction rolls back, including the pending
   reservation row and the quota increment.

The guarantee doesn't depend on application code being careful. It rests on three database facts: a row
lock admits one writer at a time, a unique index admits one key, and a conditional update changes only
rows still matching its condition. The schema adds a `CHECK` that a seat is either fully free or fully
owned, as a last line of defence.

**Fast decline before the transaction.** A plain read checks whether any requested seat is already
sold, and declines immediately if so. This is safe because it can only decline, never grant; the
grant is always the locked update above. It keeps hundreds of losers from queueing on a hot seat's row
lock, which is what turns a hot seat into timeouts and 5xx. Before declining, it re-checks the
idempotency key, so a retry of a request that just committed gets its replay rather than a 409.

**Why not `NOWAIT` / `SKIP LOCKED` on seats.** If 499 waiters were turned away the moment they saw
the lock and the holder then rolled back (because of its user's limit, say), the seat would end up
with zero winners, breaking "exactly one 201". Waiting for the lock costs milliseconds and keeps the
outcome exact. `SKIP LOCKED` is correct for interchangeable items ("any free ticket"), not for
assigned seats; it would become correct here for a "best available" feature.

**Read Committed is enough.** Correctness comes from row locks, the unique index and conditional
updates, not from snapshot isolation, so the default isolation level is sufficient and avoids
serialization-failure retries under load.

## 2. Deadlock avoidance

All transactions take locks in the same global order:
**reservation/idempotency row → user quota row → seats sorted by label.**
Cancel follows the same order (reservation row `FOR UPDATE`, quota, then seats locked in sorted order
before release). With a single global order, no two transactions can each hold a lock the other waits
for. `HotSeatConcurrencyTest` fires overlapping two-seat requests with the seats listed in both orders
to prove it.

If Postgres still reports a deadlock (`40P01`) or serialization failure (`40001`), the whole
transaction is retried up to 3 times instead of surfacing as a 5xx.

## 3. Idempotency storage

The idempotency record **is** the reservation row: `UNIQUE (user_id, idempotency_key)` plus a SHA-256
`request_hash` of show id and sorted seat labels (so `["A2","A1"]` and `["A1","A2"]` are the same
request). Keys are scoped per user, so one user's key can never collide with or reveal another's.

- Concurrent duplicates: the second `INSERT ... ON CONFLICT` waits for the first transaction to commit,
  then sees the row and replays. Twenty parallel identical requests produce one reservation.
- A declined request rolls back, so its key isn't stored, and a retry is evaluated fresh.
- A replay returns **200** with `Idempotent-Replayed: true`, not 201, so "exactly one 201 per seat" stays
  true even when the winner retries.
- Keys are optional: a client that sends none just doesn't get idempotency, rather than a 400 that
  would look like neither success nor a normal decline.

Not done: key expiry. Keys live as long as their reservation, which is right for an order history;
a busy system would add a retention window.

## 4. Holds and expiry

I chose the **explicit-cancel model**: reserve goes straight to `confirmed`, and cancel releases it.
So `held` is always 0, and it's still reported because the invariant is defined over it.

TTL holds would add a background expiry worker and race cases (a confirm arriving as its hold
expires) for no gain in what the exercise measures. How I'd add them: a `held` status with
`expires_at`; reserve creates holds, a confirm endpoint flips them, and a sweeper releases expired
holds with `UPDATE ... WHERE status='held' AND expires_at < now()` using `FOR UPDATE SKIP LOCKED` (safe
there: any expired hold will do), returning quota in the same transaction.

Cancel details: the owner check happens after locking the reservation row, so two concurrent cancels
serialize and the second sees `cancelled`; seats are released only `WHERE reservation_id = ? AND
status = 'confirmed'`, so a cancel can never free a seat now belonging to someone else; and the freed
count must equal the reservation's seat count or the transaction aborts, since a mismatch would mean
the invariant is already broken.

## 5. CAP stance under partition

**Consistency over availability.** There is one Postgres primary. If the app can't reach it,
readiness reports 503 and the instance stops being considered ready; the app does not guess, cache
or queue sales. Refusing a sale during a partition is recoverable; selling a seat twice is not.

The app runs as a single instance so metrics counters reconcile exactly with database state. The app
holds no state of its own, so more instances behind nginx would work for correctness (the database
decides everything); counters would then be summed across instances.

## 6. What pages me at 2am

| Alert | Why |
|---|---|
| any 5xx (`http_server_requests_seconds_count{status=~"5.."}` increasing) | the zero-5xx promise is broken; logs with the request id show why |
| invariant mismatch (`seats` gauge sum per show ≠ that show's `total_seats`) | data corruption; stop sales before it spreads |
| readiness flapping | DB connectivity problems, or the instance repeatedly dropping out |
| `hikaricp_connections_pending` sustained high, or `hikaricp_connections_timeout_total` increasing | the next requests become timeouts or 5xx |
| p99 latency spike | precedes timeouts on the client side |
| `unhandled exception` log lines | something reached the catch-all handler: a bug |
| certificate expiry < 14 days | certbot renewal failed |

Not paging: 409s. Thousands of `seat_taken` declines during an on-sale are the system working.

## 7. What the load tests found

The burst script found four problems, none of them in the reservation logic, which passed every
check on every request that reached the server:

1. **Client behind NAT.** From GitHub Codespaces, connections timed out at a steady ~300 new
   connections/s even at 500 in flight. Codespaces' outbound NAT caps concurrent connections to one
   destination. The server never saw those connections. Fix: run the client from a host with a direct IP.
2. **Client file descriptors on macOS.** The JVM on macOS caps itself near 10,240 open files.
   `burst.sh` now raises the limit and passes `-XX:-MaxFDLimit`.
3. **nginx `worker_connections 768`** (the distribution default): with two connections per proxied
   request, about 6k simultaneous requests at most. Raised to 16,384.
4. **One nginx worker taking almost every connection.** Without `reuseport`, workers race for one shared
   listening socket; with `multi_accept` the winner accepts everything pending, saturating one core and
   then closing connections beyond its limit. Fix: `reuseport` so the kernel spreads connections
   across all 16 workers, plus a 16,384 backlog.

After these, 20,000 requests released at once passed every check with zero 5xx and zero transport
errors.

Open item: latency in that run was p50 ~5s, p99 ~10.5s, with the burst client running on the same
machine as the service. `[TODO: compare app-side http_server_requests_seconds with client-side latency
to locate the queueing: client, nginx/TLS, or database.]` The JDK HTTP client runs all connections
through one I/O thread, so my first suspect is the client.

## 8. How I used AI

I used Claude throughout, as a design partner and to draft code, and treated its output as a first
draft to question, not an answer.

What it did: proposed the transaction design and lock order, drafted most of the Java, SQL, tests,
burst script and these documents, and helped interpret burst results.

Where I pushed back or changed direction:
- **Scope.** I asked whether it was adding unrequested features. That audit removed a temporary
  `X-User-Id` header step and moved JWT auth before reserve, so reserve was written once with the real
  identity source.
- **Flyway.** I questioned needing a migration tool for one schema file; kept it because a fresh clone
  must create its own tables, and the alternative (`init.sql`) silently skips on an existing volume.
- **Structure.** I asked what `ShowRow`/`SeatRow` were and why they existed next to the response DTOs;
  this surfaced that one of them duplicated a DTO.
- **Infrastructure.** It proposed Caddy; my server already ran nginx with a certbot certificate, so I
  used that and committed the config.
- **Load debugging.** Its first suggestion (`multi_accept on`) made the worker imbalance worse; the
  burst exposed it and the fix was `reuseport`. Running the client from Codespaces, then from the server
  itself, is what separated client limits from server limits.

`[TODO: add your own examples, in your words: anything you rewrote, verified independently, or
decided differently.]`

## 9. What's next

- **Locate the remaining latency** (section 7), most likely by spreading the burst client over several
  HTTP clients or machines.
- **TTL holds** as described in section 4.
- **Payments and notifications** after a confirmed reservation via a transactional outbox: write the
  event in the same transaction, publish asynchronously. Keeps the synchronous 201/409 contract.
- **Read scaling** for `GET /shows/{id}`: a short-lived cache or read replica; it's the endpoint a
  waiting crowd polls.
- **Very large on-sales:** a virtual waiting room in front, admitting buyers at the rate the database
  sustains, instead of letting 100k connections arrive at once.
- **Real identity provider** replacing `/auth/token`; per-user rate limiting at the edge.
- **Natural seat ordering** (`A2` before `A10`) and "best available" allocation via a `V2` migration
  with row/number columns.
- **Alert rules** for section 6 as code, and a Grafana dashboard.