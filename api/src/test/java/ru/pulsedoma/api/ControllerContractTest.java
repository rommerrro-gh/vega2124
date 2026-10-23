package ru.pulsedoma.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import ru.pulsedoma.issues.*;

class ControllerContractTest {
    MockMvc mvc;
    LocalValidatorFactoryBean validator;

    @BeforeEach
    void setup() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc =
                standaloneSetup(
                                new ResidentCasesController(
                                        mock(ResidentCaseService.class),
                                        mock(ProblemIntakeService.class)),
                                new SharedRequestsController(
                                        mock(SharedRequestService.class),
                                        mock(ResidentCaseService.class),
                                        mock(ProblemIntakeService.class)),
                                new IdentityController(mock(ResidentIdentityService.class)))
                        .setValidator(validator)
                        .build();
    }

    @AfterEach
    void close() {
        validator.close();
    }

    @Test
    void retiredRoutesAreAbsent() throws Exception {
        for (String p : new String[] {"/v1/reports", "/v1/issues", "/v1/incidents"})
            mvc.perform(post(p).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
    }

    @Test
    void categoryAndKindAreRequired() throws Exception {
        mvc.perform(post("/v1/cases").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/v1/cases/similar-problems")
                                .contentType("application/json")
                                .content("{\"houseId\":\"h\",\"description\":\"Проблема\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void splitRequiresExplicitWorkAllocation() throws Exception {
        mvc.perform(
                        post("/v1/shared-requests/g/split")
                                .contentType("application/json")
                                .content(
                                        "{\"caseId\":\"c\",\"revision\":0,\"reason\":\"Другая проблема\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void identityRouteRemains() throws Exception {
        mvc.perform(get("/v1/me/houses").principal(() -> "u")).andExpect(status().isOk());
    }
}
