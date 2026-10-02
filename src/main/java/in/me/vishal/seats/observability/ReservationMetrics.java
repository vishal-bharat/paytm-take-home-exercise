package in.me.vishal.seats.observability;

import in.me.vishal.seats.dto.DeclineReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class ReservationMetrics {

    private static final Logger log = LoggerFactory.getLogger(ReservationMetrics.class);

    private final Counter confirmed;
    private final Counter replayed;
    private final Counter cancelled;
    private final Map<DeclineReason, Counter> declined = new EnumMap<>(DeclineReason.class);
    private final MultiGauge seats;
    private final JdbcTemplate jdbc;

    public ReservationMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations confirmed (201)").register(registry);
        this.replayed = Counter.builder("reservations.replayed")
                .description("Idempotent retries answered with the original reservation").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled")
                .description("Reservations cancelled").register(registry);
        for (DeclineReason reason : DeclineReason.values()) {
            declined.put(reason, Counter.builder("reservations.declined")
                    .description("Reservation requests declined (409), by reason")
                    .tag("reason", reason.code())
                    .register(registry));
        }
        this.seats = MultiGauge.builder("seats")
                .description("Seats per show by status, read from the database")
                .register(registry);
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void replayed() {
        replayed.increment();
    }

    public void cancelled() {
        cancelled.increment();
    }

    public void declined(DeclineReason reason) {
        declined.get(reason).increment();
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refreshSeatGauges() {
        try {
            Map<Long, long[]> byShow = new LinkedHashMap<>(); 
            jdbc.query("SELECT show_id, status, count(*) AS n FROM seats GROUP BY show_id, status", rs -> {
                long[] counts = byShow.computeIfAbsent(rs.getLong("show_id"), id -> new long[2]);
                if ("available".equals(rs.getString("status"))) {
                    counts[0] = rs.getLong("n");
                } else {
                    counts[1] = rs.getLong("n");
                }
            });

            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            byShow.forEach((showId, counts) -> {
                String show = String.valueOf(showId);
                rows.add(MultiGauge.Row.of(Tags.of("show", show, "status", "available"), counts[0]));
                rows.add(MultiGauge.Row.of(Tags.of("show", show, "status", "held"), 0)); // explicit-cancel model
                rows.add(MultiGauge.Row.of(Tags.of("show", show, "status", "confirmed"), counts[1]));
            });
            seats.register(rows, true);
        } catch (Exception e) {
            log.warn("seat gauge refresh failed: {}", e.toString());
        }
    }
}
