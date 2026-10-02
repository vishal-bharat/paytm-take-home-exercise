package in.me.vishal.seats;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyTest extends IntegrationTestBase {

    @Test
    void parallelRetriesWithSameKey_createOneReservation() throws Exception {
        long show = createShow(4, "A1", "A2");
        String t = token(unique("idem"));

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(() -> reserve(t, show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k-1\"}"));
        }
        List<Response> responses = fireTogether(calls);

        assertThat(byStatus(responses)).isEqualTo(Map.of(201, 1L, 200, 19L));
        assertThat(responses.stream().map(r -> r.field("reservation_id")).distinct()).hasSize(1);
        assertThat(counts(show)).containsExactly(1, 0, 1, 2);
    }

    @Test
    void sameKeyDifferentBody_isRejected() {
        long show = createShow(4, "A1", "A2");
        String t = token(unique("idem"));

        Response first = reserve(t, show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k-2\"}");
        Response other = reserve(t, show, "{\"seats\":[\"A2\"],\"idempotency_key\":\"k-2\"}");

        assertThat(first.status()).isEqualTo(201);
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.field("error")).isEqualTo("idempotency_conflict");
        assertThat(counts(show)).containsExactly(1, 0, 1, 2);
    }

    @Test
    void retryAfterSuccess_replaysOriginal_evenThoughSeatIsNowTaken() {
        long show = createShow(4, "A1");
        String t = token(unique("idem"));

        Response first = reserve(t, show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k-3\"}");
        Response retry = reserve(t, show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k-3\"}");

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(200);   // not 409 seat_taken
        assertThat(retry.field("reservation_id")).isEqualTo(first.field("reservation_id"));
    }
}