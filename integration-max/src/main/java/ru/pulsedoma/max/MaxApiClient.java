package ru.pulsedoma.max;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

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
}
