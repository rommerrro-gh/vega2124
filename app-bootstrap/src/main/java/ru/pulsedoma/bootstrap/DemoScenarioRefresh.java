package ru.pulsedoma.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.DemoScenarioService;

/** One-off maintenance command. No HTTP endpoint and no automatic refresh on normal startup. */
@Component
@Order(1000)
@ConditionalOnProperty(name = "demo-scenarios.refresh", havingValue = "true")
public class DemoScenarioRefresh implements ApplicationRunner {
    private final org.springframework.core.env.Environment environment;
    private final org.springframework.context.ConfigurableApplicationContext context;
    private final JdbcTemplate db;
    private final DemoScenarioService scenarios;

    public DemoScenarioRefresh(
            JdbcTemplate db,
            DemoScenarioService scenarios,
            org.springframework.core.env.Environment environment,
            org.springframework.context.ConfigurableApplicationContext context) {
        this.db = db;
        this.scenarios = scenarios;
        this.environment = environment;
        this.context = context;
    }

    public void run(ApplicationArguments args) {
        if (environment.getProperty("demo-scenarios.reset-all-test-data", Boolean.class, false))
            scenarios.resetAllBusinessData();
        int count = 0;
        if (db.queryForObject("SELECT count(*) FROM houses WHERE id='demo-house-1'", Integer.class)
                > 0) {
            scenarios.refresh("demo-house-1", "demo-resident-1", "demo-dispatcher-1", "demo-uk-1");
            count++;
        }
        for (var row :
                db.queryForList(
                        "SELECT house_id,user_id,organization_id FROM guided_demo_sessions WHERE active=1")) {
            scenarios.refresh(
                    (String) row.get("house_id"),
                    (String) row.get("user_id"),
                    (String) row.get("user_id"),
                    (String) row.get("organization_id"));
            count++;
        }
        System.out.println("DEMO_SCENARIOS_REFRESHED=" + count);
        if (environment.getProperty("demo-scenarios.exit-after-refresh", Boolean.class, false))
            System.exit(org.springframework.boot.SpringApplication.exit(context, () -> 0));
    }
}
