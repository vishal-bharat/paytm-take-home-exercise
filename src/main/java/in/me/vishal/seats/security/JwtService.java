package in.me.vishal.seats.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
 
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
 
@Service
public class JwtService {
 
    private final SecretKey key;
    private final JwtParser parser;   // immutable and thread-safe: build once, reuse for every request
    private final Duration ttl;
 
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl}") Duration ttl) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // fail at startup, not on the first request
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.parser = Jwts.parser().verifyWith(key).build();
        this.ttl = ttl;
    }
 
    public String issue(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }
 
    public Optional<String> verify(String token) {
        try {
            String sub = parser.parseSignedClaims(token).getPayload().getSubject();
            return (sub == null || sub.isBlank()) ? Optional.empty() : Optional.of(sub);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
 
    public Duration ttl() {
        return ttl;
    }
}