package in.me.vishal.seats;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class PerUserLimitTest extends IntegrationTestBase {

    @Test
    void tenParallelRequestsFromOneUser_atMostLimitSucceed() throws Exception {
        String[] seats = {"A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10"};
        long show = createShow(4, seats);
        String t = token(unique("limit"));

        List<Callable<Response>> calls = new ArrayList<>();
        for (String seat : seats) {
            calls.add(() -> reserve(t, show, "{\"seats\":[\"" + seat + "\"]}"));
        }
        List<Response> responses = fireTogether(calls);

        assertThat(byStatus(responses)).isEqualTo(Map.of(201, 4L, 409, 6L));
        assertThat(responses.stream().filter(r -> r.status() == 409))
                .allMatch(r -> "per_user_limit".equals(r.field("error")));
        assertThat(counts(show)).containsExactly(6, 0, 4, 10);
    }

    @Test
    void cancelGivesQuotaBack() {
        long show = createShow(2, "A1", "A2", "A3");
        String t = token(unique("limit"));

        String rid = reserve(t, show, "{\"seats\":[\"A1\",\"A2\"]}").field("reservation_id");
        assertThat(reserve(t, show, "{\"seats\":[\"A3\"]}").field("error")).isEqualTo("per_user_limit");

        assertThat(cancel(t, rid).status()).isEqualTo(200);
        assertThat(reserve(t, show, "{\"seats\":[\"A3\"]}").status()).isEqualTo(201);
        assertThat(counts(show)).containsExactly(2, 0, 1, 3);
    }
}