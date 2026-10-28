package com.gpn.panel.web;

import com.gpn.panel.auth.AuthFilter;
import com.gpn.panel.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true);
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) throws InterruptedException {
        String token = auth.login(body.username(), body.password());
        if (token == null) {
            Thread.sleep(400);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect username or password");
        }
        ResponseCookie cookie = ResponseCookie.from("gpn_sid", token)
                .httpOnly(true)
                .secure(isSecure(request))
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofHours(12))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return Map.of("ok", true, "username", auth.usernameOf(token));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(AuthFilter.extractToken(request));
        ResponseCookie cookie = ResponseCookie.from("gpn_sid", "")
                .httpOnly(true)
                .secure(isSecure(request))
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return Map.of("ok", true);
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(HttpServletRequest request) {
        String username = auth.usernameOf(AuthFilter.extractToken(request));
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        return Map.of("username", username);
    }

    private boolean isSecure(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }
}
