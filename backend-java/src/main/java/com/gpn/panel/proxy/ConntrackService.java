package com.gpn.panel.proxy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Device access auditing: every 3 seconds the host dumps entries for the VPN subnet
 * (10.10.0.0/24) from the kernel conntrack table to /var/lib/gpn/conntrack.txt; the
 * container only reads that file, avoiding access to the host network namespace.
 * Each record contains the original destination IP/port and TX/RX byte counts, from
 * which we derive "which IPs this device has accessed".
 */
@Service
public class ConntrackService {

    private static final List<String> PROTOCOLS = List.of("tcp", "udp", "icmp", "icmpv6", "sctp", "dccp");

    private final Path file;

    public ConntrackService(@Value("${panel.conntrack-file:/sessions/conntrack.txt}") String file) {
        this.file = Path.of(file);
    }

    /** Active connections for the given internal IP; returns an empty list if the file doesn't exist (timer not deployed) */
    public List<Map<String, Object>> flowsOf(String vpnIp) {
        if (vpnIp == null || vpnIp.isBlank() || !Files.exists(file)) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : lines) {
            Map<String, String> kv = pairs(line);
            if (!vpnIp.equals(kv.get("src"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("proto", protoOf(line));
            m.put("destination", kv.getOrDefault("dst", ""));
            m.put("port", parseLong(kv.get("dport")));
            // A conntrack record contains counters for both directions: the 1st bytes value is uplink, the 2nd is downlink
            m.put("bytes", bytesOf(line, 0));
            m.put("down", bytesOf(line, 1));
            m.put("packets", parseLong(kv.get("packets")));
            m.put("state", stateOf(line));
            m.put("timeout", timeoutOf(line));
            String dst = kv.get("dst");
            if (dst == null || dst.isBlank()) continue;
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("bytes"), (Long) a.get("bytes")));
        return out;
    }

    /** Key-value pairs: src/dst appear twice on the same line; keep only the first occurrence (original direction) */
    private static Map<String, String> pairs(String line) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String t : line.trim().split("\\s+")) {
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            kv.putIfAbsent(t.substring(0, eq), t.substring(eq + 1));
        }
        return kv;
    }

    private static String protoOf(String line) {
        for (String t : prefix(line).split("\\s+")) {
            if (PROTOCOLS.contains(t)) return t;
        }
        return "tcp";
    }

    /**
     * The first segment before the original direction is the protocol name, followed by the timeout in seconds:
     * ipv4 2 tcp 6 90 TIME_WAIT src=...
     */
    private static long timeoutOf(String line) {
        String[] t = prefix(line).split("\\s+");
        for (int i = 0; i < t.length; i++) {
            if (PROTOCOLS.contains(t[i]) && i + 2 < t.length) return parseLong(t[i + 2]);
        }
        return 0;
    }

    /** Uppercase words are connection states (ESTABLISHED / TIME_WAIT etc.) */
    private static String stateOf(String line) {
        String raw = prefix(line);
        for (String t : raw.split("\\s+")) {
            if (t.length() >= 6 && t.equals(t.toUpperCase()) && t.matches("[A-Z_]+")) return t;
        }
        return "";
    }

    private static String prefix(String line) {
        int i = line.indexOf("src=");
        return i > 0 ? line.substring(0, i) : "";
    }

    /** Take the value of the n-th bytes= field (0 = uplink / 1 = downlink); missing values count as 0 */
    private static long bytesOf(String line, int n) {
        int seen = 0;
        for (String t : line.trim().split("\\s+")) {
            if (!t.startsWith("bytes=")) continue;
            if (seen == n) return parseLong(t.substring(6));
            seen++;
        }
        return 0;
    }

    private static long parseLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
