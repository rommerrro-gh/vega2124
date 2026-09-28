package ru.pulsedoma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class IssueReorganizationTest {
    private static final Path DB = database();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }

    private static Path database() {
        try { return Files.createTempFile("pulse-reorganization-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void residentWithdrawsOnlyOwnReportAndLastReportRemovesDraftIssue() throws Exception {
        String unlinked = report("demo-house-1", "Лампа в коридоре");
        mvc.perform(post("/v1/reports/" + unlinked + "/withdraw").header("X-Demo-Session", "admin")
                .contentType("application/json").content("{\"reason\":\"Ошибка\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/reports/" + unlinked + "/withdraw").header("X-Demo-Session", "true")
                .contentType("application/json").content("{\"reason\":\"Ошибка\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/v1/reports/" + unlinked + "/withdraw").header("X-Demo-Session", "true")
                .contentType("application/json").content("{\"reason\":\"Ошибка\"}"))
                .andExpect(status().isConflict());
        assertNotNull(jdbc.queryForObject("SELECT withdrawn_at FROM reports WHERE id = ?", String.class, unlinked));

        String first = report("demo-house-1", "Не горит свет в подъезде");
        String issueId = createIssue(first);
        mvc.perform(post("/v1/reports/" + first + "/withdraw").header("X-Demo-Session", "true")
                .contentType("application/json").content("{\"reason\":\"Создано по ошибке\"}"))
                .andExpect(status().isNoContent());
        assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT status FROM issues WHERE id = ?", String.class, issueId));
        assertEquals(0, count("SELECT COUNT(*) FROM issue_participants WHERE issue_id = ?", issueId));
        assertEquals(1, count("SELECT COUNT(*) FROM issue_reports WHERE issue_id = ? AND unlinked_at IS NOT NULL", issueId));
        assertEquals(0, count("SELECT COUNT(*) FROM issue_reports WHERE issue_id = ? AND unlinked_at IS NULL", issueId));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'REPORT_WITHDRAWN' AND entity_id = ?", first));
    }

    @Test
    void withdrawalKeepsOtherReportsAndSendsStartedIssueForDispatcherReview() throws Exception {
        String first = report("demo-house-1", "Течёт труба у входа");
        String issueId = createIssue(first);
        changeStatus(issueId, "OPEN");
        String second = report("demo-house-1", "Течёт труба в подъезде");
        join(issueId, second);
        withdraw(first);
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM issues WHERE id = ?", String.class, issueId));
        assertEquals(1, count("SELECT COUNT(*) FROM issue_participants WHERE issue_id = ?", issueId));
        assertEquals(1, count("SELECT COUNT(*) FROM issue_reports WHERE issue_id = ? AND unlinked_at IS NULL", issueId));
        changeStatus(issueId, "ASSIGNED");
        changeStatus(issueId, "IN_PROGRESS");
        withdraw(second);
        assertEquals("REVIEW_REQUIRED", jdbc.queryForObject("SELECT status FROM issues WHERE id = ?", String.class, issueId));
        assertFalse(json(mvc.perform(get("/v1/dispatcher/issues").param("houseId", "demo-house-1")
                .header("X-Demo-Session", "dispatcher")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEmpty());
        changeStatus(issueId, "WITHDRAWN");
    }

    @Test
    void dispatcherMergesOnlyActiveIssuesInOwnHouse() throws Exception {
        String sourceReport = report("demo-house-1", "Сломана лампа у лифта");
        String source = createIssue(sourceReport);
        String targetReport = report("demo-house-1", "Сломана лампа в подъезде");
        String target = createIssue(targetReport);
        changeStatus(target, "OPEN");
        String body = mapper.writeValueAsString(Map.of("targetIssueId", target));
        mvc.perform(post("/v1/issues/" + source + "/merge").header("X-Demo-Session", "true")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/issues/" + source + "/merge").header("X-Demo-Session", "admin")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/issues/" + source + "/merge").header("X-Demo-Session", "dispatcher")
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("targetIssueId", source))))
                .andExpect(status().isBadRequest());
        String other = createIssue(report("demo-house-2", "Свет не работает"));
        mvc.perform(post("/v1/issues/" + source + "/merge").header("X-Demo-Session", "dispatcher")
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("targetIssueId", other))))
                .andExpect(status().isBadRequest());
        JsonNode merged = json(mvc.perform(post("/v1/issues/" + source + "/merge")
                .header("X-Demo-Session", "dispatcher").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(target, merged.path("id").asText());
        assertEquals(2, merged.path("reports").size());
        assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT status FROM issues WHERE id = ?", String.class, source));
        assertEquals(0, count("SELECT COUNT(*) FROM issue_reports WHERE issue_id = ? AND unlinked_at IS NULL", source));
        assertEquals(1, count("SELECT COUNT(*) FROM issue_reports WHERE issue_id = ? AND unlinked_at IS NOT NULL", source));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'ISSUES_MERGED' AND entity_id = ?", source));
    }

    @Test
    void dispatcherSplitsOneReportIntoNewIssue() throws Exception {
        String first = report("demo-house-1", "Не горит лампа на этаже");
        String source = createIssue(first);
        changeStatus(source, "OPEN");
        String second = report("demo-house-1", "Не работает свет у лифта", "Подъезд 2");
        join(source, second);
        String body = mapper.writeValueAsString(Map.of("reportId", first, "reason", "Другая зона"));
        mvc.perform(post("/v1/issues/" + source + "/split").header("X-Demo-Session", "true")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        JsonNode result = json(mvc.perform(post("/v1/issues/" + source + "/split")
                .header("X-Demo-Session", "dispatcher").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String newIssue = result.path("id").asText();
        assertEquals("DRAFT", result.path("status").asText());
        assertEquals(first, result.path("reports").get(0).path("id").asText());
        assertEquals("Подъезд 1", result.path("location").asText());
        assertEquals("Подъезд 2", json(mvc.perform(get("/v1/dispatcher/issues/" + source)
                .header("X-Demo-Session", "dispatcher")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).path("location").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM issue_reports WHERE issue_id = ? AND unlinked_at IS NULL", source));
        assertEquals(1, count("SELECT COUNT(*) FROM issue_reports WHERE issue_id = ? AND unlinked_at IS NULL", newIssue));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'ISSUE_SPLIT' AND entity_id = ?", source));
        mvc.perform(post("/v1/issues/" + source + "/split")
                .header("X-Demo-Session", "dispatcher").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reorganizationStopsWhenResultVerificationBegins() throws Exception {
        String first = report("demo-house-1", "Не работает освещение в коридоре");
        String source = createIssue(first);
        changeStatus(source, "OPEN");
        String second = report("demo-house-1", "Не работает освещение рядом с лифтом");
        join(source, second);
        String target = createIssue(report("demo-house-1", "Не работает освещение в подъезде"));
        changeStatus(target, "OPEN");
        changeStatus(source, "ASSIGNED");
        changeStatus(source, "IN_PROGRESS");
        changeStatus(source, "RESOLVED");
        mvc.perform(post("/v1/reports/" + first + "/withdraw").header("X-Demo-Session", "true")
                .contentType("application/json").content("{\"reason\":\"Ошибка\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/v1/issues/" + source + "/split").header("X-Demo-Session", "dispatcher")
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("reportId", second, "reason", "Другая зона"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/v1/issues/" + source + "/merge").header("X-Demo-Session", "dispatcher")
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("targetIssueId", target))))
                .andExpect(status().isConflict());
        assertEquals(2, count("SELECT COUNT(*) FROM issue_reports WHERE issue_id = ? AND unlinked_at IS NULL", source));
    }

    private String report(String houseId, String text) throws Exception {
        return report(houseId, text, "Подъезд 1");
    }

    private String report(String houseId, String text, String location) throws Exception {
        return json(mvc.perform(post("/v1/reports").header("X-Demo-Session", "true")
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("houseId", houseId, "category", "LIGHTING",
                        "text", text, "location", location, "occurredAt", "2026-09-20T10:00:00Z"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("reportId").asText();
    }

    private String createIssue(String reportId) throws Exception {
        return json(mvc.perform(post("/v1/issues").header("X-Demo-Session", "true")
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("reportId", reportId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asText();
    }

    private void join(String issueId, String reportId) throws Exception {
        mvc.perform(post("/v1/issues/" + issueId + "/join").header("X-Demo-Session", "true")
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("reportId", reportId))))
                .andExpect(status().isOk());
    }

    private void withdraw(String reportId) throws Exception {
        mvc.perform(post("/v1/reports/" + reportId + "/withdraw").header("X-Demo-Session", "true")
                .contentType("application/json").content("{\"reason\":\"Ошибка\"}"))
                .andExpect(status().isNoContent());
    }

    private void changeStatus(String issueId, String next) throws Exception {
        mvc.perform(patch("/v1/issues/" + issueId + "/status").header("X-Demo-Session", "dispatcher")
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("status", next, "reason", "Проверено"))))
                .andExpect(status().isOk());
    }

    private int count(String sql, String id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private JsonNode json(byte[] bytes) throws Exception { return mapper.readTree(bytes); }
}
