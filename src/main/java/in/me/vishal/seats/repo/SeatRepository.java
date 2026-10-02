package in.me.vishal.seats.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
 
import java.util.List;
import java.util.UUID;
 
@Repository
public class SeatRepository {
	
    public record SeatRow(String label, String status) {
    }
 
    private final JdbcTemplate jdbc;
 
    public SeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
 
    /** All seats in one statement: one round trip whether the show has 10 seats or 10,000. */
    public void insertAll(long showId, List<String> labels) {
        jdbc.update("""
                INSERT INTO seats (show_id, label)
                SELECT ?, unnest(?::text[])
                """, showId, labels.toArray(String[]::new));
    }
    
    public List<SeatRow> findByShow(long showId) {
        return jdbc.query("""
                        SELECT label, status
                        FROM seats
                        WHERE show_id = ?
                        ORDER BY label
                        """,
                (rs, i) -> new SeatRow(rs.getString("label"), rs.getString("status")),
                showId);
    }
    
    public boolean anyConfirmed(long showId, List<String> labels) {
        Boolean taken = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM seats
                    WHERE show_id = ? AND label = ANY(?::text[]) AND status = 'confirmed'
                )
                """, Boolean.class, showId, labels.toArray(String[]::new));
        return Boolean.TRUE.equals(taken);
    }
 
    public List<SeatRow> lockForUpdate(long showId, List<String> sortedLabels) {
        return jdbc.query("""
                        SELECT label, status
                        FROM seats
                        WHERE show_id = ? AND label = ANY(?::text[])
                        ORDER BY label
                        FOR UPDATE
                        """,
                (rs, i) -> new SeatRow(rs.getString("label"), rs.getString("status")),
                showId, sortedLabels.toArray(String[]::new));
    }
 
    public int confirm(long showId, List<String> labels, String userId, UUID reservationId) {
        return jdbc.update("""
                UPDATE seats
                SET status = 'confirmed', user_id = ?, reservation_id = ?
                WHERE show_id = ? AND label = ANY(?::text[]) AND status = 'available'
                """, userId, reservationId, showId, labels.toArray(String[]::new));
    }
    
    public int release(long showId, UUID reservationId) {
        return jdbc.update("""
                UPDATE seats
                SET status = 'available', user_id = NULL, reservation_id = NULL
                WHERE show_id = ? AND reservation_id = ? AND status = 'confirmed'
                """, showId, reservationId);
    }
}
