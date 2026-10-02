import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Burst test for the seat reservation service.
 *
 *   java Burst.java https://your-host
 *
 * Env: ADMIN_KEY (default dev-admin-key), TOTAL (default 20000), MAX_IN_FLIGHT (default = TOTAL), SEED.
 * Exit code 0 = every check passed, 1 = at least one failed.
 */
public class Burst {

    static final int PER_USER_LIMIT = 4;
    static final String ROWS = "ABCDEFGHIJKLMNOPQRST";   // 20 rows x 50 seats = 1000
    static final int SEATS_PER_ROW = 50;

    static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)     // separate connections, like separate buyers
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    static String base;
    static String adminKey;
    static long showId;

    /** One planned reserve request. */
    record Req(String scenario, String user, List<String> seats, String body, String key) {
        String token() { return TOKENS.get(user); }
    }

    /** One result. status = -1 means a transport error (timeout, connection refused, ...). */
    record Out(Req req, int status, String body, long ms, String error) {
        String field(String name) { return jsonField(body, name); }
        String reason() {
            if (status == -1) return "transport_error";
            if (status == 201) return "confirmed";
            if (status == 200) return "idempotent_replay";
            String e = field("error");
            return e != null ? e : "http_" + status;
        }
    }

    static final Map<String, String> TOKENS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java Burst.java <BASE_URL>");
            System.exit(2);
        }
        base = args[0].replaceAll("/+$", "");
        adminKey = env("ADMIN_KEY", "dev-admin-key");
        int total = Integer.parseInt(env("TOTAL", "20000"));
        int maxInFlight = Integer.parseInt(env("MAX_IN_FLIGHT", String.valueOf(total)));
        long seed = Long.parseLong(env("SEED", String.valueOf(System.nanoTime())));
        Random rnd = new Random(seed);
        String run = UUID.randomUUID().toString().substring(0, 6);   // keeps users and keys unique per run

        // ------------------------------------------------------------ show
        List<String> allSeats = new ArrayList<>();
        for (char row : ROWS.toCharArray()) {
            for (int n = 1; n <= SEATS_PER_ROW; n++) allSeats.add(row + "" + n);
        }
        Out created = send("POST", "/shows",
                "{\"name\":\"burst-" + run + "\",\"price_paise\":25000,\"per_user_limit\":" + PER_USER_LIMIT
                        + ",\"seats\":" + jsonArray(allSeats) + "}",
                Map.of("X-Admin-Key", adminKey), null);
        if (created.status() != 201) {
            System.err.println("could not create show: " + created.status() + " " + created.body() + " " + created.error());
            System.exit(1);
        }
        showId = Long.parseLong(created.field("id"));

        // ------------------------------------------------------------ plan
        List<Req> plan = new ArrayList<>();
        List<String> hotSeats = List.of("A1", "A2", "A3", "A4", "A5");
        for (String seat : hotSeats) {
            for (int i = 0; i < 500; i++) {
                plan.add(reserveReq("hot", run + "-hot-" + seat + "-" + i, List.of(seat), null, null));
            }
        }
        List<String> idemSeats = rowSeats("ST");                          // 100 seats
        for (int i = 0; i < 100; i++) {
            String user = run + "-idem-" + i;
            for (int copy = 0; copy < 5; copy++) {
                plan.add(reserveReq("idempotency", user, List.of(idemSeats.get(i)), null, "idem-" + i));
            }
        }
        for (int i = 0; i < 25; i++) {                                     // row R
            String user = run + "-conflict-" + i;
            plan.add(reserveReq("key_conflict", user, List.of("R" + (2 * i + 1)), null, "conf-" + i));
            plan.add(reserveReq("key_conflict", user, List.of("R" + (2 * i + 2)), null, "conf-" + i));
        }
        List<String> limitSeats = rowSeats("NOPQ");                        // 200 seats
        for (int u = 0; u < 20; u++) {
            for (int j = 0; j < 10; j++) {
                plan.add(reserveReq("per_user_limit", run + "-limit-" + u, List.of(limitSeats.get(u * 10 + j)), null, null));
            }
        }
        for (int i = 0; i < 10; i++) {                                     // M1..M10
            String seat = "M" + (i + 1);
            String body = "{\"seats\":[\"" + seat + "\"],\"user_id\":\"" + run + "-victim-" + i + "\"}";
            plan.add(reserveReq("spoof", run + "-spoof-" + i, List.of(seat), body, null));
        }

        // stampede pool: A6-A50, rows B-L, M11-M50; front rows weighted heavier
        List<String> pool = new ArrayList<>();
        Set<String> poolSet = new HashSet<>();
        List<String> weighted = new ArrayList<>();
        String stampedeRows = "ABCDEFGHIJKLM";
        for (int r = 0; r < stampedeRows.length(); r++) {
            char row = stampedeRows.charAt(r);
            int from = row == 'A' ? 6 : row == 'M' ? 11 : 1;
            for (int n = from; n <= SEATS_PER_ROW; n++) {
                String label = row + "" + n;
                pool.add(label);
                poolSet.add(label);
                for (int w = 0; w < stampedeRows.length() - r; w++) weighted.add(label);
            }
        }
        int stampede = Math.max(0, total - plan.size());
        for (int i = 0; i < stampede; i++) {
            String user = run + "-fan-" + rnd.nextInt(3000);
            String seat = weighted.get(rnd.nextInt(weighted.size()));
            List<String> seats = new ArrayList<>(List.of(seat));
            if (rnd.nextInt(5) == 0) {                                      // 20% ask for two adjacent seats
                String next = seat.charAt(0) + "" + (Integer.parseInt(seat.substring(1)) + 1);
                if (poolSet.contains(next)) seats.add(next);
            }
            plan.add(reserveReq("stampede", user, seats, null, null));
        }
        Collections.shuffle(plan, rnd);

        // ------------------------------------------------------------ tokens
        Set<String> users = new LinkedHashSet<>();
        plan.forEach(r -> users.add(r.user()));
        for (int i = 0; i < 10; i++) users.add(run + "-victim-" + i);
        System.out.printf("Burst against %s  (show %d, %d seats, limit %d, run %s, seed %d)%n",
                base, showId, allSeats.size(), PER_USER_LIMIT, run, seed);
        System.out.printf("Minting %d tokens...%n", users.size());
        mintTokens(users);

        // ------------------------------------------------------------ metrics before
        Map<String, Double> metricsBefore = scrapeMetrics();

        // ------------------------------------------------------------ fire
        System.out.printf("Firing %d requests (max in flight %d)...%n", plan.size(), maxInFlight);
        CountDownLatch gate = new CountDownLatch(1);
        Semaphore inFlight = new Semaphore(maxInFlight);
        AtomicBoolean firing = new AtomicBoolean(true);
        AtomicInteger polls = new AtomicInteger();
        ConcurrentLinkedQueue<String> pollProblems = new ConcurrentLinkedQueue<>();
        List<Out> outs = new ArrayList<>();
        long elapsedMs;

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Out>> futures = new ArrayList<>(plan.size());
            for (Req r : plan) {
                futures.add(exec.submit(() -> {
                    gate.await();
                    inFlight.acquire();
                    try {
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Authorization", "Bearer " + r.token());
                        if (r.key() != null) headers.put("Idempotency-Key", r.key());
                        return send("POST", "/shows/" + showId + "/reserve", r.body(), headers, r);
                    } finally {
                        inFlight.release();
                    }
                }));
            }
            Future<?> poller = exec.submit(() -> {
                while (firing.get()) {
                    Out o = send("GET", "/shows/" + showId, null, Map.of(), null);
                    polls.incrementAndGet();
                    if (o.status() != 200) {
                        pollProblems.add("poll returned " + (o.status() == -1 ? o.error() : o.status()));
                    } else {
                        int[] c = counts(o.body());
                        if (c[0] + c[1] + c[2] != c[3]) {
                            pollProblems.add("mid-burst " + c[0] + "+" + c[1] + "+" + c[2] + " != " + c[3]);
                        }
                    }
                    try { Thread.sleep(250); } catch (InterruptedException e) { return; }
                }
            });

            long t0 = System.nanoTime();
            gate.countDown();
            for (Future<Out> f : futures) outs.add(f.get());
            elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            firing.set(false);
            poller.get();
        }

        // ------------------------------------------------------------ after
        Out finalState = send("GET", "/shows/" + showId, null, Map.of(), null);
        Map<String, Double> metricsAfter = scrapeMetrics();
        report(outs, elapsedMs, finalState, metricsBefore, metricsAfter, polls.get(), pollProblems,
                hotSeats, run);
    }

    // ================================================================ report & checks

    static void report(List<Out> outs, long elapsedMs, Out finalState, Map<String, Double> mBefore,
                       Map<String, Double> mAfter, int polls, ConcurrentLinkedQueue<String> pollProblems,
                       List<String> hotSeats, String run) {
        System.out.printf("%nRequests: %,d   Duration: %.1fs   Throughput: %,.0f req/s%n",
                outs.size(), elapsedMs / 1000.0, outs.size() * 1000.0 / Math.max(1, elapsedMs));

        System.out.println("\nOutcomes");
        Map<String, Long> outcomes = new TreeMap<>();
        for (Out o : outs) {
            String k = (o.status() == -1 ? "ERR" : String.valueOf(o.status())) + " " + o.reason();
            outcomes.merge(k, 1L, Long::sum);
        }
        outcomes.forEach((k, v) -> System.out.printf("  %-34s %,8d%n", k, v));
        Map<String, Long> errors = outs.stream().filter(o -> o.status() == -1)
                .collect(Collectors.groupingBy(Out::error, TreeMap::new, Collectors.counting()));
        errors.forEach((k, v) -> System.out.printf("    transport: %-60s %,d%n", truncate(k, 60), v));

        List<Long> lat = outs.stream().filter(o -> o.status() != -1).map(Out::ms).sorted().toList();
        if (!lat.isEmpty()) {
            System.out.printf("%nLatency  p50 %dms  p95 %dms  p99 %dms  max %dms%n",
                    pct(lat, 50), pct(lat, 95), pct(lat, 99), lat.get(lat.size() - 1));
        }

        System.out.println("\nChecks");
        List<Boolean> results = new ArrayList<>();

        long fiveXx = outs.stream().filter(o -> o.status() >= 500).count();
        long pollFiveXx = pollProblems.stream().filter(p -> p.startsWith("poll returned 5")).count();
        results.add(check("zero 5xx", fiveXx == 0 && pollFiveXx == 0,
                fiveXx + " reserve 5xx, " + pollFiveXx + " poll 5xx"));

        long transport = outs.stream().filter(o -> o.status() == -1).count();
        results.add(check("zero transport errors", transport == 0, transport + " (client-side; see breakdown above)"));

        long unexpected = outs.stream().filter(o -> o.status() != -1 && o.status() != 200
                && o.status() != 201 && o.status() != 409 && o.status() < 500).count();
        results.add(check("only 200/201/409", unexpected == 0, unexpected + " other 4xx"));

        // hot seats: exactly one winner each
        boolean hotOk = true;
        StringBuilder hotDetail = new StringBuilder();
        for (String seat : hotSeats) {
            List<Out> forSeat = outs.stream().filter(o -> o.req().scenario().equals("hot")
                    && o.req().seats().get(0).equals(seat)).toList();
            long wins = forSeat.stream().filter(o -> o.status() == 201).count();
            long taken = forSeat.stream().filter(o -> "seat_taken".equals(o.reason())).count();
            hotOk &= wins == 1 && taken == forSeat.size() - 1;
            hotDetail.append(seat).append(": ").append(wins).append("/").append(forSeat.size()).append("  ");
        }
        results.add(check("hot seats: exactly one 201 each", hotOk, hotDetail.toString().trim()));

        // no seat in two 201 responses
        Map<String, Integer> soldTo = new HashMap<>();
        for (Out o : outs) {
            if (o.status() == 201) o.req().seats().forEach(s -> soldTo.merge(s, 1, Integer::sum));
        }
        long doubleSold = soldTo.values().stream().filter(n -> n > 1).count();
        results.add(check("no seat sold twice", doubleSold == 0, doubleSold + " seats in more than one 201"));

        // reconciliation, final and during
        int[] c = finalState.status() == 200 ? counts(finalState.body()) : new int[]{-1, -1, -1, -2};
        results.add(check("reconciliation after burst", c[0] + c[1] + c[2] == c[3],
                String.format("available %d + held %d + confirmed %d = %d (total %d)", c[0], c[1], c[2], c[0] + c[1] + c[2], c[3])));
        results.add(check("API confirmed == seats in 201s", c[2] == soldTo.size(),
                "API " + c[2] + ", from 201 responses " + soldTo.size()));
        long midBurst = pollProblems.stream().filter(p -> p.startsWith("mid-burst")).count();
        results.add(check("reconciliation during burst", midBurst == 0 && polls > 0,
                polls + " polls, " + midBurst + " mismatches"));

        // idempotency
        Map<String, List<Out>> idem = byUser(outs, "idempotency");
        long idemBad = idem.values().stream().filter(list -> {
            long c201 = list.stream().filter(o -> o.status() == 201).count();
            long c200 = list.stream().filter(o -> o.status() == 200).count();
            long ids = list.stream().map(o -> o.field("reservation_id")).filter(x -> x != null).distinct().count();
            return !(c201 == 1 && c200 == list.size() - 1 && ids == 1);
        }).count();
        results.add(check("idempotent retries: one reservation per key", idemBad == 0,
                idem.size() + " keys, " + idemBad + " wrong"));

        Map<String, List<Out>> conflict = byUser(outs, "key_conflict");
        long conflictBad = conflict.values().stream().filter(list ->
                !(list.stream().filter(o -> o.status() == 201).count() == 1
                        && list.stream().filter(o -> "idempotency_conflict".equals(o.reason())).count() == 1)).count();
        results.add(check("same key, different body -> 409", conflictBad == 0,
                conflict.size() + " keys, " + conflictBad + " wrong"));

        // per-user limit: exact for the dedicated scenario, upper bound for everyone
        Map<String, List<Out>> limit = byUser(outs, "per_user_limit");
        long limitBad = limit.values().stream()
                .filter(list -> list.stream().filter(o -> o.status() == 201).count() != PER_USER_LIMIT).count();
        Map<String, Integer> seatsPerUser = new HashMap<>();
        for (Out o : outs) {
            if (o.status() == 201) seatsPerUser.merge(o.req().user(), o.req().seats().size(), Integer::sum);
        }
        int maxHeld = seatsPerUser.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        results.add(check("per-user limit holds", limitBad == 0 && maxHeld <= PER_USER_LIMIT,
                limit.size() + " users at exactly " + PER_USER_LIMIT + " (" + limitBad + " wrong), max held by anyone " + maxHeld));

        // spoofing: body user_id ignored; the named victim cannot cancel
        List<Out> spoofs = outs.stream().filter(o -> o.req().scenario().equals("spoof")).toList();
        long spoofBad = 0;
        for (Out o : spoofs) {
            if (o.status() != 201) { spoofBad++; continue; }
            String victim = run + "-victim-" + o.req().user().substring(o.req().user().lastIndexOf('-') + 1);
            Out cancel = send("POST", "/reservations/" + o.field("reservation_id") + "/cancel", "",
                    Map.of("Authorization", "Bearer " + TOKENS.get(victim)), null);
            if (cancel.status() != 404) spoofBad++;
        }
        results.add(check("identity from token only", spoofBad == 0,
                spoofs.size() + " spoofed requests, " + spoofBad + " wrong"));

        // metrics agree with what clients saw
        long count201 = outs.stream().filter(o -> o.status() == 201).count();
        if (mBefore.containsKey("confirmed") && mAfter.containsKey("confirmed")) {
            double delta = mAfter.get("confirmed") - mBefore.get("confirmed");
            results.add(check("metrics: confirmed counter == 201s", Math.round(delta) == count201,
                    "counter +" + Math.round(delta) + ", 201s " + count201 + " (other traffic during the run skews this)"));
        } else {
            results.add(check("metrics: confirmed counter == 201s", false, "could not read /actuator/prometheus"));
        }

        boolean all = results.stream().allMatch(b -> b);
        System.out.println(all ? "\nRESULT: ALL CHECKS PASSED" : "\nRESULT: FAILED");
        if (!pollProblems.isEmpty()) {
            System.out.println("First poll problems: " + pollProblems.stream().limit(5).toList());
        }
        System.exit(all ? 0 : 1);
    }

    // ================================================================ helpers

    static Req reserveReq(String scenario, String user, List<String> seats, String body, String key) {
        return new Req(scenario, user, seats, body != null ? body : "{\"seats\":" + jsonArray(seats) + "}", key);
    }

    static List<String> rowSeats(String rows) {
        List<String> out = new ArrayList<>();
        for (char row : rows.toCharArray()) {
            for (int n = 1; n <= SEATS_PER_ROW; n++) out.add(row + "" + n);
        }
        return out;
    }

    static void mintTokens(Set<String> users) throws Exception {
        Semaphore limit = new Semaphore(200);
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (String u : users) {
                fs.add(exec.submit(() -> {
                    limit.acquire();
                    try {
                        for (int attempt = 0; attempt < 3; attempt++) {
                            Out o = send("POST", "/auth/token", "{\"user\":\"" + u + "\"}", Map.of(), null);
                            if (o.status() == 200 && o.field("token") != null) {
                                TOKENS.put(u, o.field("token"));
                                return null;
                            }
                        }
                        throw new IllegalStateException("could not mint token for " + u);
                    } finally {
                        limit.release();
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        }
    }

    static Out send(String method, String path, String body, Map<String, String> headers, Req req) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT);
        headers.forEach(b::header);
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            b.GET();
        }
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Out(req, r.statusCode(), r.body(), (System.nanoTime() - t0) / 1_000_000, null);
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return new Out(req, -1, "", (System.nanoTime() - t0) / 1_000_000, msg);
        }
    }

    static Map<String, Double> scrapeMetrics() {
        Out o = send("GET", "/actuator/prometheus", null, Map.of(), null);
        Map<String, Double> m = new HashMap<>();
        if (o.status() != 200) return m;
        Matcher matcher = Pattern.compile("(?m)^reservations_confirmed_total(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)").matcher(o.body());
        if (matcher.find()) m.put("confirmed", Double.parseDouble(matcher.group(1)));
        return m;
    }

    static Map<String, List<Out>> byUser(List<Out> outs, String scenario) {
        Map<String, List<Out>> m = new LinkedHashMap<>();
        for (Out o : outs) {
            if (o.req().scenario().equals(scenario)) m.computeIfAbsent(o.req().user(), k -> new ArrayList<>()).add(o);
        }
        return m;
    }

    /** [available, held, confirmed, total_seats] from a GET /shows/{id} body. */
    static int[] counts(String body) {
        return new int[]{intField(body, "available"), intField(body, "held"),
                intField(body, "confirmed"), intField(body, "total_seats")};
    }

    static int intField(String json, String name) {
        String v = jsonField(json, name);
        return v == null ? Integer.MIN_VALUE / 4 : Integer.parseInt(v);
    }

    /** Flat-field reader for our own responses; keeps this file dependency-free. */
    static String jsonField(String json, String name) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(\"([^\"]*)\"|(-?[0-9]+)|null)").matcher(json);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(3);
    }

    static String jsonArray(List<String> items) {
        return items.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    static long pct(List<Long> sorted, int p) {
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(i, sorted.size() - 1)));
    }

    static boolean check(String name, boolean ok, String detail) {
        System.out.printf("  %-44s %s  %s%n", name, ok ? "PASS" : "FAIL", detail);
        return ok;
    }

    static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}