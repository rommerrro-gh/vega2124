package ru.pulsedoma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;
import ru.pulsedoma.bootstrap.VerificationJobs;
import ru.pulsedoma.max.MaxApiClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class PlannedDateFlowTest {
    private static final Path DB = database();

    private static Path database() {
        try { return Files.createTempFile("pulse-planned-date-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void dispatcherSetsDateAndResidentsSeeEveryChange() throws Exception {
        String issueId = createIssue();
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Moscow"));
        LocalDate first = today.plusDays(1);
        LocalDate next = today.plusDays(3);
        jdbc.update("UPDATE users SET max_user_id = 'test-max-user' WHERE id = 'demo-resident-1'");

        mvc.perform(patch("/v1/issues/" + issueId + "/planned-date")
                .header("X-Demo-Session", "true").contentType("application/json")
                .content(body(first, ""))).andExpect(status().isForbidden());
        mvc.perform(patch("/v1/issues/" + issueId + "/planned-date")
                .header("X-Demo-Session", "dispatcher").contentType("application/json")
                .content(body(today.minusDays(1), ""))).andExpect(status().isBadRequest());

        JsonNode initial = change(issueId, first, "");
        assertEquals(first.toString(), initial.path("plannedDate").asText());
        assertEquals(1, initial.path("plannedDateHistory").size());
        assertTrue(initial.path("plannedDateHistory").get(0).path("previousDate").isNull());
        String context = "Заявка «Сломан светильник у лифта».\nДом: " + initial.path("address").asText()
                + "\nМесто: Первый подъезд\n";
        DateTimeFormatter displayDate = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        assertEquals(context + "Установлен плановый срок: " + first.format(displayDate) + ".",
                jdbc.queryForObject("SELECT body FROM notification_outbox WHERE issue_id = ?", String.class, issueId));
        mvc.perform(patch("/v1/issues/" + issueId + "/planned-date")
                .header("X-Demo-Session", "dispatcher").contentType("application/json")
                .content(body(next, ""))).andExpect(status().isBadRequest());
        JsonNode changed = change(issueId, next, "Ожидаем запчасть");
        assertEquals(first.toString(), changed.path("plannedDateHistory").get(0).path("previousDate").asText());
        assertEquals("Ожидаем запчасть", changed.path("plannedDateHistory").get(0).path("reason").asText());
        assertFalse(changed.path("plannedDateHistory").get(0).path("previousDateMissed").asBoolean());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE issue_id = ?",
                Integer.class, issueId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE issue_id = ? AND cancelled_at IS NOT NULL",
                Integer.class, issueId));
        MaxApiClient max = mock(MaxApiClient.class);
        when(max.sendText(eq("test-max-user"), anyString())).thenReturn(Mono.just("{}"));
        var notifications = new VerificationJobs.Notifications(jdbc, max, "test-token");
        notifications.sendPending();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE issue_id = ? AND sent_at IS NOT NULL",
                Integer.class, issueId));
        verify(max, times(1)).sendText(eq("test-max-user"), eq(context + "Плановый срок изменён с " + first.format(displayDate)
                + " на " + next.format(displayDate) + ". Причина: Ожидаем запчасть"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND action = 'ISSUE_PLANNED_DATE_CHANGED'",
                Integer.class, issueId));
        JsonNode resident = mapper.readTree(mvc.perform(get("/v1/issues/" + issueId)
                .header("X-Demo-Session", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(next.toString(), resident.path("plannedDate").asText());
        assertEquals(2, resident.path("plannedDateHistory").size());

        jdbc.update("UPDATE issues SET planned_date = ? WHERE id = ?", today.minusDays(1).toString(), issueId);
        JsonNode lateChange = change(issueId, today.plusDays(4), "Работы задержались");
        assertTrue(lateChange.path("plannedDateHistory").get(0).path("previousDateMissed").asBoolean());

        for (String status : new String[] {"OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED"}) {
            mvc.perform(patch("/v1/issues/" + issueId + "/status")
                    .header("X-Demo-Session", "dispatcher").contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("status", status, "reason", "Тест"))))
                    .andExpect(status().isOk());
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE issue_id = ? AND kind GLOB 'PLANNED_DATE_*' AND cancelled_at IS NOT NULL",
                Integer.class, issueId));
        notifications.sendPending();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE issue_id = ? AND kind = 'VERIFY_RESULT' AND sent_at IS NOT NULL",
                Integer.class, issueId));
        verify(max, times(1)).sendText(eq("test-max-user"), contains("подтвердите результат"));
    }

    private String createIssue() throws Exception {
        String reportId = mapper.readTree(mvc.perform(post("/v1/reports")
                .header("X-Demo-Session", "true").contentType("application/json")
                .content("{\"houseId\":\"demo-house-1\",\"text\":\"Сломан светильник у лифта\",\"category\":\"LIGHTING\",\"location\":\"Первый подъезд\",\"occurredAt\":\"2026-09-20T10:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("reportId").asText();
        return mapper.readTree(mvc.perform(post("/v1/issues").header("X-Demo-Session", "true")
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("reportId", reportId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asText();
    }

    private JsonNode change(String issueId, LocalDate date, String reason) throws Exception {
        return mapper.readTree(mvc.perform(patch("/v1/issues/" + issueId + "/planned-date")
                .header("X-Demo-Session", "dispatcher").contentType("application/json")
                .content(body(date, reason))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private String body(LocalDate date, String reason) throws Exception {
        return mapper.writeValueAsString(Map.of("date", date.toString(), "reason", reason));
    }
}
