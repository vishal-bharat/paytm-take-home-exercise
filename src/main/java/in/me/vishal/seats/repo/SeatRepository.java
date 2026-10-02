package in.me.vishal.seats.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
 
import java.util.List;
 
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
}
