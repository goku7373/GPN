package com.gpn.panel.proxy;

import com.gpn.panel.state.ClientAccount;
import com.gpn.panel.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Syncs the panel's client credentials into strongSwan's EAP account file.
 * A systemd path unit on the host watches that file and runs `swanctl --load-all`
 * on change, so disable / rotate / delete all take effect within seconds, and the
 * container never needs to touch the docker socket.
 */
@Service
public class Ikev2Service {

    private static final Logger log = LoggerFactory.getLogger(Ikev2Service.class);

    private final StateStore store;
    private final Path usersFile;
    private final Path caFile;
    private final Path sasFile;
    private final Path cmdDir;

    public Ikev2Service(StateStore store,
                        @Value("${panel.ikev2-users-file:/ikev2/gpn-users.conf}") String usersFile,
                        @Value("${panel.ikev2-ca-file:/ikev2-ca/ca.crt.pem}") String caFile,
                        @Value("${panel.ikev2-sas-file:/sessions/ike-sas.txt}") String sasFile,
                        @Value("${panel.ikev2-cmd-dir:/data/ike-cmd}") String cmdDir) {
        this.store = store;
        this.usersFile = Path.of(usersFile);
        this.caFile = Path.of(caFile);
        this.sasFile = Path.of(sasFile);
        this.cmdDir = Path.of(cmdDir);
    }

    /**
     * The container cannot reach the vici socket, so "kick offline" is written as a
     * command file; the host path unit picks up the .cmd file, runs
     * `swanctl --terminate --ike-id`, and deletes it.
     */
    public void terminate(String ikeId) {
        String id = ikeId == null ? "" : ikeId.replaceAll("[^0-9]", "");
        if (id.isEmpty()) throw new IllegalArgumentException("Invalid IKE session ID");
        try {
            Files.createDirectories(cmdDir);
            Path f = cmdDir.resolve("term-" + UUID.randomUUID() + ".cmd");
            Files.writeString(f, id, StandardCharsets.UTF_8);
            log.info("Queued termination of IKE session: {}", id);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write termination command: " + e.getMessage(), e);
        }
    }

    /**
     * The host writes `swanctl --list-sas --raw` output to sasFile every 5 seconds
     * (the container has no direct vici access). That output is a single-line compact
     * format: list-sa event {gpn-ios {uniqueid=1 ... child-sas {...}}}, so it is parsed
     * by scanning characters for {} and k=v rather than by lines.
     */
    public List<Map<String, Object>> sessions() {
        if (!Files.exists(sasFile)) return List.of();
        String text;
        try {
            text = Files.readString(sasFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        Map<String, String> nameByIke = new java.util.HashMap<>();
        for (ClientAccount c : store.clients()) {
            if (c.ikeUser != null) nameByIke.put(c.ikeUser, c.name);
        }

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        java.util.Deque<Map<String, Object>> stack = new java.util.ArrayDeque<>();
        java.util.Deque<String> names = new java.util.ArrayDeque<>();
        StringBuilder tok = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '{') {
                String name = tok.toString().trim();
                tok.setLength(0);
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                if (!stack.isEmpty()) stack.peek().put(name, m);
                stack.push(m);
                names.push(name);
                i++;
            } else if (ch == '}') {
                tok.setLength(0);
                if (!stack.isEmpty()) {
                    Map<String, Object> m = stack.pop();
                    String name = names.pop();
                    // Only blocks directly under the top-level container (list-sa event / list-sas reply) are an IKE_SA
                    if (stack.size() == 1) {
                        Map<String, Object> s = toSession(name, m, nameByIke);
                        if (s != null) out.add(s);
                    }
                }
                i++;
            } else if (ch == '=') {
                String key = tok.toString().trim();
                tok.setLength(0);
                i++;
                StringBuilder val = new StringBuilder();
                int depth = 0;
                while (i < n) {
                    char c = text.charAt(i);
                    if (c == '[') {
                        depth++;
                        val.append(c);
                        i++;
                        continue;
                    }
                    if (c == ']') {
                        depth--;
                        val.append(c);
                        i++;
                        continue;
                    }
                    if (depth == 0 && (Character.isWhitespace(c) || c == '{' || c == '}')) break;
                    val.append(c);
                    i++;
                }
                if (!stack.isEmpty()) stack.peek().put(key, val.toString().trim());
            } else if (Character.isWhitespace(ch)) {
                // Whitespace may be part of a block name (e.g. "list-sa event { ... }"); keep it as a separator
                if (tok.length() > 0) tok.append(' ');
                i++;
            } else {
                tok.append(ch);
                i++;
            }
        }
        return out;
    }

    /**
     * Speed test: the host refreshes the sas snapshot only every 5 seconds, so we use a
     * 6.5-second window and compute upstream/downstream rates (bytes/sec) from the byte
     * deltas of the same IKE_SA child SA.
     */
    public Map<String, Object> speedTest(String ikeId) {
        String id = ikeId == null ? "" : ikeId.replaceAll("[^0-9]", "");
        if (id.isEmpty()) throw new IllegalArgumentException("Invalid IKE session ID");
        Map<String, Object> before = findById(sessions(), id);
        if (before == null) throw new IllegalArgumentException("Session does not exist or has disconnected");
        try {
            Thread.sleep(6500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, Object> after = findById(sessions(), id);
        if (after == null) throw new IllegalArgumentException("Connection dropped during the speed test");
        double up = Math.max(0, num(after.get("upload")) - num(before.get("upload"))) / 6.5;
        double down = Math.max(0, num(after.get("download")) - num(before.get("download"))) / 6.5;
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("upRate", up);
        m.put("downRate", down);
        m.put("total", up + down);
        return m;
    }

    /** One-click speed test: samples all online IKE_SAs over the same window, returns uniqueid → rates */
    public Map<String, Object> speedTestAll() {
        List<Map<String, Object>> before = sessions();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (before.isEmpty()) return out;
        try {
            Thread.sleep(6500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Map<String, Object>> after = sessions();
        for (Map<String, Object> a : after) {
            String id = str(a.get("id"));
            Map<String, Object> b = findById(before, id);
            if (b == null) continue;
            double up = Math.max(0, num(a.get("upload")) - num(b.get("upload"))) / 6.5;
            double down = Math.max(0, num(a.get("download")) - num(b.get("download"))) / 6.5;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("upRate", up);
            m.put("downRate", down);
            m.put("total", up + down);
            out.put(id, m);
        }
        return out;
    }

    private static Map<String, Object> findById(List<Map<String, Object>> list, String id) {
        for (Map<String, Object> s : list) {
            if (id.equals(str(s.get("id")))) return s;
        }
        return null;
    }

    private Map<String, Object> toSession(String conn, Map<String, Object> sa, Map<String, String> nameByIke) {
        String state = str(sa.get("state"));
        if (!"ESTABLISHED".equalsIgnoreCase(state)) return null;
        String remoteId = str(sa.get("remote-eap-id"));
        if (remoteId.isEmpty()) remoteId = str(sa.get("remote-id"));
        String clientName = nameByIke.getOrDefault(remoteId, remoteId);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", str(sa.get("uniqueid")));
        m.put("connection", conn);
        m.put("client", clientName);
        m.put("user", remoteId);
        m.put("remoteHost", str(sa.get("remote-host")));
        m.put("vpnIp", trimBrackets(str(sa.get("remote-vips"))));
        m.put("established", establishedEpoch(str(sa.get("established"))));
        m.put("state", state);
        // Cumulative TX/RX bytes from the child SA; the frontend computes real-time rates from the delta of two samples
        long up = 0;
        long down = 0;
        Object childSas = sa.get("child-sas");
        if (childSas instanceof Map<?, ?> children) {
            for (Object v : children.values()) {
                if (v instanceof Map<?, ?> child) {
                    up += num(child.get("bytes-in"));
                    down += num(child.get("bytes-out"));
                }
            }
        }
        m.put("upload", up);
        m.put("download", down);
        return m;
    }

    private static long num(Object v) {
        try {
            return Long.parseLong(str(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** In raw output, `established` is "seconds connected"; convert to epoch so the frontend can compute duration */
    private static long establishedEpoch(String v) {
        try {
            long n = Long.parseLong(v.replace("s", "").trim());
            return n > 1_000_000_000L ? n : System.currentTimeMillis() / 1000 - n;
        } catch (NumberFormatException e) {
            return System.currentTimeMillis() / 1000;
        }
    }

    private static String trimBrackets(String v) {
        String s = v.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        if (s.contains(",")) s = s.substring(0, s.indexOf(','));
        return s.trim();
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    public boolean available() {
        return Files.exists(caFile) && Files.isDirectory(usersFile.getParent());
    }

    /** Sync once at startup to avoid inconsistency between panel data and the strongSwan account table */
    @jakarta.annotation.PostConstruct
    public void init() {
        apply();
    }

    /** Only enabled clients are exported; disabling removes the account from the table, and an active session is rejected at renegotiation */
    public synchronized void apply() {
        StringBuilder sb = new StringBuilder("secrets {\n");
        int count = 0;
        for (ClientAccount c : store.clients()) {
            if (!c.enabled || isBlank(c.ikeUser) || isBlank(c.ikePass)) continue;
            sb.append("  eap-").append(tag(c.id)).append(" {\n");
            sb.append("    id = \"").append(c.ikeUser).append("\"\n");
            sb.append("    secret = \"").append(c.ikePass).append("\"\n");
            sb.append("  }\n");
            count++;
        }
        sb.append("}\n");
        try {
            Files.createDirectories(usersFile.getParent());
            Path tmp = usersFile.resolveSibling(usersFile.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, usersFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("IKEv2 accounts synced: {}", count);
        } catch (IOException e) {
            log.warn("Failed to write IKEv2 accounts: {}", e.getMessage());
        }
    }

    public byte[] caCertificate() throws IOException {
        return Files.readAllBytes(caFile);
    }

    /**
     * Generates an Apple configuration profile: CA certificate + IKEv2 connection + EAP
     * credentials bundled into one .mobileconfig. Installs on an iPhone with a single
     * tap, avoiding the "Unable to store VPN configuration" errors caused by manual entry.
     */
    public String mobileconfig(ClientAccount c, String host) throws Exception {
        String caDer = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(derOfCa());

        String uuidVpn = UUID.randomUUID().toString().toUpperCase();
        String uuidCa = UUID.randomUUID().toString().toUpperCase();
        String h = esc(host);

        return """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>PayloadContent</key>
  <array>
    <dict>
      <key>PayloadDescription</key><string>GPN IKEv2 Connection</string>
      <key>PayloadDisplayName</key><string>GPN VPN</string>
      <key>PayloadIdentifier</key><string>com.gpn.vpn.%s</string>
      <key>PayloadType</key><string>com.apple.vpn.managed</string>
      <key>PayloadUUID</key><string>%s</string>
      <key>PayloadVersion</key><integer>1</integer>
      <key>UserDefinedName</key><string>GPN VPN</string>
      <key>VPNType</key><string>IKEv2</string>
      <key>IKEv2</key>
      <dict>
        <key>RemoteAddress</key><string>%s</string>
        <key>RemoteIdentifier</key><string>%s</string>
        <key>LocalIdentifier</key><string></string>
        <key>ServerCertificateCommonName</key><string>%s</string>
        <key>AuthenticationMethod</key><string>Certificate</string>
        <key>ExtendedAuthEnabled</key><integer>1</integer>
        <key>AuthName</key><string>%s</string>
        <key>AuthPassword</key><string>%s</string>
        <key>IKESecurityAssociationParameters</key>
        <dict>
          <key>EncryptionAlgorithm</key><string>AES-256-GCM</string>
          <key>IntegrityAlgorithm</key><string>SHA2-256</string>
          <key>DiffieHellmanGroup</key><integer>19</integer>
        </dict>
        <key>ChildSecurityAssociationParameters</key>
        <dict>
          <key>EncryptionAlgorithm</key><string>AES-256-GCM</string>
          <key>IntegrityAlgorithm</key><string>SHA2-256</string>
          <key>DiffieHellmanGroup</key><integer>19</integer>
        </dict>
      </dict>
    </dict>
    <dict>
      <key>PayloadDescription</key><string>GPN Root Certificate (for server validation)</string>
      <key>PayloadDisplayName</key><string>GPN Zero Trust CA</string>
      <key>PayloadIdentifier</key><string>com.gpn.ca</string>
      <key>PayloadType</key><string>com.apple.security.root</string>
      <key>PayloadUUID</key><string>%s</string>
      <key>PayloadVersion</key><integer>1</integer>
      <key>PayloadContent</key>
      <data>
%s
      </data>
    </dict>
  </array>
  <key>PayloadDescription</key><string>One-click setup for GPN Zero-Trust VPN</string>
  <key>PayloadDisplayName</key><string>GPN Zero-Trust VPN</string>
  <key>PayloadIdentifier</key><string>com.gpn.profile.%s</string>
  <key>PayloadOrganization</key><string>GPN</string>
  <key>PayloadRemovalDisallowed</key><false/>
  <key>PayloadType</key><string>Configuration</string>
  <key>PayloadUUID</key><string>%s</string>
  <key>PayloadVersion</key><integer>1</integer>
</dict>
</plist>
""".formatted(slug(c), uuidVpn, h, h, h, esc(c.ikeUser), esc(c.ikePass), uuidCa, caDer, slug(c),
                UUID.randomUUID().toString().toUpperCase());
    }

    private byte[] derOfCa() throws Exception {
        java.security.cert.Certificate cert = java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(caCertificate()));
        return cert.getEncoded();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String slug(ClientAccount c) {
        return c.id.replace("-", "").substring(0, 12);
    }

    /** The object name in the secrets section must be a valid identifier */
    private static String tag(String id) {
        return id.replace("-", "").substring(0, 12);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
