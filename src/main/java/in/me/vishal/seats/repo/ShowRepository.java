package in.me.vishal.seats.repo;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
 
@Repository
public class ShowRepository {
	
    public record ShowRow(long id, String name, long pricePaise, int perUserLimit, int totalSeats) {
    }
 
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
    
    public Optional<ShowRow> find(long id) {
        return jdbc.query("""
                        SELECT id, name, price_paise, per_user_limit, total_seats
                        FROM shows
                        WHERE id = ?
                        """,
                (rs, i) -> new ShowRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"),
                        rs.getInt("total_seats")),
                id).stream().findFirst();
    }
}
