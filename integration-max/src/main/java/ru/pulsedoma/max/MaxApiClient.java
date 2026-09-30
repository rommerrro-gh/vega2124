package ru.pulsedoma.max;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.List;

@Component
public final class MaxApiClient {
    private final WebClient client;
    private final String token;

    public MaxApiClient(WebClient.Builder builder, @Value("${max.api.token:}") String token,
                        @Value("${max.api.base-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
        this.token = token;
    }

    public Mono<String> getMe() {
        return client.get().uri("/me").header("Authorization", token).retrieve().bodyToMono(String.class);
    }

    public Mono<String> sendText(String userId, String text) {
        return client.post().uri(builder -> builder.path("/messages").queryParam("user_id", userId).build())
                .header("Authorization", token).bodyValue(Map.of("text", text))
                .retrieve().bodyToMono(String.class);
    }

    public Mono<String> sendWelcome(String userId, String botUsername, String text) {
        Map<String, Object> body = botUsername.isBlank() ? Map.of("text", text) : Map.of(
                "text", text,
                "attachments", List.of(Map.of("type", "inline_keyboard", "payload", Map.of(
                        "buttons", List.of(List.of(Map.of("type", "open_app", "text", "Открыть Пульс дома",
                                "web_app", botUsername)))))));
        return client.post().uri(builder -> builder.path("/messages").queryParam("user_id", userId).build())
                .header("Authorization", token).bodyValue(body).retrieve().bodyToMono(String.class);
    }
}
