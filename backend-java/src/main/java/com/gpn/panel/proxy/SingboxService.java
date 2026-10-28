package com.gpn.panel.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpn.panel.state.ClientAccount;
import com.gpn.panel.state.PanelState;
import com.gpn.panel.state.StateStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the sing-box server as a subprocess: write config → validate with check → run.
 * Restarts on every config change. The watchdog automatically restarts the process
 * if it exits unexpectedly.
 */
@Service
public class SingboxService {

    private static final Logger log = LoggerFactory.getLogger(SingboxService.class);

    private final StateStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String bin;
    private final int port;
    private final String sni;
    private final Path workDir;
    private final Path configPath;
    private final Path logPath;

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sing-box-watchdog");
        t.setDaemon(true);
        return t;
    });

    private Process proc;
    private long startedAt;
    private volatile String version = "";
    private volatile String lastError = "";

    /** clash_api secret: exposed only on the local loopback interface, used to read live connections */
    private final String clashSecret = java.util.UUID.randomUUID().toString().replace("-", "");
    private final int clashPort = 19090;

    public SingboxService(StateStore store,
                          @Value("${panel.data-dir:/data}") String dataDir,
                          @Value("${panel.sing-box-bin:sing-box}") String bin,
                          @Value("${panel.proxy-port:24443}") int port,
                          @Value("${panel.reality-dest:www.apple.com}") String sni) {
        this.store = store;
        this.bin = bin;
        this.port = port;
        this.sni = sni;
        this.workDir = Path.of(dataDir).resolve("singbox");
        this.configPath = workDir.resolve("config.json");
        this.logPath = workDir.resolve("sing-box.log");
    }

    @PostConstruct
    void init() {
        version = detectVersion();
        if (version.isEmpty()) {
            lastError = "sing-box executable not found (" + bin + "); proxy not started";
            log.warn(lastError);
        }
        ensureRunning();
        watchdog.scheduleWithFixedDelay(this::ensureRunning, 10, 10, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        synchronized (this) {
            stop();
        }
    }

    public boolean available() {
        return !version.isEmpty();
    }

    public int port() {
        return port;
    }

    public String sni() {
        return sni;
    }

    /** Called after client credentials change: rewrites the config and restarts, taking effect immediately (revocation is immediate too) */
    public synchronized void apply() {
        stop();
        if (!available()) return;
        start();
    }

    public synchronized void restart() {
        apply();
    }

    private synchronized void ensureRunning() {
        if (!available()) return;
        if (proc != null && proc.isAlive()) return;
        start();
    }

    private void start() {
        try {
            writeConfig();
            ProcResult check = exec(15, bin, "check", "-c", configPath.toString());
            if (check.code != 0) {
                lastError = "Config validation failed: " + check.output.trim();
                log.error(lastError);
                return;
            }
            Files.createDirectories(workDir);
            ProcessBuilder pb = new ProcessBuilder(bin, "run", "-c", configPath.toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
            proc = pb.start();
            startedAt = System.currentTimeMillis();
            lastError = "";
            log.info("sing-box started, listening on port {}", port);
        } catch (Exception e) {
            lastError = "Startup failed: " + e.getMessage();
            log.error(lastError, e);
        }
    }

    private void stop() {
        if (proc == null) return;
        proc.destroy();
        try {
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            proc = null;
        }
    }

    /** Server config: a single vless+reality inbound with multiple users (one UUID per client), direct outbound */
    public synchronized void writeConfig() throws IOException {
        Files.createDirectories(workDir);
        PanelState state = store.state();

        List<Map<String, Object>> users = new ArrayList<>();
        for (ClientAccount c : state.clients) {
            if (!c.enabled) continue;
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("name", c.id);
            u.put("uuid", c.uuid);
            u.put("flow", "xtls-rprx-vision");
            users.add(u);
        }

        Map<String, Object> reality = new LinkedHashMap<>();
        reality.put("enabled", true);
        reality.put("handshake", Map.of("server", sni, "server_port", 443));
        reality.put("private_key", state.reality.privateKey);
        reality.put("short_id", List.of(state.reality.shortId));

        Map<String, Object> inbound = new LinkedHashMap<>();
        inbound.put("type", "vless");
        inbound.put("tag", "vless-in");
        inbound.put("listen", "0.0.0.0");
        inbound.put("listen_port", port);
        inbound.put("users", users);
        inbound.put("tls", Map.of("enabled", true, "server_name", sni, "reality", reality));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("log", Map.of("level", "info", "timestamp", true));
        root.put("inbounds", List.of(inbound));
        root.put("outbounds", List.of(Map.of("type", "direct", "tag", "direct")));
        root.put("route", Map.of("final", "direct"));
        // Loopback-only; the panel uses it to read currently active VLESS connections
        root.put("experimental", Map.of("clash_api",
                Map.of("external_controller", "127.0.0.1:" + clashPort, "secret", clashSecret)));

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(configPath, json, StandardCharsets.UTF_8);
    }

    /** Currently active VLESS connections (from the local clash_api); returns an empty list when unavailable */
    public List<Map<String, Object>> connections() {
        if (!available()) return List.of();
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create("http://127.0.0.1:" + clashPort + "/connections"))
                    .timeout(java.time.Duration.ofSeconds(2))
                    .header("Authorization", "Bearer " + clashSecret)
                    .GET().build();
            String body = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
            Map<String, String> userBySource = userBySource();
            List<Map<String, Object>> out = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : root.path("connections")) {
                com.fasterxml.jackson.databind.JsonNode md = n.path("metadata");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", n.path("id").asText(""));
                String source = md.path("sourceIP").asText("") + ":" + md.path("sourcePort").asText("");
                // The bracketed value in the log is the inbound user name (set to the client id here); prefer clash's own user if present
                m.put("user", userBySource.getOrDefault(source, md.path("user").asText("")));
                m.put("network", md.path("network").asText("tcp"));
                m.put("source", source);
                String host = md.path("host").asText("");
                m.put("destination", host.isEmpty()
                        ? md.path("destinationIP").asText("") + ":" + md.path("destinationPort").asText("")
                        : host + ":" + md.path("destinationPort").asText(""));
                m.put("startedAt", n.path("start").asText(""));
                m.put("upload", n.path("upload").asLong(0));
                m.put("download", n.path("download").asLong(0));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * sing-box's clash_api doesn't carry user info, but the log contains two lines per
     * connection ID — "inbound connection from IP:PORT" and "[uuid] inbound connection
     * to HOST" — which can be joined to map a source address to a client UUID.
     */
    private Map<String, String> userBySource() {
        Map<String, String> srcById = new java.util.HashMap<>();
        Map<String, String> uuidById = new java.util.HashMap<>();
        try {
            if (!Files.exists(logPath)) return Map.of();
            List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 400);
            java.util.regex.Pattern head = java.util.regex.Pattern.compile("\\[([0-9a-f]+) \\d+ms]");
            java.util.regex.Pattern fromPat = java.util.regex.Pattern.compile("inbound connection from (\\S+)");
            java.util.regex.Pattern uuidPat = java.util.regex.Pattern.compile("\\[([0-9a-f-]{36})] inbound connection to (\\S+)");
            for (int i = from; i < lines.size(); i++) {
                // sing-box logs contain ANSI color codes; strip them first or the brackets won't match
                String line = lines.get(i).replaceAll("\u001B\\[[0-9;]*m", "");
                java.util.regex.Matcher h = head.matcher(line);
                if (!h.find()) continue;
                String id = h.group(1);
                java.util.regex.Matcher f = fromPat.matcher(line);
                if (f.find()) {
                    srcById.put(id, f.group(1));
                    continue;
                }
                java.util.regex.Matcher u = uuidPat.matcher(line);
                if (u.find()) uuidById.put(id, u.group(1));
            }
        } catch (IOException e) {
            return Map.of();
        }
        Map<String, String> out = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : srcById.entrySet()) {
            String uuid = uuidById.get(e.getKey());
            if (uuid != null) out.put(e.getValue(), uuid);
        }
        return out;
    }

    /** Show the last 200 log lines by default */
    public static final int DEFAULT_LOG_LINES = 200;
    private static final java.util.Set<Integer> LOG_LINE_CHOICES = java.util.Set.of(200, 500, 1000, 2000);
    /** sing-box logs contain ANSI color codes; strip them uniformly before returning */
    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

    public Map<String, Object> status() {
        return status(DEFAULT_LOG_LINES);
    }

    public Map<String, Object> status(int logLines) {
        int lines = LOG_LINE_CHOICES.contains(logLines) ? logLines : DEFAULT_LOG_LINES;
        boolean running;
        long pid = -1;
        long uptime = 0;
        synchronized (this) {
            running = proc != null && proc.isAlive();
            if (running) {
                pid = proc.pid();
                uptime = System.currentTimeMillis() - startedAt;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available());
        m.put("running", running);
        m.put("pid", pid > 0 ? pid : null);
        m.put("uptimeMs", uptime);
        m.put("port", port);
        m.put("version", version);
        m.put("error", lastError.isEmpty() ? null : lastError);
        m.put("logTail", tail(lines));
        m.put("logLines", lines);
        return m;
    }

    private List<String> tail(int n) {
        if (!Files.exists(logPath)) return List.of();
        try {
            List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            List<String> out = new java.util.ArrayList<>(n);
            for (String line : lines.subList(Math.max(0, lines.size() - n), lines.size())) {
                out.add(ANSI.matcher(line).replaceAll("").replace("\r", ""));
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private String detectVersion() {
        try {
            ProcResult r = exec(8, bin, "version");
            if (r.code != 0) return "";
            return r.output.lines().findFirst().orElse("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private record ProcResult(int code, String output) {}

    private ProcResult exec(long timeoutSeconds, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new ProcResult(-1, "Command timed out");
        }
        byte[] out = p.getInputStream().readAllBytes();
        return new ProcResult(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }
}
