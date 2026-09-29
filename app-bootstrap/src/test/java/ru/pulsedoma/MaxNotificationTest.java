package ru.pulsedoma;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import ru.pulsedoma.max.MaxApiClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxNotificationTest {
    @Test
    void sendsVerificationTextToMaxUser() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            byte[] response = "{\"message\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            MaxApiClient client = new MaxApiClient(WebClient.builder(), "test-token",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            client.sendText("123456", "Проверьте заявку").block(Duration.ofSeconds(5));
            assertTrue(request.get().startsWith("POST /messages?user_id=123456 test-token "));
            assertTrue(request.get().contains("\"text\":\"Проверьте заявку\""));
            client.sendWelcome("123456", "t802_hakaton_max_bot", "Добро пожаловать")
                    .block(Duration.ofSeconds(5));
            assertTrue(request.get().contains("\"type\":\"inline_keyboard\""));
            assertTrue(request.get().contains("\"type\":\"open_app\""));
            assertTrue(request.get().contains("\"web_app\":\"t802_hakaton_max_bot\""));
        } finally {
            server.stop(0);
        }
    }
}
