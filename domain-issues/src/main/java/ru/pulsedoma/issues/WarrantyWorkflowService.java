package ru.pulsedoma.issues;

import static ru.pulsedoma.issues.WorkflowTypes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

@Service
public class WarrantyWorkflowService {
    private final JdbcTemplate jdbc;
    private final ResidentCaseService cases;
    private final CaseWorkAccess access;
    private final AttachmentStore store;
    private final ObjectMapper mapper;

    public WarrantyWorkflowService(
            JdbcTemplate jdbc,
            ResidentCaseService cases,
            CaseWorkAccess access,
            AttachmentStore store,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.access = access;
        this.store = store;
        this.mapper = mapper;
    }

    public record House(
            String id, String address, boolean canManageRegistry, boolean canReadRegistry) {}

    public record Staff(
            String userId,
            String name,
            CaseFunction function,
            String organizationLabel,
            String authority,
            boolean active) {}

    public record Invitation(
            String id,
            CaseFunction function,
            String organizationLabel,
            String address,
            String expiresAt,
            boolean accepted,
            boolean revoked) {}

    public record CreatedInvitation(String token, Invitation invitation) {}

    public record Warranty(
            String id,
            String houseId,
            String title,
            WarrantyObject objectType,
            WarrantyState state,
            String developer,
            String contractor,
            String contractReference,
            String basis,
            String transferredOn,
            String startsOn,
            String endsOn,
            String checkedBy,
            String checkedAt,
            int revision,
            List<Map<String, Object>> files,
            List<Map<String, Object>> history) {}

    public record Review(
            String id,
            String warrantyId,
            ReviewOutcome outcome,
            RefusalReason refusalReason,
            String basis,
            String explanation,
            String evidenceVersionId,
            String reviewer,
            String createdAt) {}

    public record Handoff(
            String id,
            String recipient,
            RecipientType recipientType,
            String channel,
            String basis,
            String authority,
            String statement,
            HandoffState state,
            String dueAt,
            int revision,
            List<HandoffEvent> events) {}

    public record HandoffEvent(
            HandoffState state,
            String note,
            String evidence_version_id,
            String created_at,
            String actor) {}

    public record InspectionReply(
            ResidentAction action,
            String comment,
            String act_id,
            String created_at,
            String actor) {}

    public record Act(
            String id,
            int version,
            ActState state,
            String inspectedAt,
            String participants,
            String findings,
            String defects,
            String preliminaryCause,
            String evidenceVersionId,
            String correctionReason,
            String author,
            String publishedAt) {}

    public record Inspection(
            String id,
            String inspectorId,
            String inspector,
            String location,
            String participants,
            String startsAt,
            String endsAt,
            InspectionState state,
            Availability residentAvailability,
            int revision,
            List<Act> acts,
            List<InspectionReply> responses) {}

    public record WorkOrder(
            String id,
            String title,
            String scope,
            String assigneeId,
            String assignee,
            WorkBasis basisType,
            String basis,
            String dueAt,
            String dueBasis,
            String actId,
            WorkState state,
            int revision,
            List<Map<String, Object>> reports) {}

    public record Workspace(
            int revision,
            String actorId,
            boolean isAuthor,
            boolean canCoordinate,
            boolean canReviewWarranty,
            boolean canInspect,
            List<ResidentCaseService.Staff> staff,
            List<Review> reviews,
            List<Handoff> handoffs,
            List<Inspection> inspections,
            List<WorkOrder> orders,
            List<Map<String, Object>> history) {}

    private boolean yes(String sql, Object... args) {
        return access.exists(sql, args);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String str(Map<String, Object> r, String k) {
        return r.get(k) == null ? null : r.get(k).toString();
    }

    private static int num(Map<String, Object> r, String k) {
        return ((Number) r.get(k)).intValue();
    }

    private static BusinessException invalid(String text) {
        return new BusinessException("INVALID_CASE", text);
    }

    private static BusinessException missing() {
        return new BusinessException("CASE_NOT_FOUND", "Материал недоступен");
    }

    private static BusinessException conflict() {
        return new BusinessException("CASE_CONFLICT", "Данные изменились. Обновите карточку");
    }

    private void manager(String house, String user) {
        if (!access.manager(house, user))
            throw new BusinessException(
                    "HOUSE_ACCESS_DENIED", "Нужны полномочия администратора УК этого дома");
    }

    private ResidentCaseService.CaseView caseView(String id, String user) {
        var c = cases.get(id, user);
        if (c.kind() == CaseKind.PAYMENT)
            throw invalid("Гарантийные работы не открываются внутри личного финансового дела");
        return c;
    }

    private void coordinate(ResidentCaseService.CaseView c, String user) {
        if (!access.coordinator(c.id(), c.houseId(), user)) throw missing();
    }

    private boolean engineer(ResidentCaseService.CaseView c, String user) {
        return access.assigned(c.id(), c.houseId(), user, CaseFunction.ENGINEER);
    }

    private boolean warranty(ResidentCaseService.CaseView c, String user) {
        return access.assigned(c.id(), c.houseId(), user, CaseFunction.WARRANTY);
    }

    private Map<String, Object> row(String table, String id) {
        var r = jdbc.queryForList("SELECT * FROM " + table + " WHERE id=?", id);
        if (r.isEmpty()) throw missing();
        return r.get(0);
    }

    private ResidentCaseService.CaseView entityCase(Map<String, Object> row, String user) {
        return caseView(str(row, "case_id"), user);
    }

    private void evidence(String version, String caseId) {
        if (version != null
                && !version.isBlank()
                && !yes(
                        "SELECT count(*) FROM case_document_versions v JOIN case_documents d ON d.id=v.document_id WHERE v.id=? AND d.case_id=?",
                        version,
                        caseId)) throw invalid("Документ должен принадлежать этому обращению");
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void history(String caseId, String entity, String action, String note, String user) {
        jdbc.update(
                "INSERT INTO workflow_history(id,case_id,entity_id,action,note,actor_id,created_at) VALUES (?,?,?,?,?,?,?)",
                id(),
                caseId,
                entity,
                action,
                note,
                user,
                now());
    }

    private void change(
            ResidentCaseService.CaseView c,
            String entity,
            String action,
            String note,
            int revision,
            String user,
            String next) {
        cases.workflowEvent(c.id(), user, revision, note, next);
        history(c.id(), entity, action, note, user);
    }

    public List<House> houses(String user) {
        return jdbc
                .query(
                        "SELECT id,address FROM houses ORDER BY address",
                        (rs, n) ->
                                new House(
                                        rs.getString(1),
                                        rs.getString(2),
                                        access.manager(rs.getString(1), user),
                                        access.manager(rs.getString(1), user)
                                                || access.dispatcher(rs.getString(1), user)
                                                || access.staffFunction(
                                                        rs.getString(1),
                                                        user,
                                                        CaseFunction.WARRANTY)
                                                || access.staffFunction(
                                                        rs.getString(1),
                                                        user,
                                                        CaseFunction.COORDINATOR)))
                .stream()
                .filter(
                        h ->
                                h.canManageRegistry()
                                        || access.dispatcher(h.id(), user)
                                        || access.staff(h.id(), user))
                .toList();
    }

    public List<Staff> staff(String house, String user) {
        manager(house, user);
        return jdbc.query(
                "SELECT s.*,u.display_name FROM workflow_staff s JOIN users u ON u.id=s.user_id WHERE house_id=? ORDER BY u.display_name,s.function",
                (rs, n) ->
                        new Staff(
                                rs.getString("user_id"),
                                rs.getString("display_name"),
                                CaseFunction.valueOf(rs.getString("function")),
                                rs.getString("organization_label"),
                                rs.getString("authority"),
                                rs.getInt("active") == 1),
                house);
    }

    @Transactional
    public void self(String house, String user) {
        manager(house, user);
        String org = organization(house, user);
        grant(
                house,
                user,
                CaseFunction.COORDINATOR,
                org,
                "Управляющая организация",
                "Рабочее сопровождение обращений своего дома",
                user);
    }

    private String organization(String house, String user) {
        manager(house, user);
        return jdbc.queryForObject(
                "SELECT organization_id FROM uk_admin_houses WHERE house_id=? AND user_id=? AND access_status='ACTIVE' LIMIT 1",
                String.class,
                house,
                user);
    }

    private void grant(
            String house,
            String target,
            CaseFunction function,
            String org,
            String label,
            String authority,
            String actor) {
        jdbc.update(
                """
            INSERT INTO workflow_staff(house_id,user_id,function,organization_id,organization_label,authority,granted_by,created_at) VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT(house_id,user_id,function) DO UPDATE SET active=1,organization_id=excluded.organization_id,organization_label=excluded.organization_label,authority=excluded.authority,granted_by=excluded.granted_by
            """,
                house,
                target,
                function.name(),
                org,
                label,
                authority,
                actor,
                now());
    }

    @Transactional
    public void revokeStaff(String house, String target, CaseFunction function, String user) {
        manager(house, user);
        jdbc.update(
                "UPDATE workflow_staff SET active=0 WHERE house_id=? AND user_id=? AND function=?",
                house,
                target,
                function.name());
        jdbc.update(
                "DELETE FROM case_collaborators WHERE user_id=? AND function=? AND case_id IN (SELECT id FROM resident_cases WHERE house_id=?)",
                target,
                function.name(),
                house);
        jdbc.update(
                "INSERT INTO audit_events(id,actor_id,action,entity,entity_id,created_at) VALUES (?,?,?,'workflow_staff',?,?)",
                id(),
                user,
                "WORKFLOW_ACCESS_REVOKED",
                house + ":" + target,
                now());
    }

    @Transactional
    public CreatedInvitation invite(String house, WorkflowRequests.Invite r, String user) {
        manager(house, user);
        if (!List.of(
                        CaseFunction.COORDINATOR,
                        CaseFunction.ENGINEER,
                        CaseFunction.WARRANTY,
                        CaseFunction.CONTRACTOR)
                .contains(r.function())) throw invalid("Выберите служебную функцию");
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = "wf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                id = id();
        jdbc.update(
                "INSERT INTO workflow_invitations(id,token_hash,house_id,function,organization_id,organization_label,authority,created_by,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id,
                hash(token),
                house,
                r.function().name(),
                organization(house, user),
                r.organizationLabel(),
                r.authority(),
                user,
                now(),
                Instant.now().plusSeconds(r.days() * 86400L).toString());
        return new CreatedInvitation(token, invitationById(id));
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Invitation invitationById(String id) {
        var r = row("workflow_invitations", id);
        return new Invitation(
                id,
                CaseFunction.valueOf(str(r, "function")),
                str(r, "organization_label"),
                jdbc.queryForObject(
                        "SELECT address FROM houses WHERE id=?", String.class, r.get("house_id")),
                str(r, "expires_at"),
                r.get("accepted_by") != null,
                r.get("revoked_at") != null);
    }

    public List<Invitation> invitations(String house, String user) {
        manager(house, user);
        return jdbc.query(
                "SELECT id FROM workflow_invitations WHERE house_id=? ORDER BY created_at DESC",
                (rs, n) -> invitationById(rs.getString(1)),
                house);
    }

    @Transactional
    public void revokeInvitation(String id, String user) {
        var r = row("workflow_invitations", id);
        manager(str(r, "house_id"), user);
        jdbc.update("UPDATE workflow_invitations SET revoked_at=? WHERE id=?", now(), id);
    }

    @Transactional
    public House accept(String token, String user) {
        var all =
                jdbc.queryForList(
                        "SELECT * FROM workflow_invitations WHERE token_hash=?", hash(token));
        if (all.isEmpty()) throw missing();
        var r = all.get(0);
        if (!yes("SELECT count(*) FROM users WHERE id=? AND status='ACTIVE'", user))
            throw missing();
        if (r.get("revoked_at") != null
                || Instant.parse(str(r, "expires_at")).isBefore(Instant.now())
                || !access.manager(str(r, "house_id"), str(r, "created_by")))
            throw invalid("Приглашение больше не действует");
        if (r.get("accepted_by") != null && !user.equals(str(r, "accepted_by")))
            throw invalid("Приглашение уже использовано");
        if (r.get("accepted_by") == null) {
            if (jdbc.update(
                            "UPDATE workflow_invitations SET accepted_by=? WHERE id=? AND accepted_by IS NULL",
                            user,
                            r.get("id"))
                    != 1) throw conflict();
            grant(
                    str(r, "house_id"),
                    user,
                    CaseFunction.valueOf(str(r, "function")),
                    str(r, "organization_id"),
                    str(r, "organization_label"),
                    str(r, "authority"),
                    str(r, "created_by"));
        }
        return new House(
                str(r, "house_id"),
                jdbc.queryForObject(
                        "SELECT address FROM houses WHERE id=?", String.class, r.get("house_id")),
                false,
                access.staffFunction(str(r, "house_id"), user, CaseFunction.WARRANTY)
                        || access.staffFunction(
                                str(r, "house_id"), user, CaseFunction.COORDINATOR));
    }

    private void registryAccess(String house, String user) {
        if (!access.manager(house, user)
                && !access.dispatcher(house, user)
                && !access.staffFunction(house, user, CaseFunction.WARRANTY)
                && !access.staffFunction(house, user, CaseFunction.COORDINATOR)) throw missing();
    }

    public List<Warranty> warranties(String house, String user) {
        registryAccess(house, user);
        return jdbc.query(
                "SELECT id FROM house_warranties WHERE house_id=? ORDER BY created_at DESC",
                (rs, n) -> warrantyView(rs.getString(1)),
                house);
    }

    private Warranty warrantyView(String id) {
        var r = row("house_warranties", id);
        return new Warranty(
                id,
                str(r, "house_id"),
                str(r, "title"),
                WarrantyObject.valueOf(str(r, "object_type")),
                WarrantyState.valueOf(str(r, "state")),
                str(r, "developer"),
                str(r, "contractor"),
                str(r, "contract_reference"),
                str(r, "basis"),
                str(r, "transferred_on"),
                str(r, "starts_on"),
                str(r, "ends_on"),
                str(r, "checked_by"),
                str(r, "checked_at"),
                num(r, "revision"),
                jdbc.queryForList(
                        "SELECT id,title,mime,size_bytes,created_at FROM warranty_files WHERE warranty_id=? ORDER BY created_at",
                        id),
                jdbc.queryForList(
                        "SELECT h.revision,h.reason,h.created_at,json_extract(h.snapshot_json,'$.title') AS title,json_extract(h.snapshot_json,'$.basis') AS basis,json_extract(h.snapshot_json,'$.contract_reference') AS contract_reference,json_extract(h.snapshot_json,'$.starts_on') AS starts_on,json_extract(h.snapshot_json,'$.ends_on') AS ends_on,u.display_name AS actor FROM warranty_history h JOIN users u ON u.id=h.actor_id WHERE warranty_id=? ORDER BY h.revision",
                        id));
    }

    @Transactional
    public Warranty saveWarranty(
            String house, String warrantyId, WorkflowRequests.Warranty r, String user) {
        manager(house, user);
        if (r.startsOn() != null && r.endsOn() != null && r.endsOn().isBefore(r.startsOn()))
            throw invalid("Проверьте даты гарантийного периода");
        if (r.state() == WarrantyState.VERIFIED
                && (optional(r.contractReference()) == null || optional(r.basis()) == null))
            throw invalid("Для проверки укажите документ и основание применимости условий");
        String id = warrantyId == null ? id() : warrantyId, time = now();
        if (warrantyId != null) {
            var old = row("house_warranties", id);
            if (!house.equals(str(old, "house_id"))) throw missing();
            if (num(old, "revision") != r.revision()) throw conflict();
            jdbc.update(
                    "UPDATE house_warranties SET title=?,object_type=?,state=?,developer=?,contractor=?,contract_reference=?,basis=?,transferred_on=?,starts_on=?,ends_on=?,checked_by=?,checked_at=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                    r.title(),
                    r.objectType().name(),
                    r.state().name(),
                    optional(r.developer()),
                    optional(r.contractor()),
                    optional(r.contractReference()),
                    r.basis(),
                    r.transferredOn(),
                    r.startsOn(),
                    r.endsOn(),
                    r.state() == WarrantyState.VERIFIED ? user : null,
                    r.state() == WarrantyState.VERIFIED ? time : null,
                    time,
                    id,
                    r.revision());
        } else {
            if (r.revision() != 0) throw conflict();
            jdbc.update(
                    "INSERT INTO house_warranties(id,house_id,title,object_type,state,developer,contractor,contract_reference,basis,transferred_on,starts_on,ends_on,checked_by,checked_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,
                    house,
                    r.title(),
                    r.objectType().name(),
                    r.state().name(),
                    optional(r.developer()),
                    optional(r.contractor()),
                    optional(r.contractReference()),
                    r.basis(),
                    r.transferredOn(),
                    r.startsOn(),
                    r.endsOn(),
                    r.state() == WarrantyState.VERIFIED ? user : null,
                    r.state() == WarrantyState.VERIFIED ? time : null,
                    time,
                    time);
        }
        var saved = row("house_warranties", id);
        try {
            jdbc.update(
                    "INSERT INTO warranty_history(id,warranty_id,actor_id,revision,snapshot_json,reason,created_at) VALUES (?,?,?,?,?,?,?)",
                    id(),
                    id,
                    user,
                    num(saved, "revision"),
                    mapper.writeValueAsString(saved),
                    r.reason(),
                    time);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return warrantyView(id);
    }

    @Transactional
    public Warranty uploadWarranty(
            String id, String title, byte[] bytes, String mime, int revision, String user) {
        var r = row("house_warranties", id);
        manager(str(r, "house_id"), user);
        if (bytes.length == 0
                || bytes.length > 10 * 1024 * 1024
                || !ResidentCaseService.validDocument(bytes, mime))
            throw invalid("Нужен PDF, JPEG или PNG до 10 МБ");
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM warranty_files WHERE warranty_id=?",
                        Integer.class,
                        id)
                >= 20) throw invalid("В записи уже 20 документов");
        if (num(r, "revision") != revision) throw conflict();
        String file = id(), key = "warranties/" + id + "/" + file;
        store.put(key, bytes, mime);
        jdbc.update(
                "INSERT INTO warranty_files(id,warranty_id,title,storage_key,mime,size_bytes,uploaded_by,created_at) VALUES (?,?,?,?,?,?,?,?)",
                file,
                id,
                title,
                key,
                mime,
                bytes.length,
                user,
                now());
        jdbc.update(
                "UPDATE house_warranties SET revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                now(),
                id,
                revision);
        try {
            jdbc.update(
                    "INSERT INTO warranty_history(id,warranty_id,actor_id,revision,snapshot_json,reason,created_at) VALUES (?,?,?,?,?,?,?)",
                    id(),
                    id,
                    user,
                    revision + 1,
                    mapper.writeValueAsString(row("house_warranties", id)),
                    "Добавлен документ: " + title,
                    now());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return warrantyView(id);
    }

    public ResidentCaseService.FileData warrantyFile(String id, String user) {
        var r = row("warranty_files", id);
        var w = row("house_warranties", str(r, "warranty_id"));
        registryAccess(str(w, "house_id"), user);
        return new ResidentCaseService.FileData(store.get(str(r, "storage_key")), str(r, "mime"));
    }

    public Workspace workspace(String id, String user) {
        var c = caseView(id, user);
        boolean coord = access.coordinator(id, c.houseId(), user);
        var reviews =
                jdbc.query(
                        "SELECT r.*,u.display_name FROM case_warranty_reviews r JOIN users u ON u.id=r.reviewed_by WHERE case_id=? ORDER BY r.rowid DESC",
                        (rs, n) ->
                                new Review(
                                        rs.getString("id"),
                                        rs.getString("warranty_id"),
                                        ReviewOutcome.valueOf(rs.getString("outcome")),
                                        rs.getString("refusal_reason") == null
                                                ? null
                                                : RefusalReason.valueOf(
                                                        rs.getString("refusal_reason")),
                                        rs.getString("basis"),
                                        rs.getString("explanation"),
                                        rs.getString("evidence_version_id"),
                                        rs.getString("display_name"),
                                        rs.getString("created_at")),
                        id);
        var handoffs =
                jdbc.query(
                        "SELECT * FROM case_handoffs WHERE case_id=? ORDER BY rowid DESC",
                        (rs, n) ->
                                new Handoff(
                                        rs.getString("id"),
                                        rs.getString("recipient"),
                                        RecipientType.valueOf(rs.getString("recipient_type")),
                                        rs.getString("channel"),
                                        rs.getString("basis"),
                                        rs.getString("authority"),
                                        rs.getString("statement"),
                                        HandoffState.valueOf(rs.getString("state")),
                                        rs.getString("due_at"),
                                        rs.getInt("revision"),
                                        jdbc.query(
                                                "SELECT e.state,e.note,e.evidence_version_id,e.created_at,u.display_name AS actor FROM case_handoff_events e JOIN users u ON u.id=e.actor_id WHERE handoff_id=? ORDER BY e.rowid",
                                                (e, k) ->
                                                        new HandoffEvent(
                                                                HandoffState.valueOf(
                                                                        e.getString(1)),
                                                                e.getString(2),
                                                                e.getString(3),
                                                                e.getString(4),
                                                                e.getString(5)),
                                                rs.getString("id"))),
                        id);
        var inspections =
                jdbc.query(
                        "SELECT i.*,u.display_name FROM case_inspections i JOIN users u ON u.id=i.inspector_id WHERE case_id=? ORDER BY i.rowid DESC",
                        (rs, n) ->
                                new Inspection(
                                        rs.getString("id"),
                                        rs.getString("inspector_id"),
                                        rs.getString("display_name"),
                                        rs.getString("location"),
                                        rs.getString("participants"),
                                        rs.getString("starts_at"),
                                        rs.getString("ends_at"),
                                        InspectionState.valueOf(rs.getString("state")),
                                        Availability.valueOf(rs.getString("resident_availability")),
                                        rs.getInt("revision"),
                                        acts(rs.getString("id"), user, c),
                                        jdbc.query(
                                                "SELECT r.action,r.comment,r.act_id,r.created_at,u.display_name AS actor FROM inspection_responses r JOIN users u ON u.id=r.actor_id WHERE inspection_id=? ORDER BY r.rowid",
                                                (e, k) ->
                                                        new InspectionReply(
                                                                ResidentAction.valueOf(
                                                                        e.getString(1)),
                                                                e.getString(2),
                                                                e.getString(3),
                                                                e.getString(4),
                                                                e.getString(5)),
                                                rs.getString("id"))),
                        id);
        var orders =
                jdbc.query(
                        "SELECT o.*,u.display_name FROM case_work_orders o JOIN users u ON u.id=o.assignee_id WHERE case_id=? ORDER BY o.rowid DESC",
                        (rs, n) ->
                                new WorkOrder(
                                        rs.getString("id"),
                                        rs.getString("title"),
                                        rs.getString("scope"),
                                        rs.getString("assignee_id"),
                                        rs.getString("display_name"),
                                        WorkBasis.valueOf(rs.getString("basis_type")),
                                        rs.getString("basis"),
                                        rs.getString("due_at"),
                                        rs.getString("due_basis"),
                                        rs.getString("act_id"),
                                        WorkState.valueOf(rs.getString("state")),
                                        rs.getInt("revision"),
                                        jdbc.queryForList(
                                                "SELECT r.id,r.version,r.report,r.evidence_version_id,r.created_at,u.display_name AS author FROM work_order_reports r JOIN users u ON u.id=r.created_by WHERE work_order_id=? ORDER BY version DESC",
                                                rs.getString("id"))),
                        id);
        return new Workspace(
                c.revision(),
                user,
                c.isAuthor(),
                coord,
                warranty(c, user),
                engineer(c, user),
                coord ? access.candidates(c.houseId()) : List.of(),
                reviews,
                handoffs,
                inspections,
                orders,
                jdbc.queryForList(
                        "SELECT h.action,h.note,h.created_at,u.display_name AS actor FROM workflow_history h JOIN users u ON u.id=h.actor_id WHERE case_id=? ORDER BY h.rowid",
                        id));
    }

    private List<Act> acts(String inspection, String user, ResidentCaseService.CaseView c) {
        boolean staff =
                engineer(c, user)
                        || access.coordinator(c.id(), c.houseId(), user)
                        || warranty(c, user);
        return jdbc.query(
                "SELECT a.*,u.display_name FROM inspection_acts a JOIN users u ON u.id=a.created_by WHERE inspection_id=? AND (state='PUBLISHED' OR ?=1) ORDER BY version DESC",
                (rs, n) ->
                        new Act(
                                rs.getString("id"),
                                rs.getInt("version"),
                                ActState.valueOf(rs.getString("state")),
                                rs.getString("inspected_at"),
                                rs.getString("participants"),
                                rs.getString("findings"),
                                rs.getString("defects"),
                                rs.getString("preliminary_cause"),
                                rs.getString("evidence_version_id"),
                                rs.getString("correction_reason"),
                                rs.getString("display_name"),
                                rs.getString("published_at")),
                inspection,
                staff ? 1 : 0);
    }

    @Transactional
    public Workspace review(String id, WorkflowRequests.Review r, String user) {
        var c = caseView(id, user);
        boolean terminal =
                r.outcome() == ReviewOutcome.WARRANTY_CONFIRMED
                        || r.outcome() == ReviewOutcome.NON_WARRANTY;
        if (!warranty(c, user) && (!access.coordinator(id, c.houseId(), user) || terminal))
            throw missing();
        if (r.warrantyId() != null) {
            var w = row("house_warranties", r.warrantyId());
            if (!c.houseId().equals(str(w, "house_id"))) throw missing();
            if (terminal && !"VERIFIED".equals(str(w, "state")))
                throw invalid("Сначала проверьте применимые гарантийные документы");
        }
        if (r.outcome() == ReviewOutcome.WARRANTY_CONFIRMED && r.warrantyId() == null)
            throw invalid("Укажите проверенные условия гарантии");
        if (r.outcome() == ReviewOutcome.NON_WARRANTY && r.refusalReason() == null)
            throw invalid("Укажите причину и обоснование позиции, доступное жителю");
        evidence(optional(r.evidenceVersionId()), id);
        if (terminal
                && optional(r.evidenceVersionId()) == null
                && !yes(
                        "SELECT count(*) FROM inspection_acts a JOIN case_inspections i ON i.id=a.inspection_id WHERE i.case_id=? AND a.state='PUBLISHED'",
                        id))
            throw invalid("Для вывода нужны материалы осмотра или документ обследования");
        jdbc.update(
                "INSERT INTO case_warranty_reviews(id,case_id,warranty_id,outcome,refusal_reason,basis,explanation,evidence_version_id,reviewed_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id(),
                id,
                optional(r.warrantyId()),
                r.outcome().name(),
                r.refusalReason() == null ? null : r.refusalReason().name(),
                r.basis(),
                r.explanation(),
                optional(r.evidenceVersionId()),
                user,
                now());
        if (r.outcome() == ReviewOutcome.POSSIBLE_WARRANTY
                || r.outcome() == ReviewOutcome.WARRANTY_CONFIRMED)
            jdbc.update("UPDATE resident_cases SET kind='WARRANTY' WHERE id=?", id);
        String note =
                "Гарантийное рассмотрение: "
                        + r.explanation()
                        + ". Основание позиции: "
                        + r.basis();
        change(
                c,
                id,
                "WARRANTY_REVIEW",
                note,
                r.revision(),
                user,
                "Сотрудник определяет следующий шаг по результатам гарантийного рассмотрения");
        return workspace(id, user);
    }

    @Transactional
    public Workspace createHandoff(String id, WorkflowRequests.Handoff r, String user) {
        var c = caseView(id, user);
        coordinate(c, user);
        String handoff = id();
        jdbc.update(
                "INSERT INTO case_handoffs(id,case_id,recipient,recipient_type,channel,basis,authority,statement,state,due_at,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,'PREPARED',?,?,?)",
                handoff,
                id,
                r.recipient(),
                r.recipientType().name(),
                r.channel(),
                r.basis(),
                r.authority(),
                r.statement(),
                r.dueAt(),
                user,
                now());
        change(
                c,
                handoff,
                "HANDOFF_PREPARED",
                "Материалы для «" + r.recipient() + "» подготовлены. Отправка ещё не подтверждена",
                r.revision(),
                user,
                "Координатор готовит передачу материалов и подтверждение полномочий");
        return workspace(id, user);
    }

    @Transactional
    public Workspace handoff(String id, WorkflowRequests.HandoffUpdate r, String user) {
        var h = row("case_handoffs", id);
        var c = entityCase(h, user);
        coordinate(c, user);
        HandoffState current = HandoffState.valueOf(str(h, "state"));
        boolean allowed =
                r.state() == HandoffState.CANCELLED
                                && !List.of(HandoffState.CANCELLED, HandoffState.RESPONDED)
                                        .contains(current)
                        || switch (current) {
                            case PREPARED -> r.state() == HandoffState.SENT;
                            case SENT -> r.state() == HandoffState.RECEIVED;
                            case RECEIVED -> r.state() == HandoffState.RESPONDED;
                            default -> false;
                        };
        if (!allowed) throw invalid("Сначала подтвердите предыдущий шаг передачи");
        evidence(optional(r.evidenceVersionId()), c.id());
        if (r.state() != HandoffState.CANCELLED && optional(r.evidenceVersionId()) == null)
            throw invalid(
                    "Приложите подтверждение отправки, получения или ответ к документам обращения");
        if (jdbc.update(
                        "UPDATE case_handoffs SET state=?,revision=revision+1 WHERE id=? AND revision=?",
                        r.state().name(),
                        id,
                        r.revision())
                != 1) throw conflict();
        jdbc.update(
                "INSERT INTO case_handoff_events(id,handoff_id,state,note,evidence_version_id,actor_id,created_at) VALUES (?,?,?,?,?,?,?)",
                id(),
                id,
                r.state().name(),
                r.note(),
                optional(r.evidenceVersionId()),
                user,
                now());
        String status =
                switch (r.state()) {
                    case SENT -> "Отправка материалов подтверждена сотрудником";
                    case RECEIVED -> "Получение материалов подтверждено сотрудником";
                    case RESPONDED -> "Получен ответ адресата";
                    case CANCELLED -> "Передача отменена";
                    default -> "Материалы подготовлены";
                };
        change(
                c,
                id,
                "HANDOFF_UPDATED",
                status + " («" + str(h, "recipient") + "»): " + r.note(),
                r.caseRevision(),
                user,
                status + ". Координатор сопровождает обращение");
        return workspace(c.id(), user);
    }

    private void inspector(ResidentCaseService.CaseView c, String target) {
        if (!access.assigned(c.id(), c.houseId(), target, CaseFunction.ENGINEER))
            throw invalid("Назначьте инженера участником этого обращения");
    }

    private String moscow(Instant value) {
        return value == null
                ? "уточняется"
                : java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                                .withZone(ZoneId.of("Europe/Moscow"))
                                .format(value)
                        + " МСК";
    }

    private void slot(Instant starts, Instant ends) {
        if (!ends.isAfter(starts)
                || Duration.between(starts, ends).toHours() > 12
                || starts.isBefore(Instant.now().minusSeconds(300)))
            throw invalid("Выберите будущий интервал осмотра длительностью до 12 часов");
    }

    @Transactional
    public Workspace planInspection(
            String caseId, String inspectionId, WorkflowRequests.Inspection r, String user) {
        var c = caseView(caseId, user);
        coordinate(c, user);
        inspector(c, r.inspectorId());
        slot(r.startsAt(), r.endsAt());
        String id = inspectionId == null ? id() : inspectionId;
        String previous =
                inspectionId == null
                        ? ""
                        : (". Прежний интервал: "
                                + moscow(
                                        Instant.parse(
                                                str(row("case_inspections", id), "starts_at")))
                                + " — "
                                + moscow(
                                        Instant.parse(
                                                str(row("case_inspections", id), "ends_at"))));
        if (inspectionId == null)
            jdbc.update(
                    "INSERT INTO case_inspections(id,case_id,inspector_id,location,participants,starts_at,ends_at,state,created_by,created_at) VALUES (?,?,?,?,?,?,?,'PROPOSED',?,?)",
                    id,
                    caseId,
                    r.inspectorId(),
                    r.location(),
                    r.participants(),
                    r.startsAt(),
                    r.endsAt(),
                    user,
                    now());
        else {
            var old = row("case_inspections", id);
            if (!caseId.equals(str(old, "case_id"))) throw missing();
            if (List.of("COMPLETED", "CANCELLED").contains(str(old, "state")))
                throw invalid("Создайте новый осмотр вместо изменения завершённого");
            if (optional(r.reason()) == null) throw invalid("Объясните перенос осмотра");
            if (jdbc.update(
                            "UPDATE case_inspections SET inspector_id=?,location=?,participants=?,starts_at=?,ends_at=?,state='PROPOSED',resident_availability='PENDING',revision=revision+1 WHERE id=? AND revision=?",
                            r.inspectorId(),
                            r.location(),
                            r.participants(),
                            r.startsAt(),
                            r.endsAt(),
                            id,
                            r.revision())
                    != 1) throw conflict();
        }
        change(
                c,
                id,
                "INSPECTION_PLANNED",
                "Предложен осмотр. Инженер: "
                        + jdbc.queryForObject(
                                "SELECT display_name FROM users WHERE id=?",
                                String.class,
                                r.inspectorId())
                        + ". Место: "
                        + r.location()
                        + ". Время: "
                        + moscow(r.startsAt())
                        + " — "
                        + moscow(r.endsAt())
                        + previous
                        + (optional(r.reason()) == null ? "" : ". Причина переноса: " + r.reason()),
                r.caseRevision(),
                user,
                "Предложен осмотр: "
                        + moscow(r.startsAt())
                        + " — "
                        + moscow(r.endsAt())
                        + ". В карточке можно подтвердить время или попросить другое");
        jdbc.update("UPDATE resident_cases SET status='WAITING_RESIDENT' WHERE id=?", caseId);
        return workspace(caseId, user);
    }

    @Transactional
    public Workspace inspectionState(String id, WorkflowRequests.InspectionUpdate r, String user) {
        var i = row("case_inspections", id);
        var c = entityCase(i, user);
        if (!access.coordinator(c.id(), c.houseId(), user)
                && !(user.equals(str(i, "inspector_id")) && engineer(c, user))) throw missing();
        if (List.of("COMPLETED", "CANCELLED").contains(str(i, "state")))
            throw invalid("Осмотр уже завершён");
        if (r.state() != InspectionState.NO_ACCESS && r.state() != InspectionState.CANCELLED)
            throw invalid(
                    "Завершение осмотра оформляется публикацией акта; время согласует житель");
        if (r.state() == InspectionState.CANCELLED
                && !access.coordinator(c.id(), c.houseId(), user)) throw missing();
        if (r.state() == InspectionState.NO_ACCESS
                && Instant.now().isBefore(Instant.parse(str(i, "starts_at"))))
            throw invalid("Нельзя заранее отметить отсутствие доступа");
        if (jdbc.update(
                        "UPDATE case_inspections SET state=?,revision=revision+1 WHERE id=? AND revision=?",
                        r.state().name(),
                        id,
                        r.revision())
                != 1) throw conflict();
        change(
                c,
                id,
                "INSPECTION_UPDATED",
                (r.state() == InspectionState.NO_ACCESS
                                ? "Осмотр не состоялся: "
                                : "Осмотр отменён: ")
                        + r.reason(),
                r.caseRevision(),
                user,
                "Координатор согласует дальнейшие действия по осмотру");
        return workspace(c.id(), user);
    }

    @Transactional
    public Workspace inspectionResponse(
            String id, WorkflowRequests.InspectionResponse r, String user) {
        var i = row("case_inspections", id);
        var c = entityCase(i, user);
        if (!c.isAuthor()) throw missing();
        String comment = Objects.toString(optional(r.comment()), "");
        if (r.action() == ResidentAction.COMMENT_ACT) {
            if (optional(r.actId()) == null
                    || !yes(
                            "SELECT count(*) FROM inspection_acts WHERE id=? AND inspection_id=? AND state='PUBLISHED'",
                            r.actId(),
                            id)
                    || comment.isBlank())
                throw invalid("Выберите опубликованный акт и напишите замечание");
        } else {
            if (!List.of("PROPOSED", "SCHEDULED").contains(str(i, "state")))
                throw invalid("Время этого осмотра уже нельзя согласовать");
            if (r.action() == ResidentAction.CONFIRM_TIME
                    && Instant.parse(str(i, "ends_at")).isBefore(Instant.now()))
                throw invalid("Время осмотра прошло. Попросите предложить другое");
        }
        Availability availability =
                r.action() == ResidentAction.CONFIRM_TIME
                        ? Availability.CONFIRMED
                        : Availability.REQUEST_OTHER_TIME;
        if (jdbc.update(
                        "UPDATE case_inspections SET revision=revision+1,resident_availability=CASE WHEN ?='COMMENT_ACT' THEN resident_availability ELSE ? END,state=CASE WHEN ?='CONFIRM_TIME' THEN 'SCHEDULED' WHEN ?='REQUEST_OTHER_TIME' THEN 'PROPOSED' ELSE state END WHERE id=? AND revision=?",
                        r.action().name(),
                        availability.name(),
                        r.action().name(),
                        r.action().name(),
                        id,
                        r.revision())
                != 1) throw conflict();
        jdbc.update(
                "INSERT INTO inspection_responses(id,inspection_id,act_id,action,comment,actor_id,created_at) VALUES (?,?,?,?,?,?,?)",
                id(),
                id,
                optional(r.actId()),
                r.action().name(),
                comment,
                user,
                now());
        String note =
                switch (r.action()) {
                    case CONFIRM_TIME -> "Житель подтвердил время осмотра";
                    case REQUEST_OTHER_TIME -> "Жителю нужно другое время осмотра";
                    case COMMENT_ACT -> "Замечание жителя к акту осмотра";
                };
        change(
                c,
                id,
                "RESIDENT_INSPECTION_RESPONSE",
                note + (comment.isBlank() ? "" : ": " + comment),
                r.caseRevision(),
                user,
                switch (r.action()) {
                    case REQUEST_OTHER_TIME -> "Координатор предложит другое время осмотра";
                    case CONFIRM_TIME ->
                            "Осмотр согласован: "
                                    + moscow(Instant.parse(str(i, "starts_at")))
                                    + ". Ожидаем инженера";
                    case COMMENT_ACT -> "Инженер рассматривает ваши замечания к акту";
                });
        return workspace(c.id(), user);
    }

    @Transactional
    public Workspace draftAct(String id, WorkflowRequests.Act r, String user) {
        var i = row("case_inspections", id);
        var c = entityCase(i, user);
        if (!user.equals(str(i, "inspector_id")) || !engineer(c, user)) throw missing();
        if ("CANCELLED".equals(str(i, "state"))) throw invalid("Осмотр отменён");
        if (r.inspectedAt().isAfter(Instant.now().plusSeconds(60)))
            throw invalid("Укажите фактическое время проведённого осмотра");
        if (yes("SELECT count(*) FROM inspection_acts WHERE inspection_id=? AND state='DRAFT'", id))
            throw invalid("Сначала опубликуйте существующий проект акта");
        int version =
                jdbc.queryForObject(
                        "SELECT coalesce(max(version),0)+1 FROM inspection_acts WHERE inspection_id=?",
                        Integer.class,
                        id);
        if (version > 1 && optional(r.correctionReason()) == null)
            throw invalid("Объясните причину новой версии акта");
        evidence(optional(r.evidenceVersionId()), c.id());
        if (jdbc.update(
                        "UPDATE case_inspections SET revision=revision+1 WHERE id=? AND revision=?",
                        id,
                        r.revision())
                != 1) throw conflict();
        jdbc.update(
                "INSERT INTO inspection_acts(id,inspection_id,version,state,inspected_at,participants,findings,defects,preliminary_cause,evidence_version_id,correction_reason,created_by,created_at) VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?)",
                id(),
                id,
                version,
                r.inspectedAt(),
                r.participants(),
                r.findings(),
                r.defects(),
                optional(r.preliminaryCause()),
                optional(r.evidenceVersionId()),
                optional(r.correctionReason()),
                user,
                now());
        change(
                c,
                id,
                "ACT_DRAFTED",
                "Инженер готовит акт осмотра. Проект ещё не опубликован",
                r.caseRevision(),
                user,
                null);
        return workspace(c.id(), user);
    }

    @Transactional
    public Workspace editAct(String actId, WorkflowRequests.Act r, String user) {
        var a = row("inspection_acts", actId);
        var i = row("case_inspections", str(a, "inspection_id"));
        var c = entityCase(i, user);
        if (!user.equals(str(i, "inspector_id")) || !engineer(c, user)) throw missing();
        if (!"DRAFT".equals(str(a, "state")))
            throw invalid(
                    "Опубликованный акт не редактируется. Создайте новую версию с причиной исправления");
        if (r.inspectedAt().isAfter(Instant.now().plusSeconds(60)))
            throw invalid("Укажите фактическое время осмотра");
        evidence(optional(r.evidenceVersionId()), c.id());
        if (jdbc.update(
                        "UPDATE case_inspections SET revision=revision+1 WHERE id=? AND revision=? AND state!='CANCELLED'",
                        i.get("id"),
                        r.revision())
                != 1) throw conflict();
        jdbc.update(
                "UPDATE inspection_acts SET inspected_at=?,participants=?,findings=?,defects=?,preliminary_cause=?,evidence_version_id=?,correction_reason=? WHERE id=?",
                r.inspectedAt(),
                r.participants(),
                r.findings(),
                r.defects(),
                optional(r.preliminaryCause()),
                optional(r.evidenceVersionId()),
                optional(r.correctionReason()),
                actId);
        change(
                c,
                actId,
                "ACT_DRAFT_UPDATED",
                "Уточнён проект акта осмотра",
                r.caseRevision(),
                user,
                null);
        return workspace(c.id(), user);
    }

    @Transactional
    public Workspace publishAct(String actId, WorkflowRequests.Revision r, String user) {
        var a = row("inspection_acts", actId);
        var i = row("case_inspections", str(a, "inspection_id"));
        var c = entityCase(i, user);
        if (!user.equals(str(i, "inspector_id")) || !engineer(c, user)) throw missing();
        if (!"DRAFT".equals(str(a, "state")))
            throw invalid("Акт уже опубликован. Исправления оформляются новой версией");
        if (jdbc.update(
                        "UPDATE case_inspections SET state='COMPLETED',revision=revision+1 WHERE id=? AND revision=? AND state!='CANCELLED'",
                        i.get("id"),
                        r.revision())
                != 1) throw conflict();
        jdbc.update(
                "UPDATE inspection_acts SET state='PUBLISHED',published_at=? WHERE id=?",
                now(),
                actId);
        change(
                c,
                actId,
                "ACT_PUBLISHED",
                "Опубликован акт осмотра, версия "
                        + num(a, "version")
                        + ". Можно ознакомиться и оставить замечания; публикация не означает подписание",
                r.caseRevision(),
                user,
                "Осмотр проведён. Сотрудник определяет необходимые работы и дальнейший порядок");
        return workspace(c.id(), user);
    }

    private void actReference(String id, String caseId) {
        if (id != null
                && !yes(
                        "SELECT count(*) FROM inspection_acts a JOIN case_inspections i ON i.id=a.inspection_id WHERE a.id=? AND i.case_id=? AND a.state='PUBLISHED'",
                        id,
                        caseId)) throw invalid("Нужен опубликованный акт этого обращения");
    }

    private void executor(ResidentCaseService.CaseView c, String user) {
        if (!access.assigned(
                c.id(), c.houseId(), user, CaseFunction.CONTRACTOR, CaseFunction.ENGINEER))
            throw invalid("Назначьте исполнителя или инженера участником этого обращения");
    }

    @Transactional
    public Workspace createWork(String id, WorkflowRequests.Work r, String user) {
        var c = caseView(id, user);
        coordinate(c, user);
        executor(c, r.assigneeId());
        actReference(optional(r.actId()), id);
        if (r.dueAt() != null && r.dueAt().isBefore(Instant.now()))
            throw invalid("При назначении выберите будущий срок или оставьте его неизвестным");
        String order = id();
        jdbc.update(
                "INSERT INTO case_work_orders(id,case_id,title,scope,assignee_id,basis_type,basis,due_at,due_basis,act_id,state,created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,'ASSIGNED',?,?,?)",
                order,
                id,
                r.title(),
                r.scope(),
                r.assigneeId(),
                r.basisType().name(),
                r.basis(),
                r.dueAt(),
                r.dueBasis(),
                optional(r.actId()),
                user,
                now(),
                now());
        change(
                c,
                order,
                "WORK_ASSIGNED",
                "Назначены работы: " + r.title() + ". Объём: " + r.scope(),
                r.revision(),
                user,
                "Назначены работы: "
                        + r.title()
                        + ". Срок: "
                        + moscow(r.dueAt())
                        + ". Ход работ доступен в карточке");
        return workspace(id, user);
    }

    @Transactional
    public Workspace updateWork(String id, WorkflowRequests.WorkUpdate r, String user) {
        var o = row("case_work_orders", id);
        var c = entityCase(o, user);
        boolean coord = access.coordinator(c.id(), c.houseId(), user),
                self = user.equals(str(o, "assignee_id"));
        WorkState state = WorkState.valueOf(str(o, "state"));
        if (state == WorkState.ACCEPTED || state == WorkState.CANCELLED)
            throw invalid("Задание завершено. При необходимости создайте новое");
        boolean progress =
                self
                        && (access.assigned(
                                c.id(),
                                c.houseId(),
                                user,
                                CaseFunction.CONTRACTOR,
                                CaseFunction.ENGINEER))
                        && switch (state) {
                            case ASSIGNED, NEED_REWORK ->
                                    r.state() == WorkState.IN_PROGRESS
                                            || r.state() == WorkState.BLOCKED;
                            case IN_PROGRESS, BLOCKED ->
                                    r.state() == WorkState.REPORTED
                                            || r.state() == WorkState.IN_PROGRESS
                                            || r.state() == WorkState.BLOCKED;
                            default -> false;
                        };
        boolean check =
                coord
                        && (r.state() == WorkState.ASSIGNED
                                || r.state() == WorkState.CANCELLED
                                || state == WorkState.REPORTED
                                        && (r.state() == WorkState.NEED_REWORK
                                                || r.state() == WorkState.ACCEPTED)
                                || r.state() == state);
        if (!progress && !check) throw missing();
        if (r.state() == WorkState.ACCEPTED && self)
            throw invalid("Исполнитель не может принять собственную работу");
        String assignee = optional(r.assigneeId()) == null ? str(o, "assignee_id") : r.assigneeId();
        if (!coord
                && (!assignee.equals(str(o, "assignee_id"))
                        || !Objects.equals(
                                r.dueAt() == null ? null : r.dueAt().toString(), str(o, "due_at"))
                        || !Objects.equals(r.dueBasis(), str(o, "due_basis"))))
            throw invalid("Исполнитель не изменяет назначение и срок");
        if (!assignee.equals(str(o, "assignee_id")) && r.state() != WorkState.ASSIGNED)
            throw invalid(
                    "При смене исполнителя верните задание в состояние «Назначено», чтобы прежний отчёт не подменил новую работу");
        if (coord) {
            executor(c, assignee);
            if (optional(r.dueBasis()) == null)
                throw invalid("Укажите основание срока или причину его отсутствия");
        }
        evidence(optional(r.evidenceVersionId()), c.id());
        boolean reporting = progress && r.state() == WorkState.REPORTED;
        if (reporting) {
            int version =
                    jdbc.queryForObject(
                            "SELECT coalesce(max(version),0)+1 FROM work_order_reports WHERE work_order_id=?",
                            Integer.class,
                            id);
            jdbc.update(
                    "INSERT INTO work_order_reports(id,work_order_id,version,report,evidence_version_id,created_by,created_at) VALUES (?,?,?,?,?,?,?)",
                    id(),
                    id,
                    version,
                    r.note(),
                    optional(r.evidenceVersionId()),
                    user,
                    now());
        }
        if (jdbc.update(
                        "UPDATE case_work_orders SET state=?,assignee_id=?,due_at=?,due_basis=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                        r.state().name(),
                        assignee,
                        r.dueAt(),
                        r.dueBasis(),
                        now(),
                        id,
                        r.revision())
                != 1) throw conflict();
        String note =
                !reporting && r.state() == state
                        ? "Условия задания обновлены"
                        : switch (r.state()) {
                            case REPORTED -> "Исполнитель представил отчёт; ожидается проверка";
                            case ACCEPTED ->
                                    "Координатор проверил работы; подтверждение жителя остаётся отдельным";
                            case NEED_REWORK -> "Работы возвращены на доработку";
                            case CANCELLED -> "Задание отменено";
                            case BLOCKED -> "Есть препятствие для выполнения работ";
                            case IN_PROGRESS -> "Исполнитель выполняет работы";
                            default -> "Условия задания обновлены";
                        };
        String changes = "";
        if (!Objects.equals(str(o, "due_at"), r.dueAt() == null ? null : r.dueAt().toString()))
            changes +=
                    ". Срок: "
                            + (str(o, "due_at") == null
                                    ? "не установлен"
                                    : moscow(Instant.parse(str(o, "due_at"))))
                            + " → "
                            + moscow(r.dueAt())
                            + ". Основание: "
                            + r.dueBasis();
        if (!assignee.equals(str(o, "assignee_id")))
            changes +=
                    ". Новый исполнитель: "
                            + jdbc.queryForObject(
                                    "SELECT display_name FROM users WHERE id=?",
                                    String.class,
                                    assignee);
        change(
                c,
                id,
                "WORK_UPDATED",
                note + ": " + r.note() + changes,
                r.caseRevision(),
                user,
                note);
        return workspace(c.id(), user);
    }

    public String printAct(String actId, String user) {
        var a = row("inspection_acts", actId);
        var i = row("case_inspections", str(a, "inspection_id"));
        var c = entityCase(i, user);
        if (!"PUBLISHED".equals(str(a, "state"))) throw missing();
        return html(
                "Акт осмотра · версия " + num(a, "version"),
                c,
                "Дата осмотра",
                moscow(Instant.parse(str(a, "inspected_at"))),
                "Место",
                str(i, "location"),
                "Участники",
                str(a, "participants"),
                "Наблюдения",
                str(a, "findings"),
                "Дефекты",
                str(a, "defects"),
                "Предварительная причина",
                str(a, "preliminary_cause"),
                "Основание исправления",
                str(a, "correction_reason"),
                "Статус",
                "Опубликованная версия. Электронная подпись не применялась; подписание и согласие участников этим файлом не подтверждаются.");
    }

    public String printHandoff(String id, String user) {
        var h = row("case_handoffs", id);
        var c = entityCase(h, user);
        coordinate(c, user);
        return html(
                "Материалы обращения для передачи",
                c,
                "Адресат",
                str(h, "recipient"),
                "Канал",
                str(h, "channel"),
                "Содержание",
                str(h, "statement"),
                "Основание",
                str(h, "basis"),
                "Полномочия представителя",
                str(h, "authority"),
                "Статус",
                "Подготовленный документ. Скачивание не означает отправку, подписание или получение адресатом.");
    }

    private String html(String title, ResidentCaseService.CaseView c, String... fields) {
        StringBuilder out =
                new StringBuilder(
                                "<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>")
                        .append(escape(title))
                        .append(
                                "</title><style>body{font:16px/1.6 sans-serif;max-width:850px;margin:40px auto;padding:20px}h1{font-size:25px}p{white-space:pre-wrap}dt{font-weight:bold;margin-top:18px}@media print{body{margin:0}}</style><h1>")
                        .append(escape(title))
                        .append("</h1><p>Обращение №")
                        .append(c.number())
                        .append("<br>")
                        .append(escape(c.address()))
                        .append("</p><dl>");
        for (int n = 0; n < fields.length; n += 2)
            out.append("<dt>")
                    .append(escape(fields[n]))
                    .append("</dt><dd><p>")
                    .append(escape(Objects.toString(fields[n + 1], "Не установлено")))
                    .append("</p></dd>");
        return out.append("</dl><p>Пульс дома · Документ для ознакомления и печати</p></html>")
                .toString();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
