package in.me.vishal.seats.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
 
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
 
@Repository
public class ReservationRepository {
 
    public record ReservationRow(UUID id, long showId, List<String> seats, long amountPaise,
                                 String status, String requestHash) {
    }
 
    private final JdbcTemplate jdbc;
 
    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
 
    public Optional<UUID> insertPending(long showId, String userId, List<String> seats, long amountPaise,
                                        String idempotencyKey, String requestHash) {
        return jdbc.query("""
                        INSERT INTO reservations
                            (show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash)
                        VALUES (?, ?, ?::text[], ?, 'pending', ?, ?)
                        ON CONFLICT (user_id, idempotency_key) DO NOTHING
                        RETURNING id
                        """,
                (rs, i) -> rs.getObject("id", UUID.class),
                showId, userId, seats.toArray(String[]::new), amountPaise, idempotencyKey, requestHash
        ).stream().findFirst();
    }
 
    public Optional<ReservationRow> findByKey(String userId, String idempotencyKey) {
        return jdbc.query("""
                        SELECT id, show_id, seats, amount_paise, status, request_hash
                        FROM reservations
                        WHERE user_id = ? AND idempotency_key = ?
                        """,
                (rs, i) -> new ReservationRow(
                        rs.getObject("id", UUID.class),
                        rs.getLong("show_id"),
                        Arrays.asList((String[]) rs.getArray("seats").getArray()),
                        rs.getLong("amount_paise"),
                        rs.getString("status"),
                        rs.getString("request_hash")),
                userId, idempotencyKey
        ).stream().findFirst();
    }
 
    public void markConfirmed(UUID id) {
        jdbc.update("UPDATE reservations SET status = 'confirmed' WHERE id = ?", id);
    }
}
