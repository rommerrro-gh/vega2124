package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxInitDataFilterTest {
    @Test
    void acceptsSignedInitDataAndRejectsTampering() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE users(id TEXT PRIMARY KEY, max_user_id TEXT UNIQUE,
                                   display_name TEXT NOT NULL, status TEXT NOT NULL)
                """);
        MaxInitDataFilter filter = new MaxInitDataFilter(jdbc, new ObjectMapper(), new MockEnvironment(),
                "test-bot-token", "", "", "");
        String user = "{\"id\":123,\"first_name\":\"Иван\",\"last_name\":\"Петров\"}";
        String date = Long.toString(Instant.now().getEpochSecond());
        String data = "auth_date=" + date + "\nuser=" + user;
        byte[] key = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), "test-bot-token");
        String hash = HexFormat.of().formatHex(hmac(key, data));
        String initData = "auth_date=" + date + "&user=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                + "&hash=" + hash;

        MockHttpServletRequest valid = new MockHttpServletRequest("GET", "/v1/me/houses");
        valid.addHeader("X-Max-Init-Data", initData);
        MockHttpServletResponse validResponse = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(valid, validResponse, (request, response) -> called.set(true));
        assertTrue(called.get());
        assertEquals(200, validResponse.getStatus());
        assertEquals("Иван Петров", jdbc.queryForObject(
                "SELECT display_name FROM users WHERE max_user_id = '123'", String.class));

        MockHttpServletRequest tampered = new MockHttpServletRequest("GET", "/v1/me/houses");
        tampered.addHeader("X-Max-Init-Data", initData.replace("123", "124"));
        MockHttpServletResponse denied = new MockHttpServletResponse();
        called.set(false);
        filter.doFilter(tampered, denied, (request, response) -> called.set(true));
        assertEquals(401, denied.getStatus());
        assertTrue(!called.get());
        dataSource.destroy();
    }

    private static byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
}
