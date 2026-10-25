package ru.pulsedoma.bootstrap;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.pulsedoma.issues.OrganizationDemoService;

@Configuration
@ConditionalOnProperty(
        name = "organization-demo.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OrganizationDemoData {
    @Bean
    @Order(50)
    ApplicationRunner organizationDemonstration(JdbcTemplate jdbc, OrganizationDemoService demo) {
        return args -> {
            if (jdbc.queryForObject(
                            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='organization_demo_fixtures'",
                            Integer.class)
                    == 0) return;
            if (jdbc.queryForObject(
                            "SELECT count(*) FROM houses WHERE id='demo-house-1'", Integer.class)
                    > 0) {
                String worker =
                        "scenario-"
                                + java.util.UUID.nameUUIDFromBytes(
                                        "pulse-final-v3:demo-house-1:worker"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jdbc.update(
                        "INSERT OR IGNORE INTO users(id,display_name) VALUES (?,'Алексей Морозов')",
                        worker);
                demo.seed(
                        "demo-house-1",
                        "demo-resident-1",
                        "demo-admin-1",
                        "demo-uk-1",
                        worker,
                        false);
            }
            for (var session :
                    jdbc.queryForList("SELECT * FROM guided_demo_sessions WHERE active=1")) {
                String user = (String) session.get("user_id");
                demo.seed(
                        (String) session.get("house_id"),
                        user,
                        user,
                        (String) session.get("organization_id"),
                        user,
                        true);
            }
        };
    }
}
