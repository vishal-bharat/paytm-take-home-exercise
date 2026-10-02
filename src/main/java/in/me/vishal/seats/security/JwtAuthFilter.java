package in.me.vishal.seats.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
 
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.regex.Pattern;


@Component
public class JwtAuthFilter extends OncePerRequestFilter {
 
    public static final String USER_ID = "userId";
 
    private static final Pattern SHOW_STATE = Pattern.compile("^/shows/[^/]+$");
 
    private final JwtService jwt;
    private final byte[] adminKey;
 
    public JwtAuthFilter(JwtService jwt, @Value("${app.admin-key}") String adminKey) {
        if (adminKey == null || adminKey.isBlank()) {
            throw new IllegalStateException("app.admin-key must be set");
        }
        this.jwt = jwt;
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }
 
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
    	
        String path = req.getServletPath();
        String method = req.getMethod();
 
        if (isPublic(method, path)) {
            chain.doFilter(req, res);
            return;
        }
 
        if ("POST".equals(method) && "/shows".equals(path)) {
            String key = req.getHeader("X-Admin-Key");
            // constant-time comparison: response timing doesn't leak how much of the key matched
            if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), adminKey)) {
                reject(res, "admin key required");
                return;
            }
            chain.doFilter(req, res);
            return;
        }
 
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(res, "bearer token required");
            return;
        }
        Optional<String> userId = jwt.verify(header.substring("Bearer ".length()).trim());
        if (userId.isEmpty()) {
            reject(res, "invalid or expired token");
            return;
        }
        req.setAttribute(USER_ID, userId.get());
        chain.doFilter(req, res);
    }
 
    private static boolean isPublic(String method, String path) {
        return path.startsWith("/actuator/")
                || ("POST".equals(method) && "/auth/token".equals(path))
                || ("GET".equals(method) && SHOW_STATE.matcher(path).matches());
    }
 
    private static void reject(HttpServletResponse res, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }
}
