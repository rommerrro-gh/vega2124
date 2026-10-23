package ru.pulsedoma.issues;

import static ru.pulsedoma.issues.CaseExchangeService.*;
import static ru.pulsedoma.issues.ExchangeTypes.*;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseControlService {
    private final JdbcTemplate db;
    private final ResidentCaseService cases;
    private final CaseWorkAccess access;
    private final CaseExchangeService exchange;

    public CaseControlService(
            JdbcTemplate db,
            ResidentCaseService cases,
            CaseWorkAccess access,
            CaseExchangeService exchange) {
        this.db = db;
        this.cases = cases;
        this.access = access;
        this.exchange = exchange;
    }

    private void house(String house, String user) {
        if (!access.manager(house, user)
                && !access.dispatcher(house, user)
                && !access.staffFunction(house, user, CaseFunction.COORDINATOR)
                && !access.billing(house, user)) throw missing();
    }

    private Map<String, Object> raw(String key) {
        return exchange.row("resident_cases", key);
    }

    private boolean financial(Map<String, Object> c) {
        return "PAYMENT".equals(s(c, "kind"));
    }

    private boolean eligible(Map<String, Object> c, String user) {
        if (user == null || user.isBlank() || user.equals(s(c, "author_id"))) return false;
        String h = s(c, "house_id");
        return financial(c)
                ? access.billing(h, user)
                : access.dispatcher(h, user)
                        || access.manager(h, user)
                        || access.staffFunction(h, user, CaseFunction.COORDINATOR);
    }

    private Map<String, Object> editable(String key, String user, int revision) {
        var c = raw(key);
        String h = s(c, "house_id");
        if (!access.manager(h, user)
                && !(financial(c) ? access.billing(h, user) : access.coordinator(key, h, user)))
            throw missing();
        check(c, revision);
        return c;
    }

    private void check(Map<String, Object> c, int revision) {
        if (rev(c) != revision) throw conflict();
        if ("CLOSED".equals(s(c, "status")))
            throw invalid("Закрытое обращение доступно для просмотра");
    }

    private void audit(Map<String, Object> c, Action action, String note, String user) {
        db.update(
                "INSERT INTO control_history(id,house_id,case_id,action,note,snapshot_json,actor_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
                id(),
                c.get("house_id"),
                c.get("id"),
                action.name(),
                note,
                exchange.json(
                        Map.of(
                                "owner",
                                Objects.toString(raw(s(c, "id")).get("responsible_user_id"), ""),
                                "followup",
                                db.queryForList(
                                        "SELECT * FROM case_followup WHERE case_id=?",
                                        c.get("id")))),
                user,
                now());
    }

    public record HouseSummary(
            String id, String address, int active, int attention, int overdue, int unassigned) {}

    public List<HouseSummary> overview(String user) {
        var result = new ArrayList<HouseSummary>();
        for (var h : db.queryForList("SELECT id,address FROM houses ORDER BY address")) {
            String key = s(h, "id");
            if (!access.manager(key, user)
                    && !access.dispatcher(key, user)
                    && !access.staffFunction(key, user, CaseFunction.COORDINATOR)
                    && !access.billing(key, user)) continue;
            var items = dashboard(key, user).items();
            result.add(
                    new HouseSummary(
                            key,
                            s(h, "address"),
                            (int)
                                    items.stream()
                                            .filter(i -> i.item().status() != CaseStatus.CLOSED)
                                            .count(),
                            (int) items.stream().filter(i -> !i.alerts().isEmpty()).count(),
                            (int)
                                    items.stream()
                                            .filter(
                                                    i ->
                                                            i.alerts().contains(Alert.OVERDUE)
                                                                    || i.alerts()
                                                                            .contains(
                                                                                    Alert
                                                                                            .WORK_OVERDUE))
                                            .count(),
                            (int)
                                    items.stream()
                                            .filter(i -> i.alerts().contains(Alert.UNASSIGNED))
                                            .count()));
        }
        return result;
    }

    public record Item(
            ResidentCaseService.CaseView item,
            String deputyId,
            String deputyName,
            List<Alert> alerts) {}

    public record Dashboard(
            boolean canManage,
            List<ResidentCaseService.Staff> candidates,
            List<Item> items,
            List<Map<String, Object>> escalations,
            List<Map<String, Object>> history) {}

    public Dashboard dashboard(String house, String user) {
        house(house, user);
        var items = new ArrayList<Item>();
        for (var c : cases.queue(house, user)) {
            var source = raw(c.id());
            var f =
                    db.queryForList(
                            "SELECT f.*,u.display_name FROM case_followup f LEFT JOIN users u ON u.id=f.deputy_id JOIN organization_houses h ON h.house_id=? AND h.organization_id=f.source_organization_id AND h.status='ACTIVE' WHERE f.case_id=?",
                            house,
                            c.id());
            String deputy = f.isEmpty() ? null : s(f.get(0), "deputy_id");
            var alerts = new ArrayList<Alert>();
            if (c.status() != CaseStatus.CLOSED) {
                if (c.responsibleUserId() == null) alerts.add(Alert.UNASSIGNED);
                else if (!eligible(source, c.responsibleUserId()))
                    alerts.add(Alert.OWNER_UNAVAILABLE);
                if (!eligible(source, deputy)) alerts.add(Alert.NO_DEPUTY);
                if (c.fullAccess()) {
                    if (c.dueAt() != null && Instant.parse(c.dueAt()).isBefore(Instant.now()))
                        alerts.add(Alert.OVERDUE);
                    if (c.status() == CaseStatus.WAITING_RESIDENT)
                        alerts.add(Alert.WAITING_RESIDENT);
                    if (c.overdueWorkCount() > 0) alerts.add(Alert.WORK_OVERDUE);
                    if (exists(c.id(), "state='SENT'")) alerts.add(Alert.WAITING_RECEIPT);
                    if (exists(c.id(), "state='RECEIVED'")) alerts.add(Alert.WAITING_ANSWER);
                    if (exists(c.id(), "state='ANSWERED'")) alerts.add(Alert.NEEDS_REVIEW);
                    if (exists(
                                    c.id(),
                                    "state NOT IN ('REVIEWED','CANCELLED') AND due_at IS NOT NULL AND datetime(due_at)<datetime('now')")
                            || access.exists(
                                    "SELECT count(*) FROM case_handoffs WHERE case_id=? AND state NOT IN ('RESPONDED','CANCELLED') AND due_at IS NOT NULL AND datetime(due_at)<datetime('now')",
                                    c.id())
                            || access.exists(
                                    "SELECT count(*) FROM billing_corrections WHERE case_id=? AND state NOT IN ('VERIFIED','REFUSAL_REVIEWED','CANCELLED') AND due_at IS NOT NULL AND datetime(due_at)<datetime('now')",
                                    c.id()))
                        if (!alerts.contains(Alert.OVERDUE)) alerts.add(Alert.OVERDUE);
                }
                if (access.exists(
                        "SELECT count(*) FROM case_escalations WHERE case_id=? AND state<>'CLOSED'",
                        c.id())) alerts.add(Alert.ESCALATED);
            }
            items.add(
                    new Item(c, deputy, f.isEmpty() ? null : s(f.get(0), "display_name"), alerts));
        }
        var escalations =
                db
                        .queryForList(
                                "SELECT e.*,c.house_id FROM case_escalations e JOIN resident_cases c ON c.id=e.case_id WHERE c.house_id=? ORDER BY e.rowid DESC",
                                house)
                        .stream()
                        .filter(
                                e ->
                                        items.stream()
                                                        .anyMatch(
                                                                i ->
                                                                        i.item()
                                                                                        .id()
                                                                                        .equals(
                                                                                                e
                                                                                                        .get(
                                                                                                                "case_id"))
                                                                                && i.item()
                                                                                        .fullAccess())
                                                || user.equals(e.get("target_id")))
                        .map(
                                e -> {
                                    if (items.stream()
                                            .noneMatch(
                                                    i ->
                                                            i.item().id().equals(e.get("case_id"))
                                                                    && i.item().fullAccess())) {
                                        var safe = new LinkedHashMap<String, Object>();
                                        for (String k :
                                                List.of(
                                                        "id",
                                                        "case_id",
                                                        "state",
                                                        "target_id",
                                                        "created_at")) safe.put(k, e.get(k));
                                        return (Map<String, Object>) safe;
                                    }
                                    return e;
                                })
                        .toList();
        // History can contain assignment reasons and personal data; only expose for full-access
        // cases.
        var history =
                db
                        .queryForList(
                                "SELECT action,note,case_id,created_at FROM control_history WHERE house_id=? AND case_id IS NOT NULL ORDER BY rowid DESC LIMIT 200",
                                house)
                        .stream()
                        .filter(
                                e ->
                                        items.stream()
                                                .anyMatch(
                                                        i ->
                                                                i.item()
                                                                                .id()
                                                                                .equals(
                                                                                        e.get(
                                                                                                "case_id"))
                                                                        && i.item().fullAccess()))
                        .toList();
        return new Dashboard(
                access.manager(house, user), access.candidates(house), items, escalations, history);
    }

    private boolean exists(String c, String condition) {
        return access.exists(
                "SELECT count(*) FROM exchange_packets WHERE case_id=? AND " + condition, c);
    }

    private void grants(Map<String, Object> c, String owner, String deputy, String user) {
        if (financial(c)) return;
        String key = s(c, "id"), h = s(c, "house_id"), org = exchange.organization(h);
        var desired = new HashSet<String>();
        desired.add(owner);
        if (deputy != null) desired.add(deputy);
        for (var old :
                db.queryForList("SELECT user_id FROM case_control_grants WHERE case_id=?", key)) {
            String target = s(old, "user_id");
            if (!desired.contains(target)) {
                db.update(
                        "DELETE FROM case_collaborators WHERE case_id=? AND user_id=? AND function='COORDINATOR'",
                        key,
                        target);
                db.update(
                        "DELETE FROM case_control_grants WHERE case_id=? AND user_id=?",
                        key,
                        target);
            }
        }
        for (String target : desired)
            if (!access.dispatcher(h, target)
                    && !access.exists(
                            "SELECT count(*) FROM case_collaborators WHERE case_id=? AND user_id=? AND function='COORDINATOR'",
                            key,
                            target)) {
                db.update(
                        "INSERT INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'COORDINATOR',?,?,?)",
                        key,
                        target,
                        user,
                        now(),
                        org);
                db.update(
                        "INSERT OR IGNORE INTO case_control_grants(case_id,user_id) VALUES (?,?)",
                        key,
                        target);
            }
    }

    private void saveAssignment(
            Map<String, Object> c,
            String owner,
            String deputy,
            String user,
            Action action,
            String reason) {
        String key = s(c, "id"), h = s(c, "house_id");
        grants(c, owner, deputy, user);
        if (db.update(
                        "UPDATE resident_cases SET responsible_user_id=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                        owner,
                        now(),
                        key,
                        rev(c))
                != 1) throw conflict();
        db.update(
                "INSERT INTO case_followup(case_id,deputy_id,source_organization_id,updated_by,updated_at) VALUES (?,?,?,?,?) ON CONFLICT(case_id) DO UPDATE SET deputy_id=excluded.deputy_id,source_organization_id=excluded.source_organization_id,updated_by=excluded.updated_by,updated_at=excluded.updated_at",
                key,
                deputy,
                exchange.organization(h),
                user,
                now());
        audit(c, action, reason, user);
    }

    @Transactional
    public Dashboard assign(String key, ExchangeRequests.Assignment r, String user) {
        var c = editable(key, user, r.revision());
        String deputy = r.deputyId() == null || r.deputyId().isBlank() ? null : r.deputyId();
        if (!eligible(c, r.ownerId()) || deputy != null && !eligible(c, deputy))
            throw invalid(
                    "Выберите действующего сотрудника с нужной функцией. Для финансового обращения требуется отдельный допуск");
        if (r.ownerId().equals(deputy))
            throw invalid("Ответственный и заместитель должны быть разными людьми");
        saveAssignment(c, r.ownerId(), deputy, user, Action.ASSIGNMENT_CHANGED, r.reason());
        return dashboard(s(c, "house_id"), user);
    }

    @Transactional
    public Dashboard takeover(String key, ExchangeRequests.ControlDecision r, String user) {
        var c = raw(key);
        check(c, r.revision());
        if (!eligible(c, user)
                || !access.exists(
                        "SELECT count(*) FROM case_followup f JOIN organization_houses h ON h.organization_id=f.source_organization_id AND h.house_id=? AND h.status='ACTIVE' WHERE f.case_id=? AND f.deputy_id=?",
                        c.get("house_id"),
                        key,
                        user)) throw missing();
        String old = s(c, "responsible_user_id");
        saveAssignment(
                c,
                user,
                eligible(c, old) && !user.equals(old) ? old : null,
                user,
                Action.DEPUTY_TOOK_OVER,
                r.note());
        return dashboard(s(c, "house_id"), user);
    }

    @Transactional
    public Dashboard escalate(String key, ExchangeRequests.Escalate r, String user) {
        var c = editable(key, user, r.revision());
        if (!access.manager(s(c, "house_id"), r.targetId()))
            throw invalid("Адресат эскалации должен быть действующим руководителем УК этого дома");
        db.update(
                "INSERT INTO case_escalations(id,case_id,target_id,state,reason,created_by,created_at,updated_at) VALUES (?,?,?,'OPEN',?,?,?,?)",
                id(),
                key,
                r.targetId(),
                r.reason(),
                user,
                now(),
                now());
        db.update(
                "UPDATE resident_cases SET revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                now(),
                key,
                r.revision());
        audit(c, Action.ESCALATED, r.reason(), user);
        return dashboard(s(c, "house_id"), user);
    }

    @Transactional
    public Dashboard escalation(String key, ExchangeRequests.Escalation r, String user) {
        var e = exchange.row("case_escalations", key);
        var c = raw(s(e, "case_id"));
        check(c, r.revision());
        if (!user.equals(s(e, "target_id")) || !access.manager(s(c, "house_id"), user))
            throw missing();
        if (!("OPEN".equals(s(e, "state")) && r.state() == EscalationState.ACKNOWLEDGED
                || "ACKNOWLEDGED".equals(s(e, "state")) && r.state() == EscalationState.CLOSED))
            throw invalid("Сначала примите эскалацию, затем зафиксируйте решение");
        db.update(
                "UPDATE case_escalations SET state=?,resolution=?,updated_at=? WHERE id=?",
                r.state().name(),
                r.note(),
                now(),
                key);
        db.update(
                "UPDATE resident_cases SET revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                now(),
                c.get("id"),
                r.revision());
        audit(c, Action.ESCALATION_UPDATED, r.note(), user);
        return dashboard(s(c, "house_id"), user);
    }

    public record Readiness(Map<String, Object> plan, Map<String, Boolean> checks, boolean ready) {}

    public Readiness pilot(String house, String user) {
        if (!access.manager(house, user)) throw missing();
        var checks = new LinkedHashMap<String, Boolean>();
        for (var p :
                List.of(
                        Participant.UK,
                        Participant.WARRANTY,
                        Participant.CONTRACTOR,
                        Participant.BILLING))
            checks.put(
                    "CHANNEL_" + p,
                    access.exists(
                            "SELECT count(*) FROM exchange_channels e JOIN organization_houses h ON h.organization_id=e.source_organization_id AND h.house_id=e.house_id WHERE e.house_id=? AND e.participant_type=? AND e.state='VERIFIED' AND h.status='ACTIVE'",
                            house,
                            p.name()));
        var staff = access.candidates(house);
        checks.put(
                "TWO_COORDINATORS",
                staff.stream()
                                .filter(
                                        u ->
                                                access.dispatcher(house, u.id())
                                                        || access.manager(house, u.id())
                                                        || access.staffFunction(
                                                                house,
                                                                u.id(),
                                                                CaseFunction.COORDINATOR))
                                .count()
                        >= 2);
        checks.put(
                "TWO_BILLING_STAFF",
                staff.stream().filter(ResidentCaseService.Staff::billingAccess).count() >= 2);
        var plans = db.queryForList("SELECT * FROM pilot_plans WHERE house_id=?", house);
        Map<String, Object> plan = plans.isEmpty() ? Map.of() : plans.get(0);
        checks.put("RUNBOOK_RECORDED", !plan.isEmpty());
        return new Readiness(
                plan, checks, checks.values().stream().allMatch(Boolean::booleanValue));
    }

    @Transactional
    public Readiness pilot(String house, ExchangeRequests.Pilot r, String user) {
        if (!access.manager(house, user)) throw missing();
        var old = db.queryForList("SELECT revision FROM pilot_plans WHERE house_id=?", house);
        if ((old.isEmpty() ? 0 : rev(old.get(0))) != r.revision()) throw conflict();
        if (r.state() == PilotState.READY_FOR_ACCEPTANCE
                && pilot(house, user).checks().entrySet().stream()
                        .anyMatch(e -> !e.getKey().equals("RUNBOOK_RECORDED") && !e.getValue()))
            throw invalid("Сначала проверьте каналы всех участников и состав ответственных");
        db.update(
                "INSERT INTO pilot_plans(house_id,state,scope,acceptance_reference,restore_reference,contact_reference,training_reference,rollback_plan,revision,updated_by,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(house_id) DO UPDATE SET state=excluded.state,scope=excluded.scope,acceptance_reference=excluded.acceptance_reference,restore_reference=excluded.restore_reference,contact_reference=excluded.contact_reference,training_reference=excluded.training_reference,rollback_plan=excluded.rollback_plan,revision=pilot_plans.revision+1,updated_by=excluded.updated_by,updated_at=excluded.updated_at",
                house,
                r.state().name(),
                r.scope(),
                r.acceptanceReference(),
                r.restoreReference(),
                r.contactReference(),
                r.trainingReference(),
                r.rollbackPlan(),
                1,
                user,
                now());
        db.update(
                "INSERT INTO control_history(id,house_id,action,note,snapshot_json,actor_id,created_at) VALUES (?,?,'PILOT_SAVED',?,?,?,?)",
                id(),
                house,
                "Обновлена подготовка пилота",
                exchange.json(r),
                user,
                now());
        return pilot(house, user);
    }
}
