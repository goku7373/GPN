package com.gpn.panel.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final AuthService auth;

    public AuthFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")
                || path.equals("/api/health")
                || path.equals("/api/auth/login")
                || path.equals("/api/ikev2/ca.crt")
                || path.startsWith("/api/ikev2/profile/")
                || path.startsWith("/api/sub/")) {
            chain.doFilter(request, response);
            return;
        }
        String token = extractToken(request);
        if (token == null || !auth.valid(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Not logged in or session expired\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    public static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("gpn_sid".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
