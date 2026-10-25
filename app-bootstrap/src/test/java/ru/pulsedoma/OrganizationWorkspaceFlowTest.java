package ru.pulsedoma;

import static org.junit.jupiter.api.Assertions.*;
import static ru.pulsedoma.issues.OrganizationTypes.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import ru.pulsedoma.issues.*;

@SpringBootTest
@ActiveProfiles("demo")
class OrganizationWorkspaceFlowTest {
    static final Path DB = temp();

    static Path temp() {
        try {
            return Files.createTempFile("organization-flow-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }

    @Autowired OrganizationWorkspaceService teams;
    @Autowired ResidentCaseService cases;
    @Autowired CaseWorkAccess access;
    @Autowired JdbcTemplate jdbc;
    String org, target, coordinator, engineer, caseId, privateCaseId;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString();
        coordinator = "coord-" + suffix;
        engineer = "engineer-" + suffix;
        for (String u : List.of(coordinator, engineer))
            jdbc.update("INSERT INTO users(id,display_name) VALUES (?,?)", u, u);
        org = teams.create("УК " + suffix, Kind.CONTRACTOR, "demo-admin-1").id();
        target = teams.create("Застройщик " + suffix, Kind.DEVELOPER, "demo-admin-1").id();
        jdbc.update(
                "INSERT INTO organization_team(organization_id,user_id,role) VALUES (?,'demo-admin-1','ADMIN')",
                org);
        teams.link(org, "demo-house-1", true, "demo-admin-1");
        teams.link(target, "demo-house-1", true, "demo-admin-1");
        teams.selfCoordinator(org, true, "demo-admin-1");
        var invitation =
                teams.invite(
                        target,
                        TeamRole.COORDINATOR,
                        "Гарантийная служба",
                        List.of(),
                        "demo-admin-1");
        teams.accept(invitation.token(), coordinator);
        var inv =
                teams.invite(
                        target,
                        TeamRole.SPECIALIST,
                        "Осмотры",
                        List.of(Function.ENGINEER),
                        "demo-admin-1");
        teams.accept(inv.token(), engineer);
        var createdCase =
                cases.create(
                        "demo-house-1",
                        CaseKind.PROBLEM,
                        "Вода на стене",
                        null,
                        suffix,
                        "demo-resident-1");
        privateCaseId = createdCase.id();
        caseId = createdCase.workflowCaseId();
        jdbc.update(
                "INSERT INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,'demo-admin-1','COORDINATOR','demo-admin-1','2026-10-25T00:00:00Z',?)",
                caseId,
                org);
    }

    @Test
    void acceptanceGrantsOnlyAssignedCaseAndKeepsOwnerUntilAccepted() {
        var before = cases.get(caseId, "demo-dispatcher-1");
        var t =
                teams.offer(
                        org,
                        caseId,
                        target,
                        "Проверить строительный дефект",
                        before.revision(),
                        "demo-admin-1");
        assertEquals(
                before.responsibleUserId(),
                cases.get(caseId, "demo-dispatcher-1").responsibleUserId());
        assertThrows(RuntimeException.class, () -> cases.get(caseId, coordinator));
        teams.reply(t.id(), TransferState.ACCEPTED, "Принято", t.revision(), coordinator);
        assertEquals(coordinator, cases.get(caseId, coordinator).responsibleUserId());
        assertFalse(access.coordinator(caseId, "demo-house-1", "demo-admin-1"));
        assertThrows(
                RuntimeException.class,
                () ->
                        teams.offer(
                                org,
                                caseId,
                                target,
                                "Повторно",
                                cases.get(caseId, "demo-admin-1").revision(),
                                "demo-admin-1"));
        assertThrows(RuntimeException.class, () -> cases.get(caseId, engineer));
        teams.assign(
                target,
                caseId,
                engineer,
                CaseFunction.ENGINEER,
                cases.get(caseId, coordinator).revision(),
                true,
                coordinator);
        assertTrue(cases.get(caseId, engineer).fullAccess());
        assertThrows(RuntimeException.class, () -> cases.get(privateCaseId, engineer));
        assertFalse(access.coordinator(caseId, "demo-house-1", engineer));
        assertThrows(
                RuntimeException.class,
                () ->
                        teams.assign(
                                target,
                                caseId,
                                "demo-admin-1",
                                CaseFunction.ENGINEER,
                                cases.get(caseId, coordinator).revision(),
                                true,
                                coordinator));
    }

    @Test
    void returnedTransferDoesNotGrantCaseAndCanBeOfferedAgain() {
        var t =
                teams.offer(
                        org,
                        caseId,
                        target,
                        "Нужен осмотр",
                        cases.get(caseId, "demo-admin-1").revision(),
                        "demo-admin-1");
        teams.reply(t.id(), TransferState.RETURNED, "Приложите фото", 0, coordinator);
        assertThrows(RuntimeException.class, () -> cases.get(caseId, coordinator));
        assertDoesNotThrow(
                () ->
                        teams.offer(
                                org,
                                caseId,
                                target,
                                "Добавлены сведения",
                                cases.get(caseId, "demo-admin-1").revision(),
                                "demo-admin-1"));
    }

    @Test
    void invitationIsSingleUseAndNoOrganizationCanManageAnotherTeam() {
        var i =
                teams.invite(
                        target,
                        TeamRole.SPECIALIST,
                        "Работы",
                        List.of(Function.CONTRACTOR),
                        "demo-admin-1");
        teams.accept(i.token(), engineer);
        assertThrows(RuntimeException.class, () -> teams.accept(i.token(), coordinator));
        assertThrows(
                RuntimeException.class,
                () -> teams.invite(org, TeamRole.ADMIN, "", List.of(), coordinator));
        assertThrows(
                RuntimeException.class,
                () ->
                        teams.reply(
                                teams.offer(
                                                org,
                                                caseId,
                                                target,
                                                "Осмотр",
                                                cases.get(caseId, "demo-admin-1").revision(),
                                                "demo-admin-1")
                                        .id(),
                                TransferState.ACCEPTED,
                                "Принято",
                                0,
                                engineer));
    }

    @Test
    void tasksAndWorkspaceStayScopedAndRevocationRequiresReassignment() {
        var t =
                teams.offer(
                        org,
                        caseId,
                        target,
                        "Осмотр",
                        cases.get(caseId, "demo-admin-1").revision(),
                        "demo-admin-1");
        teams.reply(t.id(), TransferState.ACCEPTED, "Принято", 0, coordinator);
        teams.assign(
                target,
                caseId,
                engineer,
                CaseFunction.ENGINEER,
                cases.get(caseId, coordinator).revision(),
                true,
                coordinator);
        assertTrue(cases.get(caseId, engineer).fullAccess());
        assertThrows(RuntimeException.class, () -> cases.get(privateCaseId, engineer));
        assertEquals(
                1,
                cases.queue("demo-house-1", engineer).size(),
                "Queue must contain assigned case");
        assertEquals(1, teams.houses(target, engineer).size(), "Organization must have a house");
        assertEquals(
                1,
                teams.assignedCases(target, TeamRole.SPECIALIST, engineer).size(),
                jdbc.queryForList("SELECT * FROM case_collaborators WHERE case_id=?", caseId)
                        .toString());
        assertTrue(teams.tasks(target, TeamRole.SPECIALIST, engineer).isEmpty());
        assertThrows(
                RuntimeException.class,
                () -> teams.assignedCases(org, TeamRole.SPECIALIST, engineer));
        assertThrows(
                RuntimeException.class,
                () ->
                        teams.memberRole(
                                target, engineer, TeamRole.SPECIALIST, false, "demo-admin-1"));
        var adminInvite = teams.invite(target, TeamRole.ADMIN, "", List.of(), "demo-admin-1");
        teams.accept(adminInvite.token(), "demo-admin-1");
        assertThrows(
                RuntimeException.class,
                () ->
                        teams.memberRole(
                                target, engineer, TeamRole.SPECIALIST, false, "demo-admin-1"));
        teams.assign(
                target,
                caseId,
                engineer,
                CaseFunction.ENGINEER,
                cases.get(caseId, coordinator).revision(),
                false,
                coordinator);
        teams.memberRole(target, engineer, TeamRole.SPECIALIST, false, "demo-admin-1");
        assertThrows(RuntimeException.class, () -> cases.get(caseId, engineer));
    }

    @Test
    void staleAndDuplicateRepliesCannotReplaceResponsibleAgain() {
        var t =
                teams.offer(
                        org,
                        caseId,
                        target,
                        "Осмотр",
                        cases.get(caseId, "demo-admin-1").revision(),
                        "demo-admin-1");
        assertThrows(
                RuntimeException.class,
                () -> teams.reply(t.id(), TransferState.ACCEPTED, "Принято", 5, coordinator));
        teams.reply(t.id(), TransferState.REFUSED, "Нет оснований", 0, coordinator);
        assertThrows(
                RuntimeException.class,
                () -> teams.reply(t.id(), TransferState.ACCEPTED, "Повторно", 1, coordinator));
        assertThrows(RuntimeException.class, () -> cases.get(caseId, coordinator));
    }
}
