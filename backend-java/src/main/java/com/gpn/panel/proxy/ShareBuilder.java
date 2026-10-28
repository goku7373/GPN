package com.gpn.panel.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpn.panel.state.ClientAccount;
import com.gpn.panel.state.PanelState;
import com.gpn.panel.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates client-side connection info: VLESS share links, sing-box client configs, and subscription content.
 */
@Service
public class ShareBuilder {

    private static final Logger log = LoggerFactory.getLogger(ShareBuilder.class);
    private static final String FINGERPRINT = "chrome";

    private final StateStore store;
    private final SingboxService singbox;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String configuredHost;
    private volatile String detectedHost;

    public ShareBuilder(StateStore store,
                        SingboxService singbox,
                        @Value("${panel.public-host:}") String configuredHost) {
        this.store = store;
        this.singbox = singbox;
        this.configuredHost = configuredHost == null ? "" : configuredHost.trim();
    }

    /** Server address clients should use: prefer the configured value, otherwise detect this host's public IP; falls back to a placeholder if detection fails */
    public String host() {
        if (!configuredHost.isEmpty()) return configuredHost;
        String cached = detectedHost;
        if (cached != null) return cached;
        String detected = detectPublicIp();
        detectedHost = detected;
        return detected;
    }

    private String detectPublicIp() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ipify.org"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            String ip = res.body() == null ? "" : res.body().trim();
            if (ip.matches("[0-9a-fA-F.:]{7,45}")) return ip;
        } catch (Exception e) {
            log.warn("Unable to detect public IP: {}", e.getMessage());
        }
        return "YOUR_SERVER_IP";
    }

    public Map<String, Object> serverInfo(String host) {
        PanelState.Reality r = store.state().reality;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", host);
        m.put("port", singbox.port());
        m.put("sni", singbox.sni());
        m.put("publicKey", r.publicKey);
        m.put("shortId", r.shortId);
        m.put("fingerprint", FINGERPRINT);
        m.put("protocol", "VLESS + Reality (XTLS-Vision)");
        return m;
    }

    public String vlessUri(ClientAccount c, String host) {
        PanelState.Reality r = store.state().reality;
        String query = "encryption=none"
                + "&flow=xtls-rprx-vision"
                + "&security=reality"
                + "&sni=" + enc(singbox.sni())
                + "&fp=" + FINGERPRINT
                + "&pbk=" + enc(r.publicKey)
                + "&sid=" + enc(r.shortId)
                + "&type=tcp"
                + "&headerType=none";
        return "vless://" + c.uuid + "@" + host + ":" + singbox.port()
                + "?" + query + "#" + enc(c.name);
    }

    public String singboxClientConfig(ClientAccount c, String host) throws Exception {
        PanelState.Reality r = store.state().reality;

        Map<String, Object> reality = new LinkedHashMap<>();
        reality.put("enabled", true);
        reality.put("public_key", r.publicKey);
        reality.put("short_id", r.shortId);

        Map<String, Object> tls = new LinkedHashMap<>();
        tls.put("enabled", true);
        tls.put("server_name", singbox.sni());
        tls.put("utls", Map.of("enabled", true, "fingerprint", FINGERPRINT));
        tls.put("reality", reality);

        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("type", "vless");
        outbound.put("tag", "proxy");
        outbound.put("server", host);
        outbound.put("server_port", singbox.port());
        outbound.put("uuid", c.uuid);
        outbound.put("flow", "xtls-rprx-vision");
        outbound.put("tls", tls);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("log", Map.of("level", "info", "timestamp", true));
        root.put("inbounds", List.of(Map.of(
                "type", "mixed",
                "tag", "mixed-in",
                "listen", "127.0.0.1",
                "listen_port", 2080)));
        root.put("outbounds", List.of(
                outbound,
                Map.of("type", "direct", "tag", "direct")));
        root.put("route", Map.of("final", "proxy"));

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /** Standard subscription: base64(one vless:// link per line) */
    public String subscription(List<ClientAccount> clients, String host) {
        String body = clients.stream()
                .filter(c -> c.enabled)
                .map(c -> vlessUri(c, host))
                .collect(Collectors.joining("\n"));
        return Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
