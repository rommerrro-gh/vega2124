package ru.pulsedoma;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.issues.*;
import ru.pulsedoma.issues.ReportCategory;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Transactional
class ProfilePreferencesFlowTest {
    static final Path DB = temp();

    static Path temp() {
        try {
            return Files.createTempFile("profile-preferences-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        r.add("operations.scheduler-enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ProblemIntakeService intake;
    @Autowired ResidentCaseService cases;

    @Test
    void preferencesBelongToCurrentActorAndControlOnlyCaseNotifications() throws Exception {
        mvc.perform(get("/v1/me/profile").header("X-Demo-Session", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Роман Иванов"));
        mvc.perform(
                        put("/v1/me/preferences")
                                .header("X-Demo-Session", "true")
                                .contentType("application/json")
                                .content("{\"caseUpdates\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseUpdates").value(false));
        mvc.perform(get("/v1/me/preferences").header("X-Demo-Session", "dispatcher"))
                .andExpect(jsonPath("$.caseUpdates").value(true));
        db.update("UPDATE users SET max_user_id='189000111' WHERE id='demo-resident-1'");
        var c =
                intake.create(
                        new ProblemIntakeService.Input(
                                "demo-house-1",
                                "Не закрывается входная дверь",
                                ReportCategory.OTHER,
                                null,
                                null),
                        CaseKind.PROBLEM,
                        UUID.randomUUID().toString(),
                        "demo-resident-1");
        c =
                cases.update(
                        c.id(),
                        c.kind(),
                        CaseStatus.IN_PROGRESS,
                        "Мастер проверит замок",
                        null,
                        "demo-dispatcher-1",
                        c.revision(),
                        "Назначена проверка",
                        "demo-dispatcher-1");
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM max_bot_outbox WHERE case_id=?",
                        Integer.class,
                        c.id()));
        mvc.perform(
                        put("/v1/me/preferences")
                                .header("X-Demo-Session", "true")
                                .contentType("application/json")
                                .content("{\"caseUpdates\":true}"))
                .andExpect(status().isOk());
        cases.update(
                c.id(),
                c.kind(),
                CaseStatus.IN_PROGRESS,
                "Мастер выехал",
                null,
                "demo-dispatcher-1",
                c.revision(),
                "Уточнён следующий шаг",
                "demo-dispatcher-1");
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM max_bot_outbox WHERE case_id=?",
                        Integer.class,
                        c.id()));
        mvc.perform(
                        put("/v1/me/preferences")
                                .header("X-Demo-Session", "true")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pageAddressesReloadWithoutGrantingApiAccess() throws Exception {
        for (String page :
                new String[] {
                    "home", "profile", "settings", "new", "cases/example", "cases/example/visits"
                })
            mvc.perform(get("/miniapp/" + page))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/miniapp/index.html"));
        mvc.perform(get("/dispatcher/queue"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/dispatcher/index.html"));
        mvc.perform(get("/v1/me/profile")).andExpect(status().isUnauthorized());
    }
}
