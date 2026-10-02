package in.me.vishal.seats;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class HotSeatConcurrencyTest extends IntegrationTestBase {

    @Test
    void fiveHundredUsersOneSeat_exactlyOneWins() throws Exception {
        long show = createShow(4, "A1", "A2", "A3", "A4", "A5");
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            tokens.add(token(unique("hot")));
        }

        List<Callable<Response>> calls = new ArrayList<>();
        for (String t : tokens) {
            calls.add(() -> reserve(t, show, "{\"seats\":[\"A1\"]}"));
        }
        List<Response> responses = fireTogether(calls);

        assertThat(byStatus(responses)).isEqualTo(Map.of(201, 1L, 409, 499L));
        assertThat(countBy(responses.stream().filter(r -> r.status() == 409).toList(), r -> r.field("error")))
                .isEqualTo(Map.of("seat_taken", 499L));
        assertThat(counts(show)).containsExactly(4, 0, 1, 5);
    }

    @Test
    void overlappingMultiSeatRequests_noDeadlockNoDoubleSale() throws Exception {
        String[] seats = {"B1", "B2", "B3", "B4", "B5", "B6"};
        long show = createShow(4, seats);

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String first = seats[i % seats.length];
            String second = seats[(i + 1) % seats.length];
            String body = (i % 2 == 0)
                    ? "{\"seats\":[\"" + first + "\",\"" + second + "\"]}"
                    : "{\"seats\":[\"" + second + "\",\"" + first + "\"]}";   // reversed order
            String t = token(unique("pair"));
            calls.add(() -> reserve(t, show, body));
        }
        List<Response> responses = fireTogether(calls);

        assertThat(responses).allMatch(r -> r.status() == 201 || r.status() == 409);
        long winners = responses.stream().filter(r -> r.status() == 201).count();
        int[] c = counts(show);
        assertThat(c[2]).isEqualTo(winners * 2);          // every confirmed seat belongs to exactly one 201
        assertThat(c[0] + c[1] + c[2]).isEqualTo(c[3]);   // reconciliation invariant
        assertThat(winners).isBetween(1L, 3L);            // at most 3 disjoint pairs fit in 6 seats
    }
}
