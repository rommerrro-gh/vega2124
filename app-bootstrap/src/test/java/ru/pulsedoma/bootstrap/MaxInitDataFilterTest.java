package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaxInitDataFilterTest {
    @Test
    void acceptsSignedInitDataAndRejectsTampering() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                .thenReturn(List.of("resident-1"));
        MaxInitDataFilter filter = new MaxInitDataFilter(jdbc, new ObjectMapper(), new MockEnvironment(),
                "test-bot-token", "", "");
        String user = "{\"id\":123}";
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

        MockHttpServletRequest tampered = new MockHttpServletRequest("GET", "/v1/me/houses");
        tampered.addHeader("X-Max-Init-Data", initData.replace("123", "124"));
        MockHttpServletResponse denied = new MockHttpServletResponse();
        called.set(false);
        filter.doFilter(tampered, denied, (request, response) -> called.set(true));
        assertEquals(401, denied.getStatus());
        assertTrue(!called.get());
    }

    private static byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
}
