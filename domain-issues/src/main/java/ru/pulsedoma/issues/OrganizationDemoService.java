package ru.pulsedoma.issues;

import static ru.pulsedoma.issues.OrganizationTypes.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationDemoService {
    private final JdbcTemplate jdbc;
    private final ResidentCaseService cases;
    private final boolean enabled;
    private final SharedRequestRegistry shared;

    public OrganizationDemoService(
            JdbcTemplate jdbc,
            ResidentCaseService cases,
            @Value("${organization-demo.enabled:true}") boolean enabled,
            SharedRequestRegistry shared) {
        this.shared = shared;
        this.enabled = enabled;
        this.jdbc = jdbc;
        this.cases = cases;
    }

    private String key(String value) {
        return "org-demo-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private void team(String org, String user, TeamRole role, String dept) {
        jdbc.update(
                "INSERT OR IGNORE INTO organization_team(organization_id,user_id,role,department) VALUES (?,?,?,?)",
                org,
                user,
                role.name(),
                dept);
    }

    private void function(String org, String user, Function function) {
        jdbc.update(
                "INSERT OR IGNORE INTO organization_team_functions VALUES (?,?,?)",
                org,
                user,
                function.name());
    }

    @Transactional
    public void seed(
            String house,
            String resident,
            String admin,
            String management,
            String worker,
            boolean guided) {
        if (!enabled) return;
        String marker = key(house + ":seed-v1");
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM organization_demo_fixtures WHERE id=?",
                        Integer.class,
                        marker)
                > 0) return;
        String developer = key(house + ":developer"),
                contractor = key(house + ":contractor"),
                coord = guided ? admin : "demo-recipient-1",
                now = Instant.now().toString();
        jdbc.update(
                "INSERT OR IGNORE INTO organizations(id,type,name) VALUES (?,'DEVELOPER','Застройщик «Городские кварталы»')",
                developer);
        jdbc.update(
                "INSERT OR IGNORE INTO organizations(id,type,name) VALUES (?,'GENERAL_CONTRACTOR','Генподрядчик «Стройсервис»')",
                contractor);
        for (String org : List.of(management, developer, contractor))
            jdbc.update(
                    "INSERT OR IGNORE INTO organization_work_houses(organization_id,house_id) VALUES (?,?)",
                    org,
                    house);
        team(management, admin, TeamRole.ADMIN, "");
        team(management, admin, TeamRole.COORDINATOR, "");
        team(developer, coord, TeamRole.ADMIN, "Гарантийная служба");
        team(developer, coord, TeamRole.COORDINATOR, "Гарантийная служба");
        team(contractor, coord, TeamRole.ADMIN, "");
        team(contractor, coord, TeamRole.COORDINATOR, "");
        team(developer, worker, TeamRole.SPECIALIST, "Инженерная служба");
        team(contractor, worker, TeamRole.SPECIALIST, "Ремонтная бригада");
        function(developer, worker, Function.ENGINEER);
        function(developer, worker, Function.WARRANTY);
        function(contractor, worker, Function.CONTRACTOR);
        if (guided) {
            for (String org : List.of(developer, contractor))
                jdbc.update(
                        "INSERT OR IGNORE INTO guided_demo_organizations(session_id,organization_id) SELECT id,? FROM guided_demo_sessions WHERE user_id=? AND active=1",
                        org,
                        admin);
            team(developer, admin, TeamRole.SPECIALIST, "Гарантийная служба");
            team(contractor, admin, TeamRole.SPECIALIST, "Ремонтная бригада");
            function(developer, admin, Function.ENGINEER);
            function(developer, admin, Function.WARRANTY);
            function(contractor, admin, Function.CONTRACTOR);
        }
        for (int n = 0; n < 2; n++) {
            var c =
                    cases.create(
                            house,
                            CaseKind.PROBLEM,
                            n == 0
                                    ? "После дождя промокает стена у окна"
                                    : "Отходит плитка в холле первого этажа",
                            "Подъезд 1",
                            key(house + ":case:" + n),
                            resident);
            jdbc.update(
                    "INSERT OR IGNORE INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'COORDINATOR',?,?,?)",
                    c.workflowCaseId(),
                    admin,
                    admin,
                    now,
                    management);
            jdbc.update(
                    "INSERT OR REPLACE INTO organization_case_owners VALUES (?,?)",
                    c.workflowCaseId(),
                    n == 0 ? developer : management);
            String transfer = key(house + ":transfer:" + n),
                    state = n == 0 ? "ACCEPTED" : "OFFERED";
            jdbc.update(
                    "INSERT OR IGNORE INTO organization_case_transfers(id,case_id,from_organization_id,to_organization_id,state,reason,created_by,accepted_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    transfer,
                    c.workflowCaseId(),
                    management,
                    developer,
                    state,
                    "Нужен осмотр строительного дефекта и проверка гарантийных обязательств",
                    admin,
                    n == 0 ? coord : null,
                    now,
                    now);
            jdbc.update(
                    "INSERT OR IGNORE INTO organization_transfer_history VALUES (?,?,? ,?,?,?)",
                    key(transfer + ":offered"),
                    transfer,
                    "OFFERED",
                    "Материалы направлены гарантийной службе",
                    admin,
                    now);
            if (n == 0) {
                jdbc.update(
                        "INSERT OR IGNORE INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'COORDINATOR',?,?,?)",
                        c.workflowCaseId(),
                        coord,
                        admin,
                        now,
                        developer);
                jdbc.update(
                        "INSERT OR IGNORE INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'ENGINEER',?,?,?)",
                        c.workflowCaseId(),
                        worker,
                        coord,
                        now,
                        developer);
                jdbc.update(
                        "INSERT OR IGNORE INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'WARRANTY',?,?,?)",
                        c.workflowCaseId(),
                        worker,
                        coord,
                        now,
                        developer);
                jdbc.update(
                        "UPDATE case_collaborators SET source_organization_id=? WHERE case_id=? AND user_id=? AND function='COORDINATOR'",
                        developer,
                        c.workflowCaseId(),
                        coord);
                jdbc.update(
                        "UPDATE resident_cases SET status='IN_REVIEW',responsible_user_id=?,next_step='Согласуем осмотр с инженером гарантийной службы' WHERE id=?",
                        coord,
                        c.workflowCaseId());
                jdbc.update(
                        "INSERT OR IGNORE INTO case_inspections(id,case_id,inspector_id,location,participants,starts_at,ends_at,state,created_by,created_at) VALUES (?,?,?,?,?,?,?,'PROPOSED',?,?)",
                        key(c.workflowCaseId() + ":inspection"),
                        c.workflowCaseId(),
                        worker,
                        "У окна в подъезде 1",
                        "Инженер, представитель УК",
                        java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Moscow"))
                                .plusDays(1)
                                .withHour(14)
                                .withMinute(0)
                                .withSecond(0)
                                .withNano(0)
                                .toInstant()
                                .toString(),
                        java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Moscow"))
                                .plusDays(1)
                                .withHour(16)
                                .withMinute(0)
                                .withSecond(0)
                                .withNano(0)
                                .toInstant()
                                .toString(),
                        coord,
                        now);
                jdbc.update(
                        "INSERT OR IGNORE INTO organization_transfer_history VALUES (?,?,?,?,?,?)",
                        key(transfer + ":accepted"),
                        transfer,
                        "ACCEPTED",
                        "Назначен инженер для осмотра",
                        coord,
                        now);
            }
        }
        var workCase =
                cases.create(
                        house,
                        CaseKind.PROBLEM,
                        "Повреждён доводчик входной двери",
                        "Подъезд 1",
                        key(house + ":contractor-work"),
                        resident);
        jdbc.update(
                "INSERT OR IGNORE INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'COORDINATOR',?,?,?)",
                workCase.workflowCaseId(),
                coord,
                admin,
                now,
                contractor);
        jdbc.update(
                "INSERT OR IGNORE INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'CONTRACTOR',?,?,?)",
                workCase.workflowCaseId(),
                worker,
                coord,
                now,
                contractor);
        jdbc.update(
                "UPDATE resident_cases SET status='IN_PROGRESS',responsible_user_id=?,next_step='Подрядчик заменяет доводчик двери' WHERE id=?",
                coord,
                workCase.workflowCaseId());
        jdbc.update(
                "INSERT OR IGNORE INTO case_work_orders(id,case_id,title,scope,assignee_id,basis_type,basis,due_at,due_basis,state,created_by,created_at,updated_at) VALUES (?,?,?,?,?,'MAINTENANCE',?,?,?,'ASSIGNED',?,?,?)",
                key(workCase.workflowCaseId() + ":order"),
                workCase.workflowCaseId(),
                "Заменить доводчик входной двери",
                "Установить доводчик и проверить плавность закрытия",
                worker,
                "Задание управляющей организации",
                java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Moscow"))
                        .plusDays(2)
                        .withHour(18)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0)
                        .toInstant()
                        .toString(),
                "Согласованный план работ",
                coord,
                now,
                now);
        jdbc.update(
                "INSERT OR REPLACE INTO organization_case_owners VALUES (?,?)",
                workCase.workflowCaseId(),
                contractor);
        for (String id :
                jdbc.query(
                        "SELECT id FROM resident_cases WHERE house_id=? AND client_request_id IN (?,?,?)",
                        (r, n) -> r.getString(1),
                        house,
                        key(house + ":case:0"),
                        key(house + ":case:1"),
                        key(house + ":contractor-work"))) shared.sync(shared.root(id), admin);
        jdbc.update("INSERT INTO organization_demo_fixtures VALUES (?)", marker);
    }
}
