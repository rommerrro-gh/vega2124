package ru.pulsedoma;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import ru.pulsedoma.issues.*;
import ru.pulsedoma.issues.OrganizationTypes.*;

@SpringBootTest(properties = "organization-demo.enabled=true")
@ActiveProfiles("demo")
class OrganizationDemoFlowTest {
    static final Path DB = temp();

    static Path temp() {
        try {
            return Files.createTempFile("organization-demo-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }

    @Autowired OrganizationDemoService demo;
    @Autowired OrganizationWorkspaceService teams;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoScenarioService scenarios;

    @Test
    void fixtureIncludesIndependentTeamsInspectionAndRepairAndIsRepeatable() {
        var before = jdbc.queryForObject("SELECT count(*) FROM resident_cases", Integer.class);
        String worker =
                "scenario-"
                        + java.util.UUID.nameUUIDFromBytes(
                                "pulse-final-v3:demo-house-1:worker"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        demo.seed("demo-house-1", "demo-resident-1", "demo-admin-1", "demo-uk-1", worker, false);
        assertEquals(
                before, jdbc.queryForObject("SELECT count(*) FROM resident_cases", Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM organization_case_transfers", Integer.class));
        assertTrue(
                teams.mine(worker).stream()
                        .anyMatch(
                                w ->
                                        w.kind() == Kind.DEVELOPER
                                                && w.functions().contains(Function.ENGINEER)));
        var w =
                teams.mine(worker).stream()
                        .filter(x -> x.kind() == Kind.GENERAL_CONTRACTOR)
                        .findFirst()
                        .orElseThrow();
        assertEquals(1, teams.tasks(w.id(), TeamRole.SPECIALIST, worker).size());
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
    }

    @Test
    void explicitRefreshRebuildsWorkspacesAndFutureVisitsWithoutNotifications() {
        scenarios.refresh("demo-house-1", "demo-resident-1", "demo-dispatcher-1", "demo-uk-1");
        String worker =
                "scenario-"
                        + java.util.UUID.nameUUIDFromBytes(
                                "pulse-final-v3:demo-house-1:worker"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        demo.seedAfterScenarioReset(
                "demo-house-1", "demo-resident-1", "demo-admin-1", "demo-uk-1", worker, false);
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM organization_case_transfers", Integer.class));
        assertEquals(
                2, jdbc.queryForObject("SELECT count(*) FROM case_work_orders", Integer.class));
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM visit_slots WHERE julianday(starts_at)<=julianday('now')",
                        Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM max_bot_outbox", Integer.class));
        assertThrows(
                IllegalStateException.class,
                () ->
                        demo.seedAfterScenarioReset(
                                "demo-house-1",
                                "demo-resident-1",
                                "demo-admin-1",
                                "demo-uk-1",
                                worker,
                                false));
    }
}
