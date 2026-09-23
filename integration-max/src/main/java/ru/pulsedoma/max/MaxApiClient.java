package ru.pulsedoma.max;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public final class MaxApiClient {
    private final WebClient client;
    private final String token;

    public MaxApiClient(WebClient.Builder builder, @Value("${max.api.token:}") String token) {
        this.client = builder.baseUrl("https://platform-api2.max.ru").build();
        this.token = token;
    }

    public Mono<String> getMe() {
        return client.get().uri("/me").header("Authorization", token).retrieve().bodyToMono(String.class);
    }
}
