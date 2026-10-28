package com.gpn.panel.web;

import com.gpn.panel.proxy.ConntrackService;
import com.gpn.panel.proxy.IkeSessionHistory;
import com.gpn.panel.proxy.Ikev2Service;
import com.gpn.panel.proxy.IpGeoService;
import com.gpn.panel.proxy.ShareBuilder;
import com.gpn.panel.proxy.SingboxService;
import com.gpn.panel.state.ClientAccount;
import com.gpn.panel.state.StateStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All endpoints needed by the single-page UI: overview, client credentials, share configs, subscriptions.
 */
@RestController
@RequestMapping("/api")
public class ProxyController {

    private final StateStore store;
    private final SingboxService singbox;
    private final ShareBuilder share;
    private final Ikev2Service ikev2;
    private final IpGeoService geo;
    private final IkeSessionHistory history;
    private final ConntrackService conntrack;

    public ProxyController(StateStore store, SingboxService singbox, ShareBuilder share,
                           Ikev2Service ikev2, IpGeoService geo, IkeSessionHistory history,
                           ConntrackService conntrack) {
        this.store = store;
        this.singbox = singbox;
        this.share = share;
        this.ikev2 = ikev2;
        this.geo = geo;
        this.history = history;
        this.conntrack = conntrack;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "200") int lines) {
        String host = share.host();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proxy", singbox.status(lines));
        body.put("server", share.serverInfo(host));
        body.put("ikev2", ikev2Info(host));
        List<Map<String, Object>> clients = new ArrayList<>();
        for (ClientAccount c : store.clients()) {
            clients.add(view(c, host));
        }
        body.put("clients", clients);
        return body;
    }

    @PostMapping("/clients")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, String> payload) {
        String name = payload == null ? null : payload.get("name");
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please enter a client name");
        name = name.trim();
        if (name.length() > 40) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must be at most 40 characters");
        ClientAccount c = store.create(name);
        singbox.apply();
        ikev2.apply();
        return view(c, share.host());
    }

    @PostMapping("/clients/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable String id) {
        ClientAccount c = require(id);
        c.enabled = !c.enabled;
        store.save();
        singbox.apply();
        ikev2.apply();
        return view(c, share.host());
    }

    @PostMapping("/clients/{id}/rotate")
    public Map<String, Object> rotate(@PathVariable String id) {
        ClientAccount c = require(id);
        store.rotate(c);
        singbox.apply();
        ikev2.apply();
        return view(c, share.host());
    }

    /** Ban: revoke credentials (VLESS + IKEv2) and kick active sessions; unban via /unban */
    @PostMapping("/clients/{id}/ban")
    public Map<String, Object> ban(@PathVariable String id) {
        ClientAccount c = require(id);
        c.enabled = false;
        store.save();
        singbox.apply();
        ikev2.apply();
        kickSessionsOf(c.ikeUser);
        history.forget(c.ikeUser);
        return view(c, share.host());
    }

    @PostMapping("/clients/{id}/unban")
    public Map<String, Object> unban(@PathVariable String id) {
        ClientAccount c = require(id);
        c.enabled = true;
        store.save();
        singbox.apply();
        ikev2.apply();
        return view(c, share.host());
    }

    /** Kick offline: terminate a single device by IKE_SA uniqueid; the client may reconnect on its own */
    @PostMapping("/sessions/ikev2/{ikeId}/terminate")
    public Map<String, Object> terminateIke(@PathVariable String ikeId) {
        try {
            ikev2.terminate(ikeId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        return Map.of("ok", true);
    }

    /** Speed test: waits for one sampling window, then returns upstream/downstream rates (bytes/sec) */
    @PostMapping("/sessions/ikev2/{ikeId}/speedtest")
    public Map<String, Object> speedTestIke(@PathVariable String ikeId) {
        try {
            return ikev2.speedTest(ikeId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** One-click speed test: all online IKEv2 devices, returns uniqueid → rates */
    @PostMapping("/sessions/speedtest-all")
    public Map<String, Object> speedTestAll() {
        return ikev2.speedTestAll();
    }

    private void kickSessionsOf(String ikeUser) {
        if (ikeUser == null || ikeUser.isBlank()) return;
        for (Map<String, Object> s : ikev2.sessions()) {
            if (ikeUser.equals(s.get("user"))) {
                try {
                    ikev2.terminate(String.valueOf(s.get("id")));
                } catch (RuntimeException ignored) {
                    // Failing to kick a single session does not affect the ban itself
                }
            }
        }
    }

    @DeleteMapping("/clients/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        ClientAccount c = require(id);
        store.delete(id);
        singbox.apply();
        ikev2.apply();
        history.forget(c.ikeUser);
        return Map.of("ok", true);
    }

    @GetMapping("/clients/{id}/share")
    public Map<String, Object> shareDetail(@PathVariable String id) throws Exception {
        ClientAccount c = require(id);
        String host = share.host();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", share.vlessUri(c, host));
        m.put("singbox", share.singboxClientConfig(c, host));
        m.put("subPath", "/api/sub/" + c.subToken);
        return m;
    }

    @PostMapping("/proxy/restart")
    public Map<String, Object> restart() {
        singbox.restart();
        return singbox.status();
    }

    /** Live connections: active VLESS connections and established IKEv2 devices */
    @GetMapping("/sessions")
    public Map<String, Object> sessions() {
        Map<String, String> nameByKey = new LinkedHashMap<>();
        for (ClientAccount c : store.clients()) {
            nameByKey.put(c.id, c.name);
            nameByKey.put(c.uuid, c.name);
            nameByKey.put(c.name, c.name);
        }

        List<Map<String, Object>> vless = new java.util.ArrayList<>();
        for (Map<String, Object> m : singbox.connections()) {
            Map<String, Object> row = new LinkedHashMap<>(m);
            String key = String.valueOf(m.getOrDefault("user", ""));
            row.put("client", nameByKey.getOrDefault(key, key.isEmpty() ? "Unidentified" : key.substring(0, 8)));
            vless.add(row);
        }
        List<Map<String, Object>> ike = ikev2.sessions();
        for (Map<String, Object> s : ike) {
            s.put("region", geo.lookup(String.valueOf(s.getOrDefault("remoteHost", ""))));
        }
        List<Map<String, Object>> ikeRows = history.merge(ike);
        long ikeOnline = ikeRows.stream().filter(r -> Boolean.TRUE.equals(r.get("online"))).count();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vless", vless);
        body.put("ikev2", ikeRows);
        body.put("vlessCount", vless.size());
        // Navigation and tunnel cards show the online count; offline records are counted separately for filtering
        body.put("ikeCount", ikeOnline);
        body.put("ikeOfflineCount", ikeRows.size() - ikeOnline);
        return body;
    }

    /**
     * Device monitoring: basic connection info + proxy route + current access targets.
     * IKEv2 access targets come from the host's conntrack (filtered by the internal IP
     * assigned to the device).
     */
    @GetMapping("/monitor/ikev2/{vpnIp}")
    public Map<String, Object> monitorIke(@PathVariable String vpnIp) {
        Map<String, Object> found = null;
        for (Map<String, Object> s : ikev2.sessions()) {
            if (vpnIp.equals(String.valueOf(s.getOrDefault("vpnIp", "")))) {
                found = s;
                break;
            }
        }
        List<Map<String, Object>> flows = conntrack.flowsOf(vpnIp);
        // Online status is determined by the IKEv2 SA: conntrack entries linger after disconnect and cannot be used as evidence of being online
        boolean online = found != null;
        if (found == null) {
            for (Map<String, Object> r : history.merge(List.of())) {
                if (vpnIp.equals(String.valueOf(r.getOrDefault("vpnIp", "")))) {
                    found = r;
                    break;
                }
            }
        }

        Map<String, Object> device = new LinkedHashMap<>();
        String client = "Unknown device";
        String remoteHost = "";
        String region = "";
        long connected = 0;
        long upload = 0;
        long download = 0;
        if (found != null) {
            client = String.valueOf(found.getOrDefault("client", client));
            remoteHost = String.valueOf(found.getOrDefault("remoteHost", ""));
            region = String.valueOf(found.getOrDefault("region", ""));
            if (region.isBlank() || "null".equals(region)) region = geo.lookup(remoteHost);
            connected = asLong(found.get("established"));
            upload = asLong(found.get("upload"));
            download = asLong(found.get("download"));
        }
        device.put("client", client);
        device.put("online", online);
        device.put("publicIp", remoteHost);
        device.put("region", region);
        device.put("vpnIp", vpnIp);
        device.put("connected", connected);
        device.put("upload", upload);
        device.put("download", download);

        long totalBytes = 0;
        java.util.Set<String> dests = new java.util.LinkedHashSet<>();
        for (Map<String, Object> f : flows) {
            totalBytes += asLong(f.get("bytes")) + asLong(f.get("down"));
            dests.add(String.valueOf(f.get("destination")));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "ikev2");
        body.put("device", device);
        body.put("route", route(client, "IKEv2 / EAP-MSCHAPv2", vpnIp, "System VPN tunnel"));
        body.put("flows", flows);
        body.put("summary", summary(flows.size(), dests.size(), totalBytes));
        return body;
    }

    /** VLESS device monitoring: access targets come from clash_api's active connections */
    @GetMapping("/monitor/vless")
    public Map<String, Object> monitorVless(@RequestParam String client) {
        Map<String, String> nameByKey = new LinkedHashMap<>();
        for (ClientAccount c : store.clients()) {
            nameByKey.put(c.id, c.name);
            nameByKey.put(c.uuid, c.name);
        }
        List<Map<String, Object>> flows = new ArrayList<>();
        long upload = 0;
        long download = 0;
        java.util.Set<String> dests = new java.util.LinkedHashSet<>();
        for (Map<String, Object> m : singbox.connections()) {
            String key = String.valueOf(m.getOrDefault("user", ""));
            String name = nameByKey.getOrDefault(key, key.isEmpty() ? "Unidentified" : key.substring(0, 8));
            if (!client.equals(name)) continue;
            long up = asLong(m.get("upload"));
            long down = asLong(m.get("download"));
            upload += up;
            download += down;
            Map<String, Object> f = new LinkedHashMap<>();
            String destination = String.valueOf(m.getOrDefault("destination", ""));
            f.put("proto", m.getOrDefault("network", "tcp"));
            f.put("destination", hostOf(destination));
            f.put("port", portOf(destination));
            f.put("bytes", up);
            f.put("down", down);
            f.put("packets", 0L);
            f.put("state", "ACTIVE");
            f.put("timeout", 0L);
            flows.add(f);
            dests.add(hostOf(destination));
        }
        flows.sort((a, b) -> Long.compare(asLong(b.get("bytes")), asLong(a.get("bytes"))));

        Map<String, Object> device = new LinkedHashMap<>();
        device.put("client", client);
        device.put("online", !flows.isEmpty());
        device.put("publicIp", "");
        device.put("region", "");
        device.put("vpnIp", "");
        device.put("connected", 0L);
        device.put("upload", upload);
        device.put("download", download);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "vless");
        body.put("device", device);
        body.put("route", route(client, "VLESS + Reality", "Local proxy", "XTLS-Vision / TCP"));
        body.put("flows", flows);
        body.put("summary", summary(flows.size(), dests.size(), upload + download));
        return body;
    }

    private Map<String, Object> route(String client, String ingress, String localIp, String tunnel) {
        Map<String, Object> s = share.serverInfo(share.host());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client", client);
        m.put("ingress", ingress);
        m.put("localIp", localIp);
        m.put("tunnel", tunnel);
        m.put("server", s.get("host"));
        m.put("serverPort", s.get("port"));
        m.put("egress", "Public egress");
        return m;
    }

    private static Map<String, Object> summary(int active, int destinations, long bytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("destinations", destinations);
        m.put("bytes", bytes);
        return m;
    }

    private static String hostOf(String destination) {
        int i = destination.lastIndexOf(':');
        return i > 0 ? destination.substring(0, i) : destination;
    }

    private static long portOf(String destination) {
        int i = destination.lastIndexOf(':');
        if (i < 0) return 0;
        try {
            return Long.parseLong(destination.substring(i + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** CA certificate: only used to make iPhones trust the server; it's public info, exposed so Safari can download and install it directly */
    @GetMapping(value = "/ikev2/ca.crt")
    public ResponseEntity<byte[]> caCertificate() throws Exception {
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/x-x509-ca-cert")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gpn-ca.crt\"")
                    .body(ikev2.caCertificate());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CA certificate not deployed");
        }
    }

    /** iOS configuration profile: publicly downloadable with the subscription token; opens in Safari and installs with a tap */
    @GetMapping(value = "/ikev2/profile/{token}.mobileconfig")
    public ResponseEntity<byte[]> iosProfile(@PathVariable String token) throws Exception {
        ClientAccount c = store.findBySubToken(token);
        if (c == null || !c.enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Client does not exist or is disabled");
        }
        String body = ikev2.mobileconfig(c, share.host());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/x-apple-aspen-config")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gpn-iphone.mobileconfig\"")
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Subscription: publicly accessible with the token; clients can subscribe directly */
    @GetMapping(value = "/sub/{token}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> subscription(@PathVariable String token) {
        ClientAccount c = store.findBySubToken(token);
        if (c == null || !c.enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription does not exist");
        }
        String body = share.subscription(List.of(c), share.host());
        return ResponseEntity.ok()
                .header("Subscription-Userinfo", "upload=0; download=0; total=0")
                .body(body);
    }

    private ClientAccount require(String id) {
        ClientAccount c = store.find(id);
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Client does not exist");
        return c;
    }

    private Map<String, Object> ikev2Info(String host) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", ikev2.available());
        m.put("host", host);
        m.put("remoteId", host);
        m.put("caPath", "/api/ikev2/ca.crt");
        m.put("protocol", "IKEv2 / EAP-MSCHAPv2");
        return m;
    }

    private Map<String, Object> view(ClientAccount c, String host) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("name", c.name);
        m.put("uuid", c.uuid);
        m.put("ikeUser", c.ikeUser);
        m.put("ikePass", c.ikePass);
        m.put("enabled", c.enabled);
        m.put("createdAt", c.createdAt);
        m.put("subPath", "/api/sub/" + c.subToken);
        m.put("profilePath", "/api/ikev2/profile/" + c.subToken + ".mobileconfig");
        m.put("uri", share.vlessUri(c, host));
        return m;
    }
}
