package com.gpn.panel.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * State persistence: {data-dir}/state.json. On first startup, the Reality keypair and short_id
 * are generated automatically. Keys are generated with the JDK's built-in X25519 (equivalent to
 * `sing-box generate reality-keypair`), so everything works even when sing-box is not present
 * in the container.
 */
@Component
public class StateStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path file;
    private PanelState state = new PanelState();

    public StateStore(@Value("${panel.data-dir:/data}") String dataDir) {
        try {
            Path dir = Path.of(dataDir);
            Files.createDirectories(dir);
            this.file = dir.resolve("state.json");
            load();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize data directory: " + dataDir, e);
        }
    }

    private void load() throws IOException {
        if (Files.exists(file)) {
            PanelState loaded = mapper.readValue(file.toFile(), PanelState.class);
            if (loaded != null) state = loaded;
        }
        if (state.clients == null) state.clients = new ArrayList<>();
        boolean changed = false;
        if (state.reality == null) {
            state.reality = new PanelState.Reality();
            changed = true;
        }
        if (isBlank(state.reality.privateKey) || isBlank(state.reality.publicKey)) {
            generateRealityKey();
            changed = true;
        }
        if (isBlank(state.reality.shortId)) {
            state.reality.shortId = randomHex(8);
            changed = true;
        }
        // Backfill IKEv2 credentials for legacy data
        for (ClientAccount c : state.clients) {
            if (isBlank(c.ikeUser) || isBlank(c.ikePass)) {
                fillIkeCredential(c);
                changed = true;
            }
        }
        if (changed) save();
    }

    /** iOS native VPN uses EAP-MSCHAPv2; the username has a fixed prefix for easy identification, and the password allows only printable ASCII */
    private void fillIkeCredential(ClientAccount c) {
        c.ikeUser = "gpn-" + randomHex(3);
        c.ikePass = randomPassword(16);
    }

    private static String randomPassword(int len) {
        final String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return sb.toString();
    }

    private void generateRealityKey() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            state.reality.privateKey = base64Url(tail32(kp.getPrivate().getEncoded()));
            state.reality.publicKey = base64Url(tail32(kp.getPublic().getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate Reality keypair", e);
        }
    }

    /** The last 32 bytes of the PKCS#8 / X.509 encoding are the raw X25519 key; Reality needs the raw key in base64url */
    private static byte[] tail32(byte[] encoded) {
        byte[] out = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, out, 0, 32);
        return out;
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public synchronized PanelState state() {
        return state;
    }

    public synchronized List<ClientAccount> clients() {
        return state.clients;
    }

    public synchronized ClientAccount find(String id) {
        for (ClientAccount c : state.clients) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    public synchronized ClientAccount findBySubToken(String token) {
        for (ClientAccount c : state.clients) {
            if (c.subToken.equals(token)) return c;
        }
        return null;
    }

    public synchronized ClientAccount create(String name) {
        ClientAccount c = new ClientAccount();
        c.id = UUID.randomUUID().toString();
        c.name = name;
        c.uuid = UUID.randomUUID().toString();
        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        c.subToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        c.enabled = true;
        c.createdAt = System.currentTimeMillis();
        fillIkeCredential(c);
        state.clients.add(c);
        save();
        return c;
    }

    public synchronized void rotate(ClientAccount c) {
        c.uuid = UUID.randomUUID().toString();
        c.ikePass = randomPassword(16);
        save();
    }

    public synchronized void delete(String id) {
        state.clients.removeIf(c -> c.id.equals(id));
        save();
    }

    public synchronized void save() {
        try {
            Path tmp = file.resolveSibling("state.json.tmp");
            mapper.writeValue(tmp.toFile(), state);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write state: " + e.getMessage(), e);
        }
    }

    public Path file() {
        return file;
    }

    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
