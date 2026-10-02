package in.me.vishal.seats.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
 
@Repository
public class ShowRepository {
 
    private final JdbcTemplate jdbc;
 
    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
 
    public long insert(String name, long pricePaise, int perUserLimit, int totalSeats) {
        return jdbc.queryForObject("""
                INSERT INTO shows (name, price_paise, per_user_limit, total_seats)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, name, pricePaise, perUserLimit, totalSeats);
    }
}
