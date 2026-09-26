package ru.pulsedoma.bootstrap;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("demo")
public class DemoDataConfig {
    @Bean
    ApplicationRunner demoData(JdbcTemplate jdbc) {
        return args -> {
            jdbc.update("INSERT OR IGNORE INTO users(id, display_name, status) VALUES ('demo-resident-1', 'Демо-житель', 'ACTIVE')");
            jdbc.update("INSERT OR IGNORE INTO users(id, display_name, status) VALUES ('demo-dispatcher-1', 'Демо-диспетчер', 'ACTIVE')");
            jdbc.update("INSERT OR IGNORE INTO houses(id, address) VALUES ('demo-house-1', 'Казань, ул. Баумана, 12')");
            jdbc.update("INSERT OR IGNORE INTO houses(id, address) VALUES ('demo-house-2', 'Казань, ул. Пушкина, 7')");
            jdbc.update("""
                    INSERT OR IGNORE INTO house_memberships(house_id, user_id, role, verification_status)
                    VALUES ('demo-house-1', 'demo-resident-1', 'RESIDENT', 'VERIFIED'),
                           ('demo-house-2', 'demo-resident-1', 'RESIDENT', 'VERIFIED')
                    """);
            jdbc.update("""
                    INSERT OR IGNORE INTO house_memberships(house_id, user_id, role, verification_status)
                    VALUES ('demo-house-1', 'demo-dispatcher-1', 'DISPATCHER', 'VERIFIED')
                    """);
        };
    }
}
