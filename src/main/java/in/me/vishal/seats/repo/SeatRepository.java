package in.me.vishal.seats.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
 
import java.util.List;
 
@Repository
public class SeatRepository {
 
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
}
