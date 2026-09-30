package ru.pulsedoma.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import ru.pulsedoma.common.WebhookQueue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ControllerContractTest {
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        WebhookQueue queue = payload -> {};
        mvc = standaloneSetup(new MaxWebhookController(new ObjectMapper(), queue, "test-secret"),
                new IdentityController(), new ReportsController(), new IssuesController(),
                new IncidentsController(), new HousesController(), new ConnectorsController())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void everyPendingOpenApiRouteIsMapped() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                json(post("/v1/invitations/token/accept"), "{\"maxUserId\":\"user\"}"),
                get("/v1/me/houses"),
                json(put("/v1/me/active-house"), "{\"houseId\":\"house\"}"),
                json(post("/v1/reports"), "{\"houseId\":\"house\",\"text\":\"test\"}"),
                json(post("/v1/reports/report/withdraw"), "{\"reason\":\"duplicate\"}"),
                get("/v1/issues/candidates").param("houseId", "house").param("query", "light"),
                json(post("/v1/issues"), "{\"houseId\":\"house\",\"category\":\"LIGHTING\",\"description\":\"test\",\"priority\":\"NORMAL\"}"),
                json(post("/v1/issues/issue/join"), "{\"reportId\":\"report\"}"),
                json(post("/v1/issues/issue/merge"), "{\"targetIssueId\":\"target\"}"),
                json(post("/v1/issues/issue/split"), "{\"reportId\":\"report\",\"reason\":\"test\"}"),
                json(patch("/v1/issues/issue/status"), "{\"status\":\"OPEN\",\"reason\":\"test\"}"),
                json(post("/v1/issues/issue/verify"), "{\"confirmed\":true}"),
                json(post("/v1/issues/issue/comments"), "{\"text\":\"test\"}"),
                json(post("/v1/incidents"), "{\"houseId\":\"house\",\"type\":\"FIRE\",\"description\":\"test\"}"),
                get("/v1/houses/house"),
                get("/v1/houses/house/events"),
                json(post("/v1/houses/house/polls"), "{\"question\":\"Test?\",\"options\":[\"Yes\",\"No\"]}"),
                json(post("/v1/connectors/FIAS/sync"), "{\"houseId\":\"house\"}")
        );
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isNotImplemented());
        }
    }

    @Test
    void invalidInputIsRejectedBeforePendingWorkflow() throws Exception {
        mvc.perform(json(post("/v1/reports"), "{\"houseId\":\"\",\"text\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(patch("/v1/issues/issue/status"), "{\"status\":\"UNKNOWN\",\"reason\":\"test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhookChecksSecretAndPayload() throws Exception {
        mvc.perform(json(post("/webhooks/max"), "{\"update_type\":\"bot_started\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/webhooks/max").header("X-Max-Bot-Api-Secret", "test-secret"), "{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/webhooks/max").header("X-Max-Bot-Api-Secret", "test-secret"),
                "{\"update_type\":\"bot_started\"}"))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
