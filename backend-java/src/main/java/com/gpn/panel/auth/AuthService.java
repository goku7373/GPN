package com.gpn.panel.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final Duration TTL = Duration.ofHours(12);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> accounts = new LinkedHashMap<>();

    public AuthService(@Value("${panel.admin-user}") String adminUser,
                       @Value("${panel.admin-password}") String adminPassword,
                       @Value("${panel.extra-accounts:}") String extraAccounts) {
        accounts.put(adminUser, adminPassword);
        // Extra accounts format: user:password, multiple entries separated by commas
        for (String pair : extraAccounts.split(",")) {
            String p = pair.trim();
            int idx = p.indexOf(':');
            if (idx <= 0 || idx == p.length() - 1) continue;
            accounts.put(p.substring(0, idx).trim(), p.substring(idx + 1).trim());
        }
    }

    public String login(String user, String password) {
        if (user == null || password == null) {
            return null;
        }
        String matched = null;
        for (Map.Entry<String, String> e : accounts.entrySet()) {
            boolean userOk = MessageDigest.isEqual(
                    user.getBytes(StandardCharsets.UTF_8), e.getKey().getBytes(StandardCharsets.UTF_8));
            boolean passOk = MessageDigest.isEqual(
                    password.getBytes(StandardCharsets.UTF_8), e.getValue().getBytes(StandardCharsets.UTF_8));
            if (userOk && passOk) matched = e.getKey();
        }
        if (matched == null) {
            return null;
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(token, new Session(matched, System.currentTimeMillis() + TTL.toMillis()));
        return token;
    }

    public boolean valid(String token) {
        return usernameOf(token) != null;
    }

    public String usernameOf(String token) {
        if (token == null) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        if (s.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return s.username();
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private record Session(String username, long expiresAt) {
    }
}
