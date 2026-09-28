package ru.pulsedoma.bootstrap;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ru.pulsedoma.common.CorrelationIdFilter;

@Configuration
public class SecurityConfig {
    private final MaxInitDataFilter maxInitDataFilter;

    public SecurityConfig(JdbcTemplate jdbc, ObjectMapper mapper, Environment environment,
                          @Value("${max.api.token:}") String botToken,
                          @Value("${miniapp.demo-user-id:}") String demoUserId,
                          @Value("${miniapp.demo-dispatcher-id:}") String demoDispatcherId,
                          @Value("${miniapp.demo-admin-id:}") String demoAdminId) {
        this.maxInitDataFilter = new MaxInitDataFilter(jdbc, mapper, environment, botToken,
                demoUserId, demoDispatcherId, demoAdminId);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/max").permitAll()
                        .requestMatchers("/v1/**").authenticated()
                        .requestMatchers("/").permitAll()
                        .requestMatchers("/miniapp/**").permitAll()
                        .requestMatchers("/dispatcher/**").permitAll()
                        .requestMatchers("/admin/**").permitAll()
                        .requestMatchers("/system/**", "/uk/**").permitAll()
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(maxInitDataFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new CorrelationIdFilter(), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
