package in.me.vishal.seats.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;


@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_USER_ID = "user_id";

    private static final Logger ACCESS = LoggerFactory.getLogger("access");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(HEADER);
        String requestId = (incoming != null && SAFE_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_REQUEST_ID, requestId);
        res.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            String path = req.getServletPath();
            if (!path.startsWith("/actuator/")) {   
                long ms = (System.nanoTime() - start) / 1_000_000;
                ACCESS.atInfo()
                        .addKeyValue("method", req.getMethod())
                        .addKeyValue("path", path)
                        .addKeyValue("status", res.getStatus())
                        .addKeyValue("duration_ms", ms)
                        .log("{} {} -> {} in {}ms", req.getMethod(), path, res.getStatus(), ms);
            }
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_ID);
        }
    }
}
