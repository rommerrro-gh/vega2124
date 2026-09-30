package ru.pulsedoma;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.house.AccessActivationService;
import ru.pulsedoma.identity.AccessRole;
import ru.pulsedoma.issues.MiniAppService;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class AccessActivationFlowTest {
    private static final Path DB = database();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }

    private static Path database() {
        try {
            return Files.createTempFile("pulse-access-test-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired AccessActivationService access;
    @Autowired MiniAppService miniApp;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void invitationChainRespectsScopeAndRevocation() throws Exception {
        assertNull(miniApp.activeHouse("demo-resident-1"));
        mvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/miniapp/index.html"));
        assertThrows(BusinessException.class, () -> access.revokeAccess(AccessRole.RESIDENT,
                "demo-admin-1", null, "demo-house-1", "demo-admin-1"));
        assertEquals("ACTIVE", jdbc.queryForObject("""
                SELECT access_status FROM house_memberships
                WHERE house_id = 'demo-house-1' AND user_id = 'demo-admin-1' AND role = 'RESIDENT'
                """, String.class));
        mvc.perform(get("/v1/access/organizations"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/v1/access/config").header("X-Demo-Session", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(true));
        mvc.perform(get("/v1/access/organizations").header("X-Demo-Session", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/access/invitations").header("X-Demo-Session", "true")
                .contentType("application/json")
                .content("{\"role\":\"UK_ADMIN\",\"organizationId\":\"demo-uk-1\",\"houseIds\":[\"demo-house-1\"],\"days\":3,\"activationLimit\":1}"))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO users(id, display_name, status) VALUES ('new-uk-admin', 'Новый администратор УК', 'ACTIVE')");
        jdbc.update("INSERT INTO users(id, display_name, status) VALUES ('new-dispatcher', 'Новый диспетчер', 'ACTIVE')");
        jdbc.update("INSERT INTO users(id, display_name, status) VALUES ('new-house-admin', 'Новый администратор дома', 'ACTIVE')");
        jdbc.update("INSERT INTO users(id, display_name, status) VALUES ('new-resident', 'Новый житель', 'ACTIVE')");

        var ukInvite = access.createInvitation(AccessRole.UK_ADMIN, "demo-uk-1", List.of("demo-house-1"),
                3, 1, "demo-admin-1");
        assertEquals(AccessRole.UK_ADMIN, access.preview(ukInvite.token(), "new-uk-admin").role());
        assertFalse(access.preview(ukInvite.token(), "new-uk-admin").acceptedByMe());
        access.accept(ukInvite.token(), "new-uk-admin");
        assertTrue(access.accept(ukInvite.token(), "new-uk-admin").acceptedByMe());
        assertEquals(1, jdbc.queryForObject("SELECT activation_count FROM access_invitations WHERE id = ?",
                Integer.class, ukInvite.invitation().id()));

        assertThrows(BusinessException.class, () -> access.createInvitation(AccessRole.DISPATCHER,
                "demo-uk-1", List.of("demo-house-2"), 3, 1, "new-uk-admin"));
        var dispatcherInvite = access.createInvitation(AccessRole.DISPATCHER, "demo-uk-1",
                List.of("demo-house-1"), 3, 1, "new-uk-admin");
        access.accept(dispatcherInvite.token(), "new-dispatcher");
        assertEquals(1, miniApp.dispatcherHouses("new-dispatcher").size());

        var adminInvite = access.createInvitation(AccessRole.HOUSE_ADMIN, "demo-uk-1",
                List.of("demo-house-1"), 3, 1, "new-uk-admin");
        access.accept(adminInvite.token(), "new-house-admin");
        var residentInvite = access.createInvitation(AccessRole.RESIDENT, null,
                List.of("demo-house-1"), 7, 10, "new-house-admin");
        access.accept(residentInvite.token(), "new-resident");
        assertEquals(1, miniApp.houses("new-resident").size());

        access.revokeAccess(AccessRole.DISPATCHER, "new-dispatcher", "demo-uk-1", "demo-house-1", "new-uk-admin");
        assertTrue(miniApp.dispatcherHouses("new-dispatcher").isEmpty());
        access.revokeInvitation(residentInvite.invitation().id(), "new-house-admin");
        assertTrue(access.preview(residentInvite.token(), "new-resident").acceptedByMe());
        assertThrows(BusinessException.class, () -> access.preview(residentInvite.token(), "demo-dispatcher-1"));

        access.setOrganizationHouse("demo-uk-1", "demo-house-1", false, "demo-admin-1");
        assertThrows(BusinessException.class, () -> access.createInvitation(AccessRole.HOUSE_ADMIN,
                "demo-uk-1", List.of("demo-house-1"), 3, 1, "new-uk-admin"));
        assertTrue(miniApp.houses("new-house-admin").isEmpty());

        access.setOrganizationHouse("demo-uk-1", "demo-house-1", true, "demo-admin-1");
        var pending = access.createInvitation(AccessRole.DISPATCHER, "demo-uk-1",
                List.of("demo-house-1"), 3, 1, "new-uk-admin");
        access.revokeAccess(AccessRole.UK_ADMIN, "new-uk-admin", "demo-uk-1", null, "demo-admin-1");
        assertTrue(access.myOrganizations("new-uk-admin").isEmpty());
        assertThrows(BusinessException.class, () -> access.preview(pending.token(), "new-dispatcher"));
    }

    @Test
    void demoUkAdminCanCreateAndRevokeStaffInvitationOverHttp() throws Exception {
        String created = mvc.perform(post("/v1/access/invitations").header("X-Demo-Session", "admin")
                        .contentType("application/json")
                        .content("{\"role\":\"DISPATCHER\",\"organizationId\":\"demo-uk-1\",\"houseIds\":[\"demo-house-1\"],\"days\":3,\"activationLimit\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("invitation").path("id").asText();
        mvc.perform(post("/v1/access/invitations/" + id + "/revoke").header("X-Demo-Session", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());
    }
}
