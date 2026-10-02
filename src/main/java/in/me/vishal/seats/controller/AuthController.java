package in.me.vishal.seats.controller;

import in.me.vishal.seats.dto.TokenRequest;
import in.me.vishal.seats.dto.TokenResponse;
import in.me.vishal.seats.security.JwtService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
 
/** Test identity provider. In production, tokens would come from a real IdP and this would be removed. */
@RestController
public class AuthController {
 
    private static final int MAX_USER_LENGTH = 64;
 
    private final JwtService jwt;
 
    public AuthController(JwtService jwt) {
        this.jwt = jwt;
    }
 
    @PostMapping("/auth/token")
    public TokenResponse token(@RequestBody TokenRequest req) {
        String user = req.user() == null ? "" : req.user().trim();
        if (user.isEmpty() || user.length() > MAX_USER_LENGTH) {
            throw new IllegalArgumentException("user must be 1-" + MAX_USER_LENGTH + " characters");
        }
        return new TokenResponse(jwt.issue(user), jwt.ttl().toSeconds());
    }
}
