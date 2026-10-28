package com.gpn.panel.proxy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gpn.panel.state.ClientAccount;
import com.gpn.panel.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IKEv2 session history: once an IKE_SA disappears from strongSwan (peer disconnected,
 * DPD timeout), it no longer shows up in snapshots. Here we keep one record per EAP
 * username so the panel keeps showing the session as "offline" after disconnect, and it
 * is automatically overwritten to online on reconnect. Records are persisted to
 * {data-dir}/ike-history.json so they survive restarts.
 */
@Service
public class IkeSessionHistory {

    private static final Logger log = LoggerFactory.getLogger(IkeSessionHistory.class);
    /** Offline records are kept for 7 days, then automatically purged to avoid unbounded growth */
    private static final long RETENTION = 7L * 24 * 3600_000L;
    private static final int MAX_ENTRIES = 200;
    private static final long SAVE_INTERVAL = 5 * 60_000L;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final StateStore store;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private long savedAt;

    public static class Entry {
        public String key = "";
        public String user = "";
        public String client = "";
        public String remoteHost = "";
        public String region = "";
        public String vpnIp = "";
        public String ikeId = "";
        public long established;
        public long lastSeen;
        public long upload;
        public long download;
        public boolean online;
    }

    public IkeSessionHistory(StateStore store,
                             @Value("${panel.data-dir:./data}") String dataDir) {
        this.store = store;
        this.file = Path.of(dataDir).resolve("ike-history.json");
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            List<Entry> loaded = mapper.readValue(file.toFile(), new TypeReference<List<Entry>>() {});
            if (loaded != null) entries.addAll(loaded);
            for (Entry e : entries) e.online = false;
        } catch (IOException e) {
            log.warn("Failed to read session history: {}", e.getMessage());
        }
    }

    /**
     * Merge the latest live sessions into the history and return the full list for the panel
     * (online first, the rest in descending order of last-seen time).
     */
    public synchronized List<Map<String, Object>> merge(List<Map<String, Object>> live) {
        long now = System.currentTimeMillis();
        Map<String, String> nameByUser = new HashMap<>();
        for (ClientAccount c : store.clients()) {
            if (c.ikeUser != null) nameByUser.put(c.ikeUser, c.name);
        }

        boolean dirty = false;
        Map<String, Entry> byKey = new HashMap<>();
        for (Entry e : entries) byKey.put(e.key, e);
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (Map<String, Object> s : live) {
            String user = str(s.get("user"));
            String key = user.isEmpty() ? str(s.get("remoteHost")) : user;
            seen.add(key);
            Entry e = byKey.get(key);
            if (e == null) {
                e = new Entry();
                e.key = key;
                entries.add(e);
                byKey.put(key, e);
                dirty = true;
            }
            if (!e.online) {
                e.online = true;
                dirty = true;
            }
            e.user = user;
            e.ikeId = str(s.get("id"));
            e.established = num(s.get("established"));
            e.remoteHost = str(s.get("remoteHost"));
            e.vpnIp = trimBrackets(str(s.get("vpnIp")));
            e.region = str(s.get("region"));
            byKey.put(key, e);
            e.upload = Math.max(e.upload, num(s.get("upload")));
            e.download = Math.max(e.download, num(s.get("download")));
            e.lastSeen = now;
        }

        // Sessions that disappeared from the snapshot are marked offline; records offline for more than 7 days are purged
        for (Entry e : entries) {
            if (e.online && !seen.contains(e.key)) {
                e.online = false;
                dirty = true;
            }
        }
        if (entries.removeIf(e -> !e.online && now - e.lastSeen > RETENTION)) dirty = true;
        if (entries.size() > MAX_ENTRIES) {
            entries.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
            entries.subList(MAX_ENTRIES, entries.size()).clear();
            dirty = true;
        }

        if (dirty || now - savedAt > SAVE_INTERVAL) save();

        List<Map<String, Object>> out = new ArrayList<>();
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort((a, b) -> {
            if (a.online != b.online) return a.online ? -1 : 1;
            return Long.compare(b.lastSeen, a.lastSeen);
        });
        for (Entry e : ordered) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.ikeId);
            m.put("client", nameByUser.getOrDefault(e.user, e.client.isEmpty() ? e.user : e.client));
            m.put("user", e.user);
            m.put("remoteHost", e.remoteHost);
            m.put("region", e.region);
            m.put("vpnIp", e.vpnIp);
            m.put("established", e.established);
            m.put("upload", e.upload);
            m.put("download", e.download);
            m.put("online", e.online);
            m.put("lastSeen", e.lastSeen / 1000);
            out.add(m);
        }
        return out;
    }

    /** Remove history records as well when an account is deleted or banned */
    public synchronized void forget(String ikeUser) {
        if (ikeUser == null || ikeUser.isBlank()) return;
        if (entries.removeIf(e -> ikeUser.equals(e.key) || ikeUser.equals(e.user))) save();
    }

    private void save() {
        entries.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("ike-history.json.tmp");
            mapper.writeValue(tmp.toFile(), entries);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            savedAt = System.currentTimeMillis();
        } catch (IOException e) {
            log.warn("Failed to write session history: {}", e.getMessage());
        }
    }

    private static String trimBrackets(String v) {
        String s = v.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        if (s.contains(",")) s = s.substring(0, s.indexOf(','));
        return s.trim();
    }

    private static long num(Object v) {
        try {
            return Long.parseLong(str(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
