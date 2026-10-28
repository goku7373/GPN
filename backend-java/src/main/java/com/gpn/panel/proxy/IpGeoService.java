package com.gpn.panel.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public IP geolocation lookup: prefers overseas public APIs (ipwho.is, no key required),
 * falls back to api.ip.sb on failure, avoiding reliance on domestic vendor APIs.
 * Results are cached for 6 hours, so a 5-second refresh interval won't repeatedly hit external services.
 */
@Service
public class IpGeoService {

    private static final Logger log = LoggerFactory.getLogger(IpGeoService.class);
    private static final long TTL = 6 * 3600_000L;
    private static final Pattern REGION = Pattern.compile("\"region\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CITY = Pattern.compile("\"city\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public String lookup(String ip) {
        if (ip == null || ip.isBlank()) return "";
        if (isPrivate(ip)) return "LAN";
        Entry e = cache.get(ip);
        if (e != null && System.currentTimeMillis() - e.at < TTL) return e.region;
        String region = fetch("https://ipwho.is/" + ip);
        if (region.isEmpty()) region = fetch("https://api.ip.sb/geoip/" + ip);
        cache.put(ip, new Entry(region, System.currentTimeMillis()));
        return region;
    }

    private String fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "GPN-Panel/1.0")
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            String text = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            if (text.contains("\"success\":false") || text.contains("\"success\": false")) return "";
            return combine(group(REGION, text), group(CITY, text));
        } catch (Exception e) {
            log.debug("IP geolocation lookup failed {}: {}", url, e.getMessage());
            return "";
        }
    }

    private static String combine(String region, String city) {
        if (region.isEmpty()) return city;
        if (city.isEmpty() || city.equalsIgnoreCase(region)) return region;
        return region + " · " + city;
    }

    private static String group(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static boolean isPrivate(String ip) {
        if (ip.startsWith("10.") || ip.startsWith("192.168.")
                || ip.startsWith("127.") || ip.startsWith("fe80")) return true;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private record Entry(String region, long at) {}
}
