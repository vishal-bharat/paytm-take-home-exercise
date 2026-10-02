package in.me.vishal.seats.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
 
@Repository
public class QuotaRepository {
 
    private final JdbcTemplate jdbc;
 
    public QuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
 
    /**
     * Atomically adds n seats to the user's count if that stays within the limit.
     * Parallel requests from one user serialize on this single row, so the limit can't be overshot.
     */
    public boolean tryConsume(long showId, String userId, int n, int limit) {
        jdbc.update("""
                INSERT INTO user_show_quota (show_id, user_id, used)
                VALUES (?, ?, 0)
                ON CONFLICT (show_id, user_id) DO NOTHING
                """, showId, userId);
        int updated = jdbc.update("""
                UPDATE user_show_quota
                SET used = used + ?
                WHERE show_id = ? AND user_id = ? AND used + ? <= ?
                """, n, showId, userId, n, limit);
        return updated == 1;
    }
}