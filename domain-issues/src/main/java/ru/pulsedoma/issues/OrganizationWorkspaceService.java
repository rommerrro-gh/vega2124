package ru.pulsedoma.issues;

import static ru.pulsedoma.issues.OrganizationTypes.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

@Service
public class OrganizationWorkspaceService {
    private final JdbcTemplate jdbc;
    private final CaseWorkAccess access;
    private final ResidentCaseService cases;
    private final SharedRequestRegistry shared;

    public OrganizationWorkspaceService(
            JdbcTemplate jdbc,
            CaseWorkAccess access,
            ResidentCaseService cases,
            SharedRequestRegistry shared) {
        this.shared = shared;
        this.jdbc = jdbc;
        this.access = access;
        this.cases = cases;
    }

    public record Workspace(
            String id,
            String name,
            Kind kind,
            TeamRole role,
            String department,
            List<Function> functions) {}

    public record Organization(String id, String name, Kind kind) {}

    public record Member(
            String id,
            String name,
            TeamRole role,
            String department,
            boolean active,
            List<Function> functions) {}

    public record Invitation(
            String id,
            TeamRole role,
            String department,
            String expiresAt,
            boolean accepted,
            boolean revoked) {}

    public record CreatedInvitation(String token, Invitation invitation) {}

    public record Transfer(
            String id,
            String caseId,
            String description,
            String fromId,
            String fromName,
            String toId,
            String toName,
            TransferState state,
            String reason,
            String reply,
            int revision,
            String createdAt,
            List<Map<String, Object>> history) {}

    private boolean exists(String sql, Object... args) {
        return access.exists(sql, args);
    }

    private BusinessException denied() {
        return new BusinessException(
                "ORGANIZATION_ACCESS_DENIED", "Недостаточно полномочий в организации");
    }

    private BusinessException invalid(String text) {
        return new BusinessException("ORGANIZATION_INVALID", text);
    }

    private BusinessException conflict() {
        return new BusinessException("CASE_CONFLICT", "Данные изменились. Обновите страницу");
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private String demo(String actor) {
        return jdbc
                .query(
                        "SELECT id FROM guided_demo_sessions WHERE user_id=? AND active=1",
                        (r, n) -> r.getString(1),
                        actor)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public boolean system(String actor) {
        return exists(
                        "SELECT count(*) FROM system_administrators s JOIN users u ON u.id=s.user_id WHERE s.user_id=? AND u.status='ACTIVE'",
                        actor)
                || demo(actor) != null
                        && exists(
                                "SELECT count(*) FROM users WHERE id=? AND status='ACTIVE'", actor);
    }

    private void scope(String org, String actor) {
        String session = demo(actor);
        if (session != null
                && !exists(
                        "SELECT count(*) FROM guided_demo_organizations WHERE session_id=? AND organization_id=?",
                        session,
                        org)) throw denied();
    }

    public boolean role(String org, String user, TeamRole role) {
        return exists(
                "SELECT count(*) FROM organization_team t JOIN users u ON u.id=t.user_id WHERE t.organization_id=? AND t.user_id=? AND t.role=? AND t.active=1 AND u.status='ACTIVE'",
                org,
                user,
                role.name());
    }

    private void admin(String org, String actor) {
        if (!role(org, actor, TeamRole.ADMIN)) throw denied();
    }

    private void coordinate(String org, String actor) {
        if (!role(org, actor, TeamRole.COORDINATOR)) throw denied();
    }

    private List<Function> functions(String org, String user) {
        return jdbc.query(
                "SELECT function FROM organization_team_functions WHERE organization_id=? AND user_id=? ORDER BY function",
                (r, n) -> Function.valueOf(r.getString(1)),
                org,
                user);
    }

    private void syncLegacy() {
        jdbc.update(
                "INSERT OR IGNORE INTO organization_team(organization_id,user_id,role) SELECT organization_id,user_id,'ADMIN' FROM organization_memberships WHERE role='UK_ADMIN' AND access_status='ACTIVE'");
        jdbc.update(
                "INSERT OR IGNORE INTO organization_work_houses(organization_id,house_id) SELECT organization_id,house_id FROM organization_houses WHERE status='ACTIVE'");
        jdbc.update(
                "INSERT OR IGNORE INTO organization_team(organization_id,user_id,role,department) SELECT organization_id,user_id,CASE WHEN function='COORDINATOR' THEN 'COORDINATOR' ELSE 'SPECIALIST' END,organization_label FROM workflow_staff WHERE active=1");
        jdbc.update(
                "INSERT OR IGNORE INTO organization_team_functions(organization_id,user_id,function) SELECT organization_id,user_id,function FROM workflow_staff WHERE active=1 AND function IN ('ENGINEER','WARRANTY','CONTRACTOR')");
    }

    public List<Workspace> mine(String actor) {
        syncLegacy();
        return jdbc.query(
                "SELECT t.*,o.name,o.type FROM organization_team t JOIN organizations o ON o.id=t.organization_id JOIN users u ON u.id=t.user_id WHERE t.user_id=? AND t.active=1 AND u.status='ACTIVE' ORDER BY o.name,t.role",
                (r, n) ->
                        new Workspace(
                                r.getString("organization_id"),
                                r.getString("name"),
                                Kind.valueOf(r.getString("type")),
                                TeamRole.valueOf(r.getString("role")),
                                r.getString("department"),
                                functions(r.getString("organization_id"), actor)),
                actor);
    }

    public List<Organization> organizations(String actor) {
        if (!system(actor)) throw denied();
        return jdbc.query(
                "SELECT id,name,type FROM organizations WHERE (? IS NULL OR id IN (SELECT organization_id FROM guided_demo_organizations WHERE session_id=?)) ORDER BY name",
                (r, n) ->
                        new Organization(
                                r.getString(1), r.getString(2), Kind.valueOf(r.getString(3))),
                demo(actor),
                demo(actor));
    }

    @Transactional
    public Organization create(String name, Kind kind, String actor) {
        if (!system(actor)) throw denied();
        String org = id();
        jdbc.update(
                "INSERT INTO organizations(id,name,type) VALUES (?,?,?)", org, name, kind.name());
        if (demo(actor) != null)
            jdbc.update(
                    "INSERT INTO guided_demo_organizations(session_id,organization_id) VALUES (?,?)",
                    demo(actor),
                    org);
        return new Organization(org, name, kind);
    }

    @Transactional
    public void link(String org, String house, boolean active, String actor) {
        if (!system(actor)) throw denied();
        scope(org, actor);
        if (demo(actor) != null
                && !exists(
                        "SELECT count(*) FROM guided_demo_houses WHERE session_id=? AND house_id=?",
                        demo(actor),
                        house)) throw denied();
        if (exists(
                "SELECT count(*) FROM organizations WHERE id=? AND type='MANAGEMENT_COMPANY'",
                org)) {
            if (active
                    && exists(
                            "SELECT count(*) FROM organization_houses WHERE house_id=? AND organization_id<>? AND status='ACTIVE'",
                            house,
                            org)) throw invalid("У дома уже есть управляющая организация");
            jdbc.update(
                    "INSERT INTO organization_houses(organization_id,house_id,status) VALUES (?,?,?) ON CONFLICT(organization_id,house_id) DO UPDATE SET status=excluded.status",
                    org,
                    house,
                    active ? "ACTIVE" : "SUSPENDED");
            jdbc.update(
                    "INSERT OR REPLACE INTO uk_admin_houses(organization_id,user_id,house_id,access_status) SELECT organization_id,user_id,?,? FROM organization_team WHERE organization_id=? AND role='ADMIN' AND active=1",
                    house,
                    active ? "ACTIVE" : "SUSPENDED",
                    org);
        }
        if (exists(
                "SELECT count(*) FROM organizations WHERE id=? AND type='MANAGEMENT_COMPANY'", org))
            jdbc.update(
                    "UPDATE house_memberships SET access_status=? WHERE source_organization_id=? AND house_id=? AND role IN ('DISPATCHER','HOUSE_ADMIN') AND access_status IN ('ACTIVE','SUSPENDED')",
                    active ? "ACTIVE" : "SUSPENDED",
                    org,
                    house);
        jdbc.update(
                "INSERT INTO organization_work_houses(organization_id,house_id,active) VALUES (?,?,?) ON CONFLICT(organization_id,house_id) DO UPDATE SET active=excluded.active",
                org,
                house,
                active ? 1 : 0);
    }

    public List<Map<String, Object>> houses(String org, String actor) {
        if (system(actor)) scope(org, actor);
        if (!system(actor) && mine(actor).stream().noneMatch(w -> w.id().equals(org)))
            throw denied();
        return jdbc.queryForList(
                "SELECT h.id,h.address FROM organization_work_houses w JOIN houses h ON h.id=w.house_id WHERE w.organization_id=? AND w.active=1 ORDER BY h.address",
                org);
    }

    public List<Member> members(String org, String actor) {
        if (!role(org, actor, TeamRole.ADMIN) && !role(org, actor, TeamRole.COORDINATOR))
            throw denied();
        return jdbc.query(
                "SELECT t.*,u.display_name FROM organization_team t JOIN users u ON u.id=t.user_id WHERE t.organization_id=? ORDER BY u.display_name,t.role",
                (r, n) ->
                        new Member(
                                r.getString("user_id"),
                                r.getString("display_name"),
                                TeamRole.valueOf(r.getString("role")),
                                r.getString("department"),
                                r.getInt("active") == 1,
                                functions(org, r.getString("user_id"))),
                org);
    }

    private String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Invitation> invitations(String org, String actor) {
        if (!system(actor)) admin(org, actor);
        else scope(org, actor);
        return jdbc.query(
                "SELECT * FROM organization_team_invitations WHERE organization_id=? ORDER BY expires_at DESC",
                (r, n) ->
                        new Invitation(
                                r.getString("id"),
                                TeamRole.valueOf(r.getString("role")),
                                r.getString("department"),
                                r.getString("expires_at"),
                                r.getString("accepted_by") != null,
                                r.getInt("revoked") == 1),
                org);
    }

    @Transactional
    public CreatedInvitation invite(
            String org, TeamRole role, String department, List<Function> functions, String actor) {
        if (!system(actor)) admin(org, actor);
        else scope(org, actor);
        if (role == TeamRole.SPECIALIST && functions.isEmpty())
            throw invalid("Выберите хотя бы одну функцию специалиста");
        String key = id();
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = "org_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String expires = Instant.now().plus(7, ChronoUnit.DAYS).toString();
        String json =
                "["
                        + String.join(
                                ",",
                                functions.stream()
                                        .distinct()
                                        .map(f -> "\"" + f.name() + "\"")
                                        .toList())
                        + "]";
        jdbc.update(
                "INSERT INTO organization_team_invitations(id,token_hash,organization_id,role,department,functions_json,created_by,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                key,
                hash(token),
                org,
                role.name(),
                department,
                json,
                actor,
                expires);
        return new CreatedInvitation(
                token, new Invitation(key, role, department, expires, false, false));
    }

    public Map<String, Object> invitation(String token) {
        var rows =
                jdbc.queryForList(
                        "SELECT i.role,i.department,i.functions_json,i.expires_at,o.name AS organizationName FROM organization_team_invitations i JOIN organizations o ON o.id=i.organization_id WHERE i.token_hash=? AND i.revoked=0 AND i.accepted_by IS NULL",
                        hash(token));
        if (rows.isEmpty()
                || Instant.parse((String) rows.getFirst().get("expires_at"))
                        .isBefore(Instant.now())) throw invalid("Приглашение недействительно");
        return rows.getFirst();
    }

    @Transactional
    public void accept(String token, String actor) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM organization_team_invitations WHERE token_hash=?",
                        hash(token));
        if (rows.isEmpty()) throw invalid("Приглашение не найдено");
        var r = rows.getFirst();
        if (r.get("accepted_by") != null
                || ((Number) r.get("revoked")).intValue() != 0
                || Instant.parse((String) r.get("expires_at")).isBefore(Instant.now()))
            throw invalid("Приглашение уже использовано или недействительно");
        if (!exists("SELECT count(*) FROM users WHERE id=? AND status='ACTIVE'", actor))
            throw denied();
        if (jdbc.update(
                        "UPDATE organization_team_invitations SET accepted_by=? WHERE id=? AND accepted_by IS NULL AND revoked=0",
                        actor,
                        r.get("id"))
                != 1) throw conflict();
        jdbc.update(
                "INSERT INTO organization_team(organization_id,user_id,role,department) VALUES (?,?,?,?) ON CONFLICT(organization_id,user_id,role) DO UPDATE SET active=1,department=excluded.department",
                r.get("organization_id"),
                actor,
                r.get("role"),
                r.get("department"));
        if ("ADMIN".equals(r.get("role"))
                && exists(
                        "SELECT count(*) FROM organizations WHERE id=? AND type='MANAGEMENT_COMPANY'",
                        r.get("organization_id"))) {
            jdbc.update(
                    "INSERT INTO organization_memberships(organization_id,user_id,role,access_status) VALUES (?,?,'UK_ADMIN','ACTIVE') ON CONFLICT(organization_id,user_id,role) DO UPDATE SET access_status='ACTIVE'",
                    r.get("organization_id"),
                    actor);
            jdbc.update(
                    "INSERT OR REPLACE INTO uk_admin_houses(organization_id,user_id,house_id,access_status) SELECT organization_id,?,house_id,'ACTIVE' FROM organization_houses WHERE organization_id=? AND status='ACTIVE'",
                    actor,
                    r.get("organization_id"));
        }
        for (var f :
                jdbc.query(
                        "SELECT value FROM json_each(?)",
                        (rs, n) -> Function.valueOf(rs.getString(1)),
                        r.get("functions_json")))
            jdbc.update(
                    "INSERT OR IGNORE INTO organization_team_functions VALUES (?,?,?)",
                    r.get("organization_id"),
                    actor,
                    f.name());
    }

    @Transactional
    public void revokeInvitation(String org, String key, String actor) {
        if (!system(actor)) admin(org, actor);
        else scope(org, actor);
        if (jdbc.update(
                        "UPDATE organization_team_invitations SET revoked=1 WHERE id=? AND organization_id=? AND accepted_by IS NULL",
                        key,
                        org)
                != 1) throw invalid("Приглашение уже использовано или недоступно");
    }

    @Transactional
    public void memberRole(String org, String user, TeamRole role, boolean active, String actor) {
        admin(org, actor);
        if (active && !role(org, user, role))
            throw invalid("Нового сотрудника подключайте по приглашению");
        if (!active) {
            if (role == TeamRole.ADMIN
                    && exists(
                            "SELECT count(*) FROM organization_team WHERE organization_id=? AND user_id=? AND role='ADMIN' AND active=1",
                            org,
                            user)
                    && !exists(
                            "SELECT count(*) FROM organization_team WHERE organization_id=? AND user_id<>? AND role='ADMIN' AND active=1",
                            org,
                            user)) throw invalid("Сначала назначьте другого администратора");
            if (exists(
                    "SELECT count(*) FROM resident_cases c JOIN case_collaborators a ON a.case_id=c.id WHERE a.user_id=? AND a.source_organization_id=? AND c.status<>'CLOSED'",
                    user,
                    org)) throw invalid("Сначала переназначьте незавершённые обращения сотрудника");
        }
        jdbc.update(
                "UPDATE organization_team SET active=? WHERE organization_id=? AND user_id=? AND role=?",
                active ? 1 : 0,
                org,
                user,
                role.name());
        if (!active && role != TeamRole.ADMIN)
            jdbc.update(
                    "UPDATE workflow_staff SET active=0 WHERE organization_id=? AND user_id=? AND "
                            + (role == TeamRole.COORDINATOR
                                    ? "function='COORDINATOR'"
                                    : "function<>'COORDINATOR'"),
                    org,
                    user);
        if (!active && role == TeamRole.ADMIN) {
            jdbc.update(
                    "UPDATE organization_memberships SET access_status='REVOKED' WHERE organization_id=? AND user_id=? AND role='UK_ADMIN'",
                    org,
                    user);
            jdbc.update(
                    "UPDATE uk_admin_houses SET access_status='REVOKED' WHERE organization_id=? AND user_id=?",
                    org,
                    user);
        }
    }

    @Transactional
    public void functions(String org, String user, List<Function> functions, String actor) {
        admin(org, actor);
        if (!role(org, user, TeamRole.SPECIALIST) || functions.isEmpty())
            throw invalid("Выберите действующего специалиста и хотя бы одну функцию");
        for (var old : functions(org, user))
            if (!functions.contains(old)) {
                if (exists(
                        "SELECT count(*) FROM case_collaborators a JOIN resident_cases c ON c.id=a.case_id WHERE a.source_organization_id=? AND a.user_id=? AND a.function=? AND c.status<>'CLOSED'",
                        org,
                        user,
                        old.name()))
                    throw invalid(
                            "Сначала снимите или переназначьте незавершённые задачи этой функции");
                jdbc.update(
                        "UPDATE workflow_staff SET active=0 WHERE organization_id=? AND user_id=? AND function=?",
                        org,
                        user,
                        old.name());
            }
        jdbc.update(
                "DELETE FROM organization_team_functions WHERE organization_id=? AND user_id=?",
                org,
                user);
        for (var f : new HashSet<>(functions))
            jdbc.update(
                    "INSERT INTO organization_team_functions VALUES (?,?,?)", org, user, f.name());
    }

    @Transactional
    public void selfCoordinator(String org, boolean active, String actor) {
        admin(org, actor);
        if (!active) {
            memberRole(org, actor, TeamRole.COORDINATOR, false, actor);
            return;
        }
        jdbc.update(
                "INSERT INTO organization_team(organization_id,user_id,role) VALUES (?,?,'COORDINATOR') ON CONFLICT(organization_id,user_id,role) DO UPDATE SET active=1",
                org,
                actor);
    }

    @Transactional
    public int reassignCoordinator(String org, String from, String to, String actor) {
        admin(org, actor);
        if (from.equals(to)
                || !role(org, to, TeamRole.COORDINATOR)
                || !exists(
                        "SELECT count(*) FROM organization_team WHERE organization_id=? AND user_id=? AND role='COORDINATOR'",
                        org,
                        from))
            throw invalid("Выберите другого действующего координатора своей организации");
        var ids =
                jdbc.query(
                        "SELECT c.id FROM resident_cases c JOIN case_collaborators a ON a.case_id=c.id JOIN organization_work_houses h ON h.house_id=c.house_id AND h.organization_id=a.source_organization_id LEFT JOIN organization_case_owners o ON o.case_id=c.id WHERE a.user_id=? AND a.function='COORDINATOR' AND a.source_organization_id=? AND h.active=1 AND c.internal_work=1 AND c.kind<>'PAYMENT' AND c.status<>'CLOSED' AND (o.organization_id IS NULL OR o.organization_id=?)",
                        (r, n) -> r.getString(1),
                        from,
                        org,
                        org);
        for (String key : ids) {
            jdbc.update(
                    "INSERT INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'COORDINATOR',?,?,?) ON CONFLICT(case_id,user_id,function) DO UPDATE SET source_organization_id=excluded.source_organization_id,assigned_by=excluded.assigned_by",
                    key,
                    to,
                    actor,
                    Instant.now().toString(),
                    org);
            jdbc.update(
                    "UPDATE resident_cases SET responsible_user_id=CASE WHEN responsible_user_id=? THEN ? ELSE responsible_user_id END,revision=revision+1,updated_at=? WHERE id=?",
                    from,
                    to,
                    Instant.now().toString(),
                    key);
            jdbc.update(
                    "DELETE FROM case_collaborators WHERE case_id=? AND user_id=? AND function='COORDINATOR' AND source_organization_id=?",
                    key,
                    from,
                    org);
            jdbc.update("DELETE FROM case_control_grants WHERE case_id=? AND user_id=?", key, from);
            caseEvent(
                    key,
                    "Администратор передал незавершённое обращение другому координатору организации",
                    actor);
            shared.sync(key, actor);
        }
        return ids.size();
    }

    public List<Organization> partners(String org, String actor) {
        if (!role(org, actor, TeamRole.ADMIN) && !role(org, actor, TeamRole.COORDINATOR))
            throw denied();
        return jdbc.query(
                "SELECT DISTINCT o.id,o.name,o.type FROM organizations o JOIN organization_work_houses a ON a.organization_id=o.id JOIN organization_work_houses b ON b.house_id=a.house_id WHERE b.organization_id=? AND a.active=1 AND b.active=1 AND o.id<>? ORDER BY o.name",
                (r, n) ->
                        new Organization(
                                r.getString(1), r.getString(2), Kind.valueOf(r.getString(3))),
                org,
                org);
    }

    public List<ResidentCaseService.CaseView> assignedCases(
            String org, TeamRole mode, String actor) {
        if (!role(org, actor, mode) || mode == TeamRole.ADMIN) throw denied();
        var result = new LinkedHashMap<String, ResidentCaseService.CaseView>();
        String filter =
                mode == TeamRole.COORDINATOR
                        ? "a.function='COORDINATOR'"
                        : "a.function IN ('ENGINEER','WARRANTY','CONTRACTOR')";
        for (String key :
                jdbc.query(
                        "SELECT DISTINCT a.case_id FROM case_collaborators a JOIN resident_cases c ON c.id=a.case_id JOIN organization_work_houses h ON h.house_id=c.house_id AND h.organization_id=a.source_organization_id WHERE a.user_id=? AND a.source_organization_id=? AND h.active=1 AND "
                                + filter,
                        (r, n) -> r.getString(1),
                        actor,
                        org)) {
            try {
                var c = cases.get(key, actor);
                if (c.kind() != CaseKind.PAYMENT && c.fullAccess()) {
                    String group = c.workflowCaseId() == null ? c.id() : c.workflowCaseId();
                    if (!result.containsKey(group) || c.id().equals(group)) result.put(group, c);
                }
            } catch (BusinessException e) {
                if (!"CASE_NOT_FOUND".equals(e.getCode())) throw e;
            }
        }
        return List.copyOf(result.values());
    }

    public List<Map<String, Object>> tasks(String org, TeamRole mode, String actor) {
        var result = new ArrayList<Map<String, Object>>();
        for (var c : assignedCases(org, mode, actor)) {
            String root = c.workflowCaseId() == null ? c.id() : c.workflowCaseId();
            for (var r :
                    jdbc.queryForList(
                            "SELECT id,location AS title,starts_at AS dueAt,state,inspector_id AS assignee FROM case_inspections WHERE (case_id=? OR case_id=?) AND state NOT IN ('CANCELLED','COMPLETED')",
                            root,
                            c.id()))
                if (mode == TeamRole.COORDINATOR || actor.equals(r.get("assignee"))) {
                    r.put("caseId", c.id());
                    r.put("kind", "INSPECTION");
                    r.put("description", c.description());
                    result.add(r);
                }
            for (var r :
                    jdbc.queryForList(
                            "SELECT id,title,due_at AS dueAt,state,assignee_id AS assignee FROM case_work_orders WHERE (case_id=? OR case_id=?) AND state NOT IN ('CANCELLED','ACCEPTED')",
                            root,
                            c.id()))
                if (mode == TeamRole.COORDINATOR || actor.equals(r.get("assignee"))) {
                    r.put("caseId", c.id());
                    r.put("kind", "WORK");
                    r.put("description", c.description());
                    result.add(r);
                }
            if (mode == TeamRole.SPECIALIST
                    && access.assigned(root, c.houseId(), actor, CaseFunction.WARRANTY)
                    && c.status() != CaseStatus.CLOSED)
                result.add(
                        new HashMap<>(
                                Map.of(
                                        "id",
                                        root + "-review",
                                        "caseId",
                                        c.id(),
                                        "kind",
                                        "WARRANTY",
                                        "title",
                                        "Рассмотреть гарантийный случай",
                                        "description",
                                        c.description(),
                                        "state",
                                        c.status().name())));
        }
        return result;
    }

    private Transfer transfer(String key) {
        return jdbc
                .query(
                        "SELECT t.*,c.description,f.name AS from_name,o.name AS to_name FROM organization_case_transfers t JOIN resident_cases c ON c.id=t.case_id JOIN organizations f ON f.id=t.from_organization_id JOIN organizations o ON o.id=t.to_organization_id WHERE t.id=?",
                        (r, n) ->
                                new Transfer(
                                        r.getString("id"),
                                        r.getString("case_id"),
                                        r.getString("description"),
                                        r.getString("from_organization_id"),
                                        r.getString("from_name"),
                                        r.getString("to_organization_id"),
                                        r.getString("to_name"),
                                        TransferState.valueOf(r.getString("state")),
                                        r.getString("reason"),
                                        r.getString("reply"),
                                        r.getInt("revision"),
                                        r.getString("created_at"),
                                        jdbc.queryForList(
                                                "SELECT h.state,h.note,h.created_at,u.display_name FROM organization_transfer_history h JOIN users u ON u.id=h.actor_id WHERE h.transfer_id=? ORDER BY h.created_at,h.rowid",
                                                key)),
                        key)
                .stream()
                .findFirst()
                .orElseThrow(this::denied);
    }

    public List<Transfer> transfers(String org, String actor) {
        coordinate(org, actor);
        return jdbc
                .query(
                        "SELECT id FROM organization_case_transfers WHERE from_organization_id=? OR to_organization_id=? ORDER BY created_at DESC",
                        (r, n) -> r.getString(1),
                        org,
                        org)
                .stream()
                .map(this::transfer)
                .toList();
    }

    private boolean ownsCase(String org, String caseId, String house, String actor) {
        return exists(
                        "SELECT count(*) FROM case_collaborators WHERE (case_id=? OR case_id=?) AND user_id=? AND function='COORDINATOR' AND source_organization_id=?",
                        caseId,
                        shared.root(caseId),
                        actor,
                        org)
                || access.dispatcher(house, actor)
                        && exists(
                                "SELECT count(*) FROM organization_houses WHERE house_id=? AND organization_id=? AND status='ACTIVE'",
                                house,
                                org);
    }

    private void caseEvent(String caseId, String text, String actor) {
        jdbc.update(
                "INSERT INTO case_events(id,case_id,actor_id,type,text,created_at) VALUES (?,?,?,'UPDATED',?,?)",
                id(),
                caseId,
                actor,
                text,
                Instant.now().toString());
    }

    private void event(String key, TransferState state, String note, String actor) {
        jdbc.update(
                "INSERT INTO organization_transfer_history VALUES (?,?,?,?,?,?)",
                id(),
                key,
                state.name(),
                note,
                actor,
                Instant.now().toString());
    }

    @Transactional
    public Transfer offer(
            String org, String caseId, String target, String reason, int revision, String actor) {
        coordinate(org, actor);
        var c = cases.get(caseId, actor);
        if (c.kind() == CaseKind.PAYMENT)
            throw invalid("Личные финансовые дела не передаются подрядчикам");
        if (!access.coordinator(c.id(), c.houseId(), actor)
                || !ownsCase(org, c.id(), c.houseId(), actor)) throw denied();
        String rootCase = c.workflowCaseId() == null ? c.id() : c.workflowCaseId();
        if (exists(
                "SELECT count(*) FROM organization_case_owners WHERE case_id=? AND organization_id<>?",
                rootCase,
                org)) throw invalid("Передачу оформляет текущая ответственная организация");
        if (!exists(
                "SELECT count(*) FROM organization_team t JOIN users u ON u.id=t.user_id WHERE t.organization_id=? AND t.role='COORDINATOR' AND t.active=1 AND u.status='ACTIVE'",
                target)) throw invalid("У принимающей организации ещё нет координатора");
        if (c.status() == CaseStatus.CLOSED) throw invalid("Обращение уже закрыто");
        if (org.equals(target)
                || !exists(
                        "SELECT count(*) FROM organization_work_houses WHERE organization_id=? AND house_id=? AND active=1",
                        target,
                        c.houseId())
                || !exists(
                        "SELECT count(*) FROM organization_work_houses WHERE organization_id=? AND house_id=? AND active=1",
                        org,
                        c.houseId()))
            throw invalid("Выберите организацию, подключённую к этому дому");
        if (exists(
                "SELECT count(*) FROM organization_case_transfers WHERE case_id=? AND state='OFFERED'",
                rootCase)) throw invalid("Сначала дождитесь ответа на текущую передачу");
        if (jdbc.update(
                        "UPDATE resident_cases SET revision=revision+1 WHERE id=? AND revision=?",
                        caseId,
                        revision)
                != 1) throw conflict();
        if (!caseId.equals(rootCase))
            jdbc.update("UPDATE resident_cases SET revision=revision+1 WHERE id=?", rootCase);
        caseId = rootCase;
        jdbc.update("INSERT OR IGNORE INTO organization_case_owners VALUES (?,?)", rootCase, org);
        String key = id(), now = Instant.now().toString();
        jdbc.update(
                "INSERT INTO organization_case_transfers(id,case_id,from_organization_id,to_organization_id,state,reason,created_by,created_at,updated_at) VALUES (?,?,?,?,'OFFERED',?,?,?,?)",
                key,
                caseId,
                org,
                target,
                reason,
                actor,
                now,
                now);
        caseEvent(
                caseId,
                "Обращение направлено организации. Ответственный сохраняется до подтверждения приёма.",
                actor);
        event(key, TransferState.OFFERED, reason, actor);
        return transfer(key);
    }

    @Transactional
    public Transfer reply(
            String key, TransferState state, String note, int revision, String actor) {
        var t = transfer(key);
        coordinate(t.toId(), actor);
        var caseRow =
                jdbc.queryForMap(
                        "SELECT house_id,status FROM resident_cases WHERE id=?", t.caseId());
        if (CaseStatus.CLOSED.name().equals(caseRow.get("status")))
            throw invalid("Обращение уже закрыто");
        String house = (String) caseRow.get("house_id");
        if (!exists(
                "SELECT count(*) FROM organization_work_houses WHERE organization_id=? AND house_id=? AND active=1",
                t.toId(),
                house)) throw denied();
        if (t.state() != TransferState.OFFERED || state == TransferState.OFFERED)
            throw invalid("Передача уже обработана");
        if (jdbc.update(
                        "UPDATE organization_case_transfers SET state=?,reply=?,accepted_by=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND state='OFFERED'",
                        state.name(),
                        note,
                        actor,
                        Instant.now().toString(),
                        key,
                        revision)
                != 1) throw conflict();
        if (state == TransferState.ACCEPTED) {
            jdbc.update(
                    "INSERT INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'COORDINATOR',?,?,?) ON CONFLICT(case_id,user_id,function) DO UPDATE SET source_organization_id=excluded.source_organization_id",
                    t.caseId(),
                    actor,
                    actor,
                    Instant.now().toString(),
                    t.toId());
            jdbc.update(
                    "UPDATE resident_cases SET responsible_user_id=?,next_step=?,status=CASE WHEN status='RECEIVED' THEN 'IN_REVIEW' ELSE status END,revision=revision+1,updated_at=? WHERE id=?",
                    actor,
                    "Организация «"
                            + t.toName()
                            + "» приняла обращение. Координатор уточняет следующий шаг",
                    Instant.now().toString(),
                    t.caseId());
        }
        if (state == TransferState.ACCEPTED) {
            var c = cases.get(t.caseId(), actor);
            jdbc.update(
                    "INSERT INTO organization_case_owners VALUES (?,?) ON CONFLICT(case_id) DO UPDATE SET organization_id=excluded.organization_id",
                    c.workflowCaseId() == null ? c.id() : c.workflowCaseId(),
                    t.toId());
            shared.sync(t.caseId(), actor);
        }
        caseEvent(
                t.caseId(),
                state == TransferState.ACCEPTED
                        ? "Организация «"
                                + t.toName()
                                + "» приняла обращение. Назначен координатор."
                        : "Организация «" + t.toName() + "» вернула обращение: " + note,
                actor);
        event(key, state, note, actor);
        return transfer(key);
    }

    @Transactional
    public void assign(
            String org,
            String caseId,
            String target,
            CaseFunction function,
            int revision,
            boolean enabled,
            String actor) {
        coordinate(org, actor);
        var c = cases.get(caseId, actor);
        if (!access.coordinator(c.id(), c.houseId(), actor)
                || !ownsCase(org, c.id(), c.houseId(), actor)) throw denied();
        if (exists(
                "SELECT count(*) FROM organization_case_owners WHERE case_id=? AND organization_id<>?",
                c.workflowCaseId() == null ? c.id() : c.workflowCaseId(),
                org)) throw denied();
        if (!exists(
                "SELECT count(*) FROM organization_work_houses WHERE organization_id=? AND house_id=? AND active=1",
                org,
                c.houseId())) throw denied();
        boolean permitted =
                function == CaseFunction.COORDINATOR
                        ? role(org, target, TeamRole.COORDINATOR)
                        : function != CaseFunction.BILLING
                                && role(org, target, TeamRole.SPECIALIST)
                                && exists(
                                        "SELECT count(*) FROM organization_team_functions WHERE organization_id=? AND user_id=? AND function=?",
                                        org,
                                        target,
                                        function.name());
        if (!permitted) throw invalid("Выберите сотрудника своей организации с нужной функцией");
        if (c.kind() == CaseKind.PAYMENT)
            throw invalid("Личные финансовые дела недоступны этому кабинету");
        if (!enabled && access.hasTasks(caseId, target, function))
            throw invalid("Сначала завершите или переназначьте осмотры и работы этого сотрудника");
        if (!enabled && target.equals(c.responsibleUserId()))
            throw invalid("Сначала смените ответственного за обращение");
        if (jdbc.update(
                        "UPDATE resident_cases SET revision=revision+1 WHERE id=? AND revision=?",
                        caseId,
                        revision)
                != 1) throw conflict();
        String workCase = shared.root(caseId);
        if (!caseId.equals(workCase))
            jdbc.update("UPDATE resident_cases SET revision=revision+1 WHERE id=?", workCase);
        caseId = workCase;
        if (enabled)
            jdbc.update(
                    "INSERT INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,?,?,?,?) ON CONFLICT(case_id,user_id,function) DO UPDATE SET source_organization_id=excluded.source_organization_id",
                    caseId,
                    target,
                    function.name(),
                    actor,
                    Instant.now().toString(),
                    org);
        else
            jdbc.update(
                    "DELETE FROM case_collaborators WHERE case_id=? AND user_id=? AND function=? AND source_organization_id=?",
                    caseId,
                    target,
                    function.name(),
                    org);
        caseEvent(
                caseId,
                enabled
                        ? "Назначен участник обработки обращения"
                        : "Снято назначение участника обработки",
                actor);
    }
}
