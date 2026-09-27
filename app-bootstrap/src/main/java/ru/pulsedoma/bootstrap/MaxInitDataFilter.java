package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Verifies MAX WebApp.initData on every API request; never trusts initDataUnsafe. */
public class MaxInitDataFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String botToken;
    private final String demoUserId;
    private final String demoDispatcherId;
    private final Environment environment;

    public MaxInitDataFilter(JdbcTemplate jdbc, ObjectMapper mapper, Environment environment,
                             @Value("${max.api.token:}") String botToken,
                             @Value("${miniapp.demo-user-id:}") String demoUserId,
                             @Value("${miniapp.demo-dispatcher-id:}") String demoDispatcherId) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.environment = environment;
        this.botToken = botToken;
        this.demoUserId = demoUserId;
        this.demoDispatcherId = demoDispatcherId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String userId;
            if (environment.acceptsProfiles(Profiles.of("demo")) && !demoUserId.isBlank()
                    && "true".equals(request.getHeader("X-Demo-Session"))) {
                userId = demoUserId;
            } else if (environment.acceptsProfiles(Profiles.of("demo")) && !demoDispatcherId.isBlank()
                    && "dispatcher".equals(request.getHeader("X-Demo-Session"))) {
                userId = demoDispatcherId;
            } else {
                VerifiedMaxUser maxUser = verify(request.getHeader("X-Max-Init-Data"));
                jdbc.update("""
                        INSERT INTO users(id, max_user_id, display_name, status)
                        VALUES (?, ?, ?, 'ACTIVE')
                        ON CONFLICT(max_user_id) DO UPDATE SET display_name = excluded.display_name
                        WHERE excluded.display_name != 'MAX user'
                          AND users.display_name != excluded.display_name
                        """, java.util.UUID.randomUUID().toString(), maxUser.id(), maxUser.displayName());
                List<String> users = jdbc.query("SELECT id FROM users WHERE max_user_id = ? AND status = 'ACTIVE'",
                        (rs, row) -> rs.getString(1), maxUser.id());
                if (users.isEmpty()) throw new IllegalArgumentException("MAX user has no account");
                userId = users.get(0);
            }
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            if (!response.isCommitted()) response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    private record VerifiedMaxUser(String id, String displayName) {}

    private VerifiedMaxUser verify(String initData) throws Exception {
        if (botToken.isBlank() || initData == null || initData.length() > 8192) {
            throw new IllegalArgumentException("MAX initData missing");
        }
        Map<String, String> values = new HashMap<>();
        for (String pair : initData.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || values.putIfAbsent(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null) {
                throw new IllegalArgumentException("Invalid MAX initData");
            }
        }
        String supplied = values.remove("hash");
        long authDate = Long.parseLong(values.getOrDefault("auth_date", "0"));
        long age = Instant.now().getEpochSecond() - authDate;
        if (supplied == null || supplied.length() != 64 || age < -60 || age > 3600) {
            throw new IllegalArgumentException("Expired MAX initData");
        }
        String data = values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"));
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken);
        byte[] expected = hmac(secret, data);
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(supplied))) {
            throw new IllegalArgumentException("Invalid MAX signature");
        }
        var user = mapper.readTree(values.get("user"));
        String maxUserId = user.path("id").asText();
        if (maxUserId.isBlank()) throw new IllegalArgumentException("MAX user missing");
        String firstName = user.path("first_name").asText("").trim();
        String lastName = user.path("last_name").asText("").trim();
        String displayName = (firstName + " " + lastName).trim();
        return new VerifiedMaxUser(maxUserId, displayName.isBlank() ? "MAX user" : displayName);
    }

    private static byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
}
