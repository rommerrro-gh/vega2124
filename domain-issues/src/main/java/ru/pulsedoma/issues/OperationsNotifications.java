package ru.pulsedoma.issues;

import static ru.pulsedoma.issues.CaseExchangeService.*;
import static ru.pulsedoma.issues.OperationsTypes.*;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsNotifications {
    private final JdbcTemplate db;
    private final CaseWorkAccess access;

    public OperationsNotifications(JdbcTemplate db, CaseWorkAccess access) {
        this.db = db;
        this.access = access;
    }

    public void enqueue(
            String user,
            String house,
            String caseId,
            NotificationKind kind,
            String entity,
            int revision,
            String dedupe,
            String body,
            String org,
            DeadlineKind deadline,
            String due) {
        db.update(
                "INSERT OR IGNORE INTO operations_notifications(id,user_id,house_id,case_id,kind,entity_id,entity_revision,dedupe_key,body,source_organization_id,deadline_kind,due_at,created_at,next_attempt_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id(),
                user,
                house,
                caseId,
                kind.name(),
                entity,
                revision,
                dedupe,
                body,
                org,
                deadline == null ? null : deadline.name(),
                due,
                now(),
                now());
    }

    public void broadcast(
            String house,
            NotificationKind kind,
            String entity,
            int revision,
            String body,
            String org) {
        String column =
                switch (kind) {
                    case NOTICE -> "notices";
                    case PAYMENT -> "payments";
                    case MASS -> "mass_problems";
                    default -> throw invalid("Нет подписки такого вида");
                };
        for (var u :
                db.queryForList(
                        "SELECT s.user_id FROM house_update_subscriptions s WHERE s.house_id=? AND s."
                                + column
                                + "=1",
                        house))
            if (resident(house, s(u, "user_id")))
                enqueue(
                        s(u, "user_id"),
                        house,
                        null,
                        kind,
                        entity,
                        revision,
                        kind + ":" + entity + ":" + revision,
                        body,
                        org,
                        null,
                        null);
    }

    public boolean resident(String house, String user) {
        return access.member(house, user)
                && access.exists(
                        "SELECT count(*) FROM house_memberships WHERE house_id=? AND user_id=? AND role='RESIDENT' AND verification_status='VERIFIED' AND access_status='ACTIVE'",
                        house,
                        user);
    }

    private boolean readableCase(String key, String house, String user) {
        if (access.exists(
                        "SELECT count(*) FROM resident_cases WHERE id=? AND author_id=?", key, user)
                && resident(house, user)) return true;
        if (access.exists("SELECT count(*) FROM resident_cases WHERE id=? AND kind='PAYMENT'", key))
            return access.billing(house, user);
        return access.dispatcher(house, user)
                || access.assigned(key, house, user, CaseFunction.values());
    }

    public boolean deadlineOpen(DeadlineKind kind, String entity, String due) {
        String sql =
                switch (kind) {
                    case CASE_STEP ->
                            "SELECT count(*) FROM resident_cases WHERE id=? AND due_at=? AND status NOT IN ('CLOSED','WAITING_RESIDENT','RESULT_PROPOSED')";
                    case WORK_ORDER ->
                            "SELECT count(*) FROM case_work_orders WHERE id=? AND due_at=? AND state NOT IN ('ACCEPTED','CANCELLED')";
                    case EXCHANGE_PACKET ->
                            "SELECT count(*) FROM exchange_packets WHERE id=? AND due_at=? AND state NOT IN ('REVIEWED','CANCELLED')";
                    case LEGACY_HANDOFF ->
                            "SELECT count(*) FROM case_handoffs WHERE id=? AND due_at=? AND state NOT IN ('RESPONDED','CANCELLED')";
                    case BILLING_CORRECTION ->
                            "SELECT count(*) FROM billing_corrections WHERE id=? AND due_at=? AND state NOT IN ('VERIFIED','REFUSAL_REVIEWED','CANCELLED')";
                    case SHARED_WORK ->
                            "SELECT count(*) FROM mass_work_orders w JOIN house_mass_problems p ON p.id=w.problem_id WHERE w.id=? AND w.due_at=? AND w.state NOT IN ('ACCEPTED','CANCELLED') AND p.state='ACTIVE'";
                    case VISIT ->
                            "SELECT count(*) FROM case_visits v JOIN visit_slots s ON s.id=v.selected_slot_id WHERE v.id=? AND s.starts_at=? AND v.state='CONFIRMED'";
                };
        return access.exists(sql, entity, due);
    }

    public boolean allowed(Map<String, Object> n) {
        String house = s(n, "house_id"), user = s(n, "user_id"), entity = s(n, "entity_id");
        if (!access.exists("SELECT count(*) FROM users WHERE id=? AND status='ACTIVE'", user)
                || !access.exists(
                        "SELECT count(*) FROM organization_houses WHERE house_id=? AND organization_id=? AND status='ACTIVE'",
                        house,
                        n.get("source_organization_id"))) return false;
        NotificationKind kind = NotificationKind.valueOf(s(n, "kind"));
        if (kind == NotificationKind.NOTICE
                || kind == NotificationKind.PAYMENT
                || kind == NotificationKind.MASS) {
            String col =
                    kind == NotificationKind.NOTICE
                            ? "notices"
                            : kind == NotificationKind.PAYMENT ? "payments" : "mass_problems";
            if (!resident(house, user)
                    || !access.exists(
                            "SELECT count(*) FROM house_update_subscriptions WHERE house_id=? AND user_id=? AND "
                                    + col
                                    + "=1",
                            house,
                            user)) return false;
            String table =
                    kind == NotificationKind.NOTICE
                            ? "house_operations_notices"
                            : kind == NotificationKind.MASS
                                    ? "house_mass_problems"
                                    : "house_tariffs";
            return access.exists(
                    "SELECT count(*) FROM "
                            + table
                            + " WHERE id=? AND revision=? AND state<>'DRAFT'",
                    entity,
                    n.get("entity_revision"));
        }
        if (!access.exists(
                "SELECT count(*) FROM house_reminder_policies WHERE house_id=? AND source_organization_id=? AND enabled=1",
                house,
                n.get("source_organization_id"))) return false;
        if ("SHARED_WORK".equals(s(n, "deadline_kind")))
            return deadlineOpen(DeadlineKind.SHARED_WORK, entity, s(n, "due_at"))
                    && (access.manager(house, user)
                            || access.exists(
                                            "SELECT count(*) FROM mass_work_orders WHERE id=? AND worker_id=?",
                                            entity,
                                            user)
                                    && (access.dispatcher(house, user)
                                            || access.staff(house, user)));
        String caseId = s(n, "case_id");
        if (!access.exists(
                "SELECT count(*) FROM resident_cases WHERE id=? AND house_id=? AND status<>'CLOSED'",
                caseId,
                house)) return false;
        if (kind == NotificationKind.ESCALATION) {
            return access.manager(house, user)
                    && access.exists(
                            "SELECT count(*) FROM case_escalations WHERE id=? AND target_id=? AND state<>'CLOSED'",
                            entity,
                            user);
        }
        if (!readableCase(caseId, house, user)) return false;
        if (kind == NotificationKind.REMINDER) {
            if (!access.exists(
                    "SELECT count(*) FROM house_reminder_policies WHERE house_id=? AND source_organization_id=? AND enabled=1",
                    house,
                    n.get("source_organization_id"))) return false;
            boolean assigned =
                    access.exists(
                                    "SELECT count(*) FROM resident_cases WHERE id=? AND responsible_user_id=?",
                                    caseId,
                                    user)
                            || access.exists(
                                    "SELECT count(*) FROM case_followup WHERE case_id=? AND deputy_id=?",
                                    caseId,
                                    user)
                            || access.exists(
                                    "SELECT count(*) FROM case_work_orders WHERE id=? AND assignee_id=?",
                                    entity,
                                    user);
            if (!assigned) return false;
        }
        if (kind == NotificationKind.VISIT
                && !access.exists(
                        "SELECT count(*) FROM case_visits v JOIN resident_cases c ON c.id=v.case_id WHERE v.id=? AND v.state='CONFIRMED' AND (v.worker_id=? OR c.author_id=?)",
                        entity,
                        user,
                        user)) return false;
        return n.get("deadline_kind") != null
                && deadlineOpen(
                        DeadlineKind.valueOf(s(n, "deadline_kind")), entity, s(n, "due_at"));
    }

    public List<Map<String, Object>> inbox(String user) {
        return db
                .queryForList(
                        "SELECT * FROM operations_notifications WHERE user_id=? AND cancelled_at IS NULL ORDER BY rowid DESC LIMIT 100",
                        user)
                .stream()
                .filter(this::allowed)
                .map(
                        n -> {
                            var result = new LinkedHashMap<String, Object>();
                            for (String k :
                                    List.of(
                                            "id",
                                            "house_id",
                                            "case_id",
                                            "kind",
                                            "entity_id",
                                            "body",
                                            "created_at",
                                            "read_at",
                                            "sent_at")) result.put(k, n.get(k));
                            return (Map<String, Object>) result;
                        })
                .toList();
    }

    @Transactional
    public void read(String id, String user) {
        var rows =
                db.queryForList(
                        "SELECT * FROM operations_notifications WHERE id=? AND user_id=?",
                        id,
                        user);
        if (rows.isEmpty() || !allowed(rows.getFirst())) throw missing();
        db.update(
                "UPDATE operations_notifications SET read_at=coalesce(read_at,?) WHERE id=?",
                now(),
                id);
    }

    @Transactional
    public List<Map<String, Object>> claim() {
        String lease = id();
        var result = new ArrayList<Map<String, Object>>();
        for (var n :
                db.queryForList(
                        "SELECT o.*,u.max_user_id FROM operations_notifications o JOIN users u ON u.id=o.user_id WHERE o.sent_at IS NULL AND o.cancelled_at IS NULL AND datetime(o.next_attempt_at)<=datetime('now') AND (o.lease_until IS NULL OR datetime(o.lease_until)<datetime('now')) ORDER BY o.rowid LIMIT 20")) {
            if (!allowed(n)
                    || Instant.parse(s(n, "created_at"))
                            .isBefore(Instant.now().minusSeconds(172800))) {
                db.update(
                        "UPDATE operations_notifications SET cancelled_at=? WHERE id=?",
                        now(),
                        n.get("id"));
                continue;
            }
            if (n.get("max_user_id") == null || s(n, "max_user_id").isBlank()) continue;
            if (db.update(
                            "UPDATE operations_notifications SET lease_token=?,lease_until=? WHERE id=? AND sent_at IS NULL AND (lease_until IS NULL OR datetime(lease_until)<datetime('now'))",
                            lease,
                            Instant.now().plusSeconds(90).toString(),
                            n.get("id"))
                    == 1) {
                n.put("lease_token", lease);
                result.add(n);
            }
        }
        return result;
    }

    public void delivered(String id, String lease) {
        db.update(
                "UPDATE operations_notifications SET sent_at=?,lease_until=NULL,lease_token=NULL,last_error=NULL WHERE id=? AND lease_token=?",
                now(),
                id,
                lease);
    }

    public void retry(String id, String lease, int attempts, String error) {
        db.update(
                "UPDATE operations_notifications SET attempts=?,next_attempt_at=?,lease_until=NULL,lease_token=NULL,last_error=? WHERE id=? AND lease_token=?",
                attempts,
                Instant.now().plusSeconds(Math.min(3600, 1L << Math.min(attempts, 11))).toString(),
                error,
                id,
                lease);
    }
}
