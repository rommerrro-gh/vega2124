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
import org.springframework.mock.web.MockMultipartFile;
import ru.pulsedoma.issues.MiniAppService;
import ru.pulsedoma.issues.IssueStatus;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class MiniAppFlowTest {
    private static final Path DB = database();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }

    private static Path database() {
        try {
            return Files.createTempFile("pulse-miniapp-test-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MiniAppService issues;

    @Test
    void houseAdminIsScopedToOneHouseAndAuditsContactsAndInvitations() throws Exception {
        mvc.perform(get("/admin/index.html")).andExpect(status().isOk());
        JsonNode houses = json(mvc.perform(get("/v1/me/houses").header("X-Demo-Session", "admin"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(2, houses.size());
        assertTrue(houses.get(0).path("memberships").toString().contains("HOUSE_ADMIN"));
        assertFalse(houses.get(1).path("memberships").toString().contains("HOUSE_ADMIN"));

        String contactBody = "{\"type\":\"EMERGENCY\",\"title\":\"Аварийная служба\",\"phone\":\"112\",\"details\":\"Круглосуточно\"}";
        mvc.perform(post("/v1/houses/demo-house-1/contacts").header("X-Demo-Session", "true")
                        .contentType("application/json").content(contactBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/houses/demo-house-2/contacts").header("X-Demo-Session", "admin")
                        .contentType("application/json").content(contactBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/houses/demo-house-1/contacts").header("X-Demo-Session", "dispatcher")
                        .contentType("application/json").content(contactBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/houses/demo-house-1/contacts").header("X-Demo-Session", "admin")
                        .contentType("application/json")
                        .content("{\"type\":\"EMERGENCY\",\"title\":\"Неверный\",\"phone\":\"abc\"}"))
                .andExpect(status().isBadRequest());
        JsonNode created = json(mvc.perform(post("/v1/houses/demo-house-1/contacts")
                .header("X-Demo-Session", "admin").contentType("application/json").content(contactBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        String contactId = created.path("id").asText();
        assertEquals("112", created.path("phone").asText());
        JsonNode passport = json(mvc.perform(get("/v1/houses/demo-house-1")
                .header("X-Demo-Session", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(contactId, passport.path("contacts").get(0).path("id").asText());
        mvc.perform(put("/v1/houses/demo-house-2/contacts/" + contactId).header("X-Demo-Session", "admin")
                        .contentType("application/json").content(contactBody))
                .andExpect(status().isForbidden());
        JsonNode updated = json(mvc.perform(put("/v1/houses/demo-house-1/contacts/" + contactId)
                .header("X-Demo-Session", "admin").contentType("application/json")
                .content("{\"type\":\"LOCAL\",\"title\":\"Консьерж\",\"phone\":\"+7 999 000 00 00\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals("LOCAL", updated.path("type").asText());
        mvc.perform(delete("/v1/houses/demo-house-1/contacts/" + contactId)
                .header("X-Demo-Session", "admin")).andExpect(status().isNoContent());
        assertEquals(0, json(mvc.perform(get("/v1/houses/demo-house-1/contacts")
                .header("X-Demo-Session", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).size());

        String invitationBody = "{\"days\":7,\"activationLimit\":2}";
        mvc.perform(post("/v1/houses/demo-house-1/invitations").header("X-Demo-Session", "true")
                        .contentType("application/json").content(invitationBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/houses/demo-house-2/invitations").header("X-Demo-Session", "admin")
                        .contentType("application/json").content(invitationBody))
                .andExpect(status().isForbidden());
        JsonNode invite = json(mvc.perform(post("/v1/houses/demo-house-1/invitations")
                .header("X-Demo-Session", "admin").contentType("application/json").content(invitationBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        String token = invite.path("token").asText();
        String invitationId = invite.path("invitation").path("id").asText();
        assertFalse(token.isBlank());
        JsonNode invitationList = json(mvc.perform(get("/v1/houses/demo-house-1/invitations")
                .header("X-Demo-Session", "admin")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertFalse(invitationList.toString().contains(token));
        assertFalse(jdbc.queryForObject("SELECT after_json FROM audit_events WHERE entity_id = ?",
                String.class, invitationId).contains(token));
        mvc.perform(post("/v1/houses/demo-house-1/invitations/" + invitationId + "/revoke")
                .header("X-Demo-Session", "admin")).andExpect(status().isOk());
        mvc.perform(post("/v1/invitations/" + token + "/accept").header("X-Demo-Session", "true"))
                .andExpect(status().isBadRequest());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor_id = 'demo-admin-1'",
                Integer.class));

        JsonNode freshInvite = json(mvc.perform(post("/v1/houses/demo-house-1/invitations")
                .header("X-Demo-Session", "admin").contentType("application/json").content(invitationBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        jdbc.update("INSERT INTO users(id, display_name, status) VALUES ('new-invited-resident', 'Новый житель', 'ACTIVE')");
        issues.acceptInvitation(freshInvite.path("token").asText(), "new-invited-resident");
        assertEquals("RESIDENT", jdbc.queryForObject("""
                SELECT role FROM house_memberships WHERE house_id = 'demo-house-1' AND user_id = 'new-invited-resident'
                """, String.class));
    }

    @Test
    void passportShowsProvenanceOnlyToVerifiedHouseMembers() throws Exception {
        mvc.perform(get("/v1/houses/demo-house-1")).andExpect(status().isUnauthorized());

        JsonNode passport = json(mvc.perform(get("/v1/houses/demo-house-1")
                .header("X-Demo-Session", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals("Казань, ул. Баумана, 12", passport.path("address").asText());
        assertEquals(2, passport.path("fields").size());
        assertEquals("building_year", passport.path("fields").get(0).path("key").asText());
        assertEquals(2005, passport.path("fields").get(0).path("value").asInt());
        assertEquals("Демо-данные", passport.path("fields").get(0).path("source").asText());
        assertEquals("2026-09-28T00:00:00Z", passport.path("fields").get(0).path("fetchedAt").asText());

        JsonNode empty = json(mvc.perform(get("/v1/houses/demo-house-2")
                .header("X-Demo-Session", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(0, empty.path("fields").size());
        mvc.perform(get("/v1/houses/demo-house-2").header("X-Demo-Session", "dispatcher"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/houses/missing-house").header("X-Demo-Session", "true"))
                .andExpect(status().isNotFound());
    }

    @Test
    void residentExplicitlyCreatesOrJoinsIssueInOwnHouse() throws Exception {
        mvc.perform(get("/miniapp/index.html")).andExpect(status().isOk());
        mvc.perform(get("/dispatcher/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v1/me/houses")).andExpect(status().isUnauthorized());
        JsonNode houses = mapper.readTree(mvc.perform(get("/v1/me/houses").header("X-Demo-Session", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(2, houses.size());
        assertEquals(0, json(mvc.perform(get("/v1/me/dispatcher-houses")
                .header("X-Demo-Session", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).size());
        assertEquals(1, json(mvc.perform(get("/v1/me/dispatcher-houses")
                .header("X-Demo-Session", "dispatcher")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).size());

        int existingIssues = jdbc.queryForObject("SELECT COUNT(*) FROM issues", Integer.class);
        JsonNode first = reportAt("Свет не работает в подъезде на втором этаже", "Подъезд 9");
        String firstId = first.path("reportId").asText();
        assertEquals(0, first.path("candidates").size());
        assertEquals(existingIssues, jdbc.queryForObject("SELECT COUNT(*) FROM issues", Integer.class));

        mvc.perform(multipart("/v1/reports/" + firstId + "/attachments")
                        .file(new MockMultipartFile("file", "bad.txt", "text/plain", "bad".getBytes()))
                        .header("X-Demo-Session", "true"))
                .andExpect(status().isBadRequest());

        MockMultipartFile file = new MockMultipartFile("file", "evidence.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        JsonNode uploaded = json(mvc.perform(multipart("/v1/reports/" + firstId + "/attachments")
                .file(file).header("X-Demo-Session", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String attachmentId = uploaded.path("id").asText();
        mvc.perform(get("/v1/attachments/" + attachmentId).header("X-Demo-Session", "true"))
                .andExpect(status().isOk());

        JsonNode created = json(mvc.perform(post("/v1/issues").header("X-Demo-Session", "true")
                .contentType("application/json").content("{\"reportId\":\"" + firstId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String issueId = created.path("id").asText();
        assertEquals("DRAFT", created.path("status").asText());
        assertEquals(1, created.path("participants").asInt());
        assertEquals("Подъезд 9", created.path("location").asText());
        assertEquals(1, created.path("attachments").size());

        mvc.perform(get("/v1/dispatcher/issues").param("houseId", "demo-house-1")
                        .header("X-Demo-Session", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/dispatcher/issues").param("houseId", "demo-house-2")
                        .header("X-Demo-Session", "dispatcher"))
                .andExpect(status().isForbidden());
        JsonNode queue = json(mvc.perform(get("/v1/dispatcher/issues").param("houseId", "demo-house-1")
                .header("X-Demo-Session", "dispatcher"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(issueId, queue.get(0).path("id").asText());
        mvc.perform(get("/v1/attachments/" + attachmentId).header("X-Demo-Session", "dispatcher"))
                .andExpect(status().isOk());
        mvc.perform(patch("/v1/issues/" + issueId + "/status").header("X-Demo-Session", "true")
                        .contentType("application/json").content("{\"status\":\"OPEN\",\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/v1/issues/" + issueId + "/status").header("X-Demo-Session", "dispatcher")
                        .contentType("application/json").content("{\"status\":\"IN_PROGRESS\",\"reason\":\"Пропуск\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/v1/issues/" + issueId + "/status").header("X-Demo-Session", "dispatcher")
                        .contentType("application/json").content("{\"status\":\"OPEN\",\"reason\":\"Принято\"}"))
                .andExpect(status().isOk());
        JsonNode differentLocation = reportAt("Свет не работает в подъезде на втором этаже", "Подъезд 2");
        assertEquals(0, differentLocation.path("candidates").size());
        JsonNode second = reportAt("Свет не работает в подъезде на втором этаже", "Подъезд 9");
        assertFalse(second.path("candidates").isEmpty());
        assertEquals(issueId, second.path("candidates").get(0).path("issueId").asText());
        assertEquals(existingIssues + 1, jdbc.queryForObject("SELECT COUNT(*) FROM issues", Integer.class));

        JsonNode joined = json(mvc.perform(post("/v1/issues/" + issueId + "/join")
                .header("X-Demo-Session", "true").contentType("application/json")
                .content("{\"reportId\":\"" + second.path("reportId").asText() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(issueId, joined.path("id").asText());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM issue_reports WHERE issue_id = ?", Integer.class, issueId));
        assertTrue(joined.path("address").asText().contains("Баумана"));
        jdbc.update("UPDATE users SET max_user_id = '123456' WHERE id = 'demo-resident-1'");
        jdbc.update("INSERT INTO users(id, display_name, status) VALUES ('second-resident', 'Второй житель', 'ACTIVE')");
        jdbc.update("""
                INSERT INTO house_memberships(house_id, user_id, role, verification_status)
                VALUES ('demo-house-1', 'second-resident', 'RESIDENT', 'VERIFIED')
                """);
        jdbc.update("""
                INSERT INTO issue_participants(issue_id, user_id, confirmation_type, confirmed_at)
                VALUES (?, 'second-resident', 'resident_join', CURRENT_TIMESTAMP)
                """, issueId);
        for (String next : java.util.List.of("ASSIGNED", "IN_PROGRESS", "RESOLVED")) {
            mvc.perform(patch("/v1/issues/" + issueId + "/status").header("X-Demo-Session", "dispatcher")
                            .contentType("application/json")
                            .content("{\"status\":\"" + next + "\",\"reason\":\"Этап работы\"}"))
                    .andExpect(status().isOk());
        }
        assertEquals("VERIFICATION_72H", issues.issue(issueId, "demo-resident-1").status().name());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM status_events WHERE issue_id = ?", Integer.class, issueId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE issue_id = ?", Integer.class, issueId));
        mvc.perform(post("/v1/issues/" + issueId + "/verify").header("X-Demo-Session", "dispatcher")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isNotFound());
        JsonNode firstConfirmation = json(mvc.perform(post("/v1/issues/" + issueId + "/verify")
                .header("X-Demo-Session", "true").contentType("application/json")
                .content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals("VERIFICATION_72H", firstConfirmation.path("status").asText());
        mvc.perform(post("/v1/issues/" + issueId + "/verify").header("X-Demo-Session", "true")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isBadRequest());
        assertEquals(IssueStatus.REOPENED,
                issues.verify(issueId, "second-resident", false, "Свет снова погас").status());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox
                WHERE issue_id = ? AND cancelled_at IS NOT NULL
                """, Integer.class, issueId));
        for (String next : java.util.List.of("ASSIGNED", "IN_PROGRESS", "RESOLVED")) {
            mvc.perform(patch("/v1/issues/" + issueId + "/status").header("X-Demo-Session", "dispatcher")
                            .contentType("application/json")
                            .content("{\"status\":\"" + next + "\",\"reason\":\"Повторная работа\"}"))
                    .andExpect(status().isOk());
        }
        assertEquals(2, jdbc.queryForObject("SELECT verification_round FROM issues WHERE id = ?", Integer.class, issueId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE issue_id = ?", Integer.class, issueId));
        mvc.perform(post("/v1/issues/" + issueId + "/verify").header("X-Demo-Session", "true")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        assertEquals(IssueStatus.CLOSED_CONFIRMED,
                issues.verify(issueId, "second-resident", true, null).status());
        JsonNode mine = json(mvc.perform(get("/v1/me/issues").header("X-Demo-Session", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals("CLOSED_CONFIRMED", mine.get(0).path("status").asText());

        JsonNode expiringReport = reportAt("Свет моргает на лестнице", "Подъезд 3");
        JsonNode expiringIssue = json(mvc.perform(post("/v1/issues").header("X-Demo-Session", "true")
                .contentType("application/json")
                .content("{\"reportId\":\"" + expiringReport.path("reportId").asText() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String expiringId = expiringIssue.path("id").asText();
        jdbc.update("""
                UPDATE issues SET status = 'VERIFICATION_72H', verification_round = 1,
                                  verification_due_at = '2020-01-01T00:00:00Z' WHERE id = ?
                """, expiringId);
        assertEquals(1, issues.closeExpiredVerifications());
        assertEquals(IssueStatus.CLOSED_UNCONFIRMED,
                issues.issue(expiringId, "demo-resident-1").status());

        mvc.perform(post("/v1/issues").header("X-Demo-Session", "true")
                        .contentType("application/json").content("{\"reportId\":\"" + firstId + "\"}"))
                .andExpect(status().isBadRequest());
        JsonNode otherHouse = json(mvc.perform(post("/v1/reports").header("X-Demo-Session", "true")
                .contentType("application/json")
                .content(mapper.writeValueAsString(java.util.Map.of("houseId", "demo-house-2",
                        "category", "LIGHTING", "text", "Не работает свет в подъезде",
                        "location", "Подъезд 1", "occurredAt", "2026-09-20T10:00:00Z"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(post("/v1/issues/" + issueId + "/join")
                        .header("X-Demo-Session", "true").contentType("application/json")
                        .content("{\"reportId\":\"" + otherHouse.path("reportId").asText() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void residentCanFindAndJoinIdenticalDraftIssue() throws Exception {
        String description = "Свет мигает у лестницы на пятом этаже";
        String location = "Подъезд 5, этаж 5";
        JsonNode first = reportAt(description, location);
        JsonNode created = json(mvc.perform(post("/v1/issues").header("X-Demo-Session", "true")
                .contentType("application/json")
                .content("{\"reportId\":\"" + first.path("reportId").asText() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals("DRAFT", created.path("status").asText());

        JsonNode second = reportAt(description, location);
        assertEquals(created.path("id").asText(), second.path("candidates").get(0).path("issueId").asText());
        JsonNode joined = json(mvc.perform(post("/v1/issues/" + created.path("id").asText() + "/join")
                .header("X-Demo-Session", "true").contentType("application/json")
                .content("{\"reportId\":\"" + second.path("reportId").asText() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals("DRAFT", joined.path("status").asText());
        assertEquals(2, joined.path("reports").size());
    }

    private JsonNode report(String text) throws Exception {
        return reportAt(text, "Подъезд 1");
    }

    private JsonNode reportAt(String text, String location) throws Exception {
        return json(mvc.perform(post("/v1/reports").header("X-Demo-Session", "true")
                .contentType("application/json")
                .content(mapper.writeValueAsString(java.util.Map.of("houseId", "demo-house-1",
                        "category", "LIGHTING", "text", text,
                        "location", location, "occurredAt", "2026-09-20T10:00:00Z"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode json(byte[] value) throws Exception {
        return mapper.readTree(value);
    }
}
