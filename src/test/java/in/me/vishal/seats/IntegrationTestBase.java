package in.me.vishal.seats;


import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    protected static final String ADMIN_KEY = "dev-admin-key";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    protected record Response(int status, String body) {
        String field(String name) {
            return jsonField(body, name);
        }
    }

    protected Response post(String path, String json, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        return send(b.build());
    }

    protected Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET().build());
    }

    private static Response send(HttpRequest request) {
        try {
            HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected long createShow(int perUserLimit, String... seats) {
        String seatJson = java.util.Arrays.stream(seats).map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
        Response r = post("/shows",
                "{\"price_paise\":25000,\"per_user_limit\":" + perUserLimit + ",\"seats\":[" + seatJson + "]}",
                Map.of("X-Admin-Key", ADMIN_KEY));
        if (r.status() != 201) {
            throw new IllegalStateException("create show failed: " + r);
        }
        return Long.parseLong(r.field("id"));
    }

    protected String token(String user) {
        Response r = post("/auth/token", "{\"user\":\"" + user + "\"}", Map.of());
        return r.field("token");
    }

    protected Response reserve(String token, long showId, String json) {
        return post("/shows/" + showId + "/reserve", json, Map.of("Authorization", "Bearer " + token));
    }

    protected Response cancel(String token, String reservationId) {
        return post("/reservations/" + reservationId + "/cancel", "", Map.of("Authorization", "Bearer " + token));
    }

    /** [available, held, confirmed, total] from GET /shows/{id}. */
    protected int[] counts(long showId) {
        Response r = get("/shows/" + showId);
        return new int[]{
                Integer.parseInt(r.field("available")),
                Integer.parseInt(r.field("held")),
                Integer.parseInt(r.field("confirmed")),
                Integer.parseInt(r.field("total_seats"))};
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static <T> List<T> fireTogether(List<Callable<T>> calls) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(exec.submit(() -> {
                    gate.await();
                    return call.call();
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    protected static Map<Integer, Long> byStatus(List<Response> responses) {
        return responses.stream().collect(Collectors.groupingBy(Response::status, Collectors.counting()));
    }

    protected static <K> Map<K, Long> countBy(List<Response> responses, Function<Response, K> key) {
        return responses.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
    }

    static String jsonField(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(\"([^\"]*)\"|(-?[0-9]+)|null)")
                .matcher(json);
        if (!m.find()) {
            return null;
        }
        return m.group(2) != null ? m.group(2) : m.group(3);
    }
}
