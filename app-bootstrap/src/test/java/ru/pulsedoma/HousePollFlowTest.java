package ru.pulsedoma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class HousePollFlowTest {
    private static final Path DB = database();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }

    private static Path database() {
        try {
            return Files.createTempFile("pulse-poll-test-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void preliminaryPollIsScopedAndAcceptsOneVotePerResident() throws Exception {
        String body = """
                {"question":"Нужна ли велопарковка?","options":["Да","Нет"],
                 "closesAt":"2099-01-01T00:00:00Z","resultsHiddenUntilClose":true}
                """;
        mvc.perform(post("/v1/houses/demo-house-1/polls").contentType("application/json")
                .content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/houses/demo-house-1/polls").header("X-Demo-Session", "true")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/houses/demo-house-2/polls").header("X-Demo-Session", "admin")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/houses/demo-house-1/polls").header("X-Demo-Session", "admin")
                .contentType("application/json").content(body.replace("\"Нет\"", "\"Да\"")))
                .andExpect(status().isBadRequest());

        JsonNode created = json(mvc.perform(post("/v1/houses/demo-house-1/polls")
                .header("X-Demo-Session", "admin").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        String pollId = created.path("id").asText();
        String optionId = created.path("options").get(0).path("id").asText();
        assertFalse(created.path("official").asBoolean(true));
        assertFalse(created.path("resultsVisible").asBoolean(true));
        assertEquals(0, created.path("options").get(0).path("votes").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND action = 'HOUSE_POLL_CREATED'",
                Integer.class, pollId));

        mvc.perform(get("/v1/houses/demo-house-1/polls").header("X-Demo-Session", "dispatcher"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/houses/demo-house-2/polls").header("X-Demo-Session", "true"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/houses/demo-house-2/polls/" + pollId + "/votes")
                .header("X-Demo-Session", "true").contentType("application/json")
                .content("{\"optionId\":\"" + optionId + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/houses/demo-house-1/polls/" + pollId + "/votes")
                .header("X-Demo-Session", "true").contentType("application/json")
                .content("{\"optionId\":\"missing\"}"))
                .andExpect(status().isNotFound());

        String vote = "{\"optionId\":\"" + optionId + "\"}";
        JsonNode voted = json(mvc.perform(post("/v1/houses/demo-house-1/polls/" + pollId + "/votes")
                .header("X-Demo-Session", "true").contentType("application/json").content(vote))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(optionId, voted.path("myOptionId").asText());
        assertEquals(0, voted.path("options").get(0).path("votes").asInt());
        mvc.perform(post("/v1/houses/demo-house-1/polls/" + pollId + "/votes")
                .header("X-Demo-Session", "true").contentType("application/json").content(vote))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM poll_votes WHERE poll_id = ?",
                Integer.class, pollId));

        jdbc.update("UPDATE polls SET closes_at = '2000-01-01T00:00:00Z' WHERE id = ?", pollId);
        JsonNode closed = json(mvc.perform(get("/v1/houses/demo-house-1/polls")
                .header("X-Demo-Session", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).get(0);
        assertTrue(closed.path("closed").asBoolean());
        assertTrue(closed.path("resultsVisible").asBoolean());
        assertEquals(1, closed.path("options").get(0).path("votes").asInt());
        mvc.perform(post("/v1/houses/demo-house-1/polls/" + pollId + "/votes")
                .header("X-Demo-Session", "admin").contentType("application/json").content(vote))
                .andExpect(status().isConflict());
    }

    private JsonNode json(byte[] bytes) throws Exception {
        return mapper.readTree(bytes);
    }
}
