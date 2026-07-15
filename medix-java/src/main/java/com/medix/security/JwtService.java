package com.medix.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final ObjectMapper mapper;
    private final byte[] secret;
    private final String configuredSecret;
    private final long ttlSeconds;
    @Value("${medix.security.production:false}") private boolean production;
    public JwtService(@Value("${medix.security.jwt-secret:dev-only-change-this-secret-32bytes}") String secret,
                      @Value("${medix.security.jwt-ttl-seconds:28800}") long ttlSeconds) {
        this.mapper = new ObjectMapper(); this.configuredSecret=secret; this.secret = secret.getBytes(StandardCharsets.UTF_8); this.ttlSeconds = ttlSeconds;
    }
    @PostConstruct void validateConfiguration(){if(production&&"dev-only-change-this-secret-32bytes".equals(configuredSecret))throw new IllegalStateException("MEDIX_JWT_SECRET must be set in production");}
    public String issue(AppPrincipal principal) {
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        try {
            String payload = encode(mapper.writeValueAsBytes(Map.of("sub", principal.username(), "exp", Instant.now().getEpochSecond() + ttlSeconds)));
            return header + "." + payload + "." + sign(header + "." + payload);
        } catch (Exception e) { throw new IllegalStateException("Unable to issue authentication token"); }
    }
    public String verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !constantTime(parts[2], sign(parts[0] + "." + parts[1]))) return null;
            @SuppressWarnings("unchecked") Map<String,Object> claims = mapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            if (((Number) claims.get("exp")).longValue() <= Instant.now().getEpochSecond()) return null;
            return (String) claims.get("sub");
        } catch (Exception ignored) { return null; }
    }
    private String sign(String value) throws Exception { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256")); return encode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
    private String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private boolean constantTime(String a, String b) { return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
}
