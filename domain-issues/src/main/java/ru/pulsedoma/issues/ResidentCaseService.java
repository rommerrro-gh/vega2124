package ru.pulsedoma.issues;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

@Service
public class ResidentCaseService {
    private final JdbcTemplate jdbc;
    private final AttachmentStore store;
    private final CaseBotNotifications botNotifications;
    private final CaseWorkAccess access;
    private final CaseCompletionGuard completion;
    private final SharedRequestRegistry shared;

    public ResidentCaseService(
            JdbcTemplate jdbc,
            AttachmentStore store,
            CaseBotNotifications botNotifications,
            CaseWorkAccess access,
            CaseCompletionGuard completion,
            SharedRequestRegistry shared) {
        this.jdbc = jdbc;
        this.store = store;
        this.botNotifications = botNotifications;
        this.access = access;
        this.completion = completion;
        this.shared = shared;
    }

    public record Event(
            String id, String actor, CaseEventType type, String text, String createdAt) {}

    public record DocumentVersion(
            String id, int version, String mime, long size, String uploadedBy, String createdAt) {}

    public record Document(String id, String title, List<DocumentVersion> versions) {}

    public record Staff(String id, String name, boolean billingAccess, boolean billingAssignable) {}

    public record Context(
            String actorId,
            boolean intakeAvailable,
            boolean canManagePermissions,
            boolean canReview,
            List<Staff> staff) {}

    public record CaseView(
            String id,
            long number,
            String houseId,
            String address,
            String author,
            CaseKind kind,
            CaseStatus status,
            String description,
            String location,
            String responsibleUserId,
            String responsibleName,
            String nextStep,
            String dueAt,
            int revision,
            String createdAt,
            String updatedAt,
            boolean fullAccess,
            boolean canManage,
            boolean isAuthor,
            List<Event> events,
            List<Document> documents,
            int openWorkCount,
            int overdueWorkCount,
            ReportCategory category,
            String sharedRequestId,
            int participants,
            boolean withdrawn,
            String workflowCaseId,
            boolean visitOffered) {}

    public record FileData(byte[] bytes, String mime) {}

    private boolean count(String sql, Object... args) {
        return Optional.ofNullable(jdbc.queryForObject(sql, Integer.class, args)).orElse(0) > 0;
    }

    private boolean member(String house, String user) {
        return count(
                """
            SELECT count(*) FROM house_memberships m JOIN users u ON u.id=m.user_id
            WHERE m.house_id=? AND m.user_id=? AND m.verification_status='VERIFIED'
              AND m.access_status='ACTIVE' AND u.status='ACTIVE'
            """,
                house,
                user);
    }

    private boolean resident(String house, String user) {
        return member(house, user)
                && count(
                        """
            SELECT count(*) FROM house_memberships WHERE house_id=? AND user_id=?
              AND role='RESIDENT' AND verification_status='VERIFIED' AND access_status='ACTIVE'
            """,
                        house,
                        user);
    }

    private boolean dispatcher(String house, String user) {
        return member(house, user)
                && count(
                        """
            SELECT count(*) FROM house_memberships WHERE house_id=? AND user_id=?
              AND role='DISPATCHER' AND verification_status='VERIFIED' AND access_status='ACTIVE'
            """,
                        house,
                        user);
    }

    private boolean manager(String house, String user) {
        return count(
                """
            SELECT count(*) FROM uk_admin_houses a
            JOIN organization_memberships m ON m.organization_id=a.organization_id AND m.user_id=a.user_id
            JOIN organization_houses h ON h.organization_id=a.organization_id AND h.house_id=a.house_id
            JOIN users u ON u.id=a.user_id
            WHERE a.house_id=? AND a.user_id=? AND a.access_status='ACTIVE'
              AND m.role='UK_ADMIN' AND m.access_status='ACTIVE' AND h.status='ACTIVE' AND u.status='ACTIVE'
            """,
                house,
                user);
    }

    private boolean billing(String house, String user) {
        return access.billing(house, user);
    }

    private boolean collaborator(String id, String house, String user) {
        return access.assigned(id, house, user, CaseFunction.values());
    }

    private boolean work(Map<String, Object> row, String user) {
        String house = str(row, "house_id");
        if (CaseKind.PAYMENT.name().equals(str(row, "kind"))) return billing(house, user);
        return dispatcher(house, user) || collaborator(str(row, "id"), house, user);
    }

    private boolean manages(Map<String, Object> r, String user) {
        if (!work(r, user)) return false;
        if (CaseKind.PAYMENT.name().equals(str(r, "kind")))
            return billing(str(r, "house_id"), user);
        return dispatcher(str(r, "house_id"), user)
                || access.assigned(
                        str(r, "id"),
                        str(r, "house_id"),
                        user,
                        CaseFunction.COORDINATOR,
                        CaseFunction.WARRANTY,
                        CaseFunction.BILLING);
    }

    private boolean owns(Map<String, Object> row, String user) {
        return !Integer.valueOf(1).equals(row.get("internal_work"))
                && user.equals(str(row, "author_id"))
                && resident(str(row, "house_id"), user);
    }

    private Map<String, Object> row(String id) {
        var rows = jdbc.queryForList("SELECT * FROM resident_cases WHERE id=?", id);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    private Map<String, Object> readable(String id, String user) {
        var r = row(id);
        if (!owns(r, user) && !work(r, user)) throw missing();
        return r;
    }

    private void requireWork(Map<String, Object> r, String user) {
        if (!manages(r, user)) throw missing();
    }

    private boolean intake(String house) {
        return count(
                """
            SELECT count(*) FROM house_memberships m JOIN users u ON u.id=m.user_id
            WHERE m.house_id=? AND m.role='DISPATCHER' AND m.verification_status='VERIFIED'
              AND m.access_status='ACTIVE' AND u.status='ACTIVE'
            """,
                house);
    }

    public Context context(String house, String user) {
        if (!member(house, user) && !manager(house, user) && !access.staff(house, user))
            throw missing();
        boolean staff =
                dispatcher(house, user)
                        || manager(house, user)
                        || billing(house, user)
                        || access.staffFunction(house, user, CaseFunction.COORDINATOR);
        List<Staff> list = staff ? access.candidates(house) : List.of();
        return new Context(
                user,
                intake(house),
                manager(house, user),
                staff
                        || count(
                                "SELECT count(*) FROM case_collaborators a JOIN resident_cases c ON c.id=a.case_id WHERE c.house_id=? AND a.user_id=?",
                                house,
                                user),
                list);
    }

    @Transactional
    public void permission(String house, String target, boolean enabled, String actor) {
        if (!manager(house, actor)) throw denied();
        if (enabled && !access.billingEligible(house, target))
            throw invalid("Выберите действующего сотрудника этого дома");
        if (enabled)
            jdbc.update(
                    """
            INSERT INTO case_billing_permissions(house_id,user_id,granted_by,created_at,source_organization_id) VALUES (?,?,?,?,(SELECT organization_id FROM uk_admin_houses WHERE house_id=? AND user_id=? AND access_status='ACTIVE' LIMIT 1))
            ON CONFLICT(house_id,user_id) DO UPDATE SET granted_by=excluded.granted_by,created_at=excluded.created_at,source_organization_id=excluded.source_organization_id
            """,
                    house,
                    target,
                    actor,
                    now(),
                    house,
                    actor);
        else
            jdbc.update(
                    "DELETE FROM case_billing_permissions WHERE house_id=? AND user_id=?",
                    house,
                    target);
        audit(actor, "CASE_BILLING_PERMISSION", house);
    }

    @Transactional
    public CaseView create(
            String house,
            CaseKind kind,
            String text,
            String location,
            String requestId,
            String user) {
        if (!resident(house, user)) throw denied();
        if (kind == null) throw invalid("Укажите тип обращения");
        if (kind == CaseKind.WARRANTY)
            throw invalid("Гарантийный процесс определяет сотрудник после рассмотрения");
        text = clean(text, 4000);
        location = optional(location, 160);
        if (requestId == null || requestId.isBlank() || requestId.length() > 80)
            throw invalid("Не удалось определить запрос. Повторите отправку");
        String fingerprint =
                hash(
                        house.length()
                                + ":"
                                + house
                                + kind.name().length()
                                + ":"
                                + kind.name()
                                + text.length()
                                + ":"
                                + text
                                + Objects.toString(location, "").length()
                                + ":"
                                + Objects.toString(location, ""));
        var existing =
                jdbc.queryForList(
                        "SELECT id,request_hash FROM resident_cases WHERE author_id=? AND client_request_id=?",
                        user,
                        requestId);
        if (!existing.isEmpty()) {
            if (!fingerprint.equals(str(existing.get(0), "request_hash"))) throw conflict();
            return get(str(existing.get(0), "id"), user);
        }
        if (!intake(house))
            throw new BusinessException(
                    "CASE_ROUTING_UNAVAILABLE",
                    "В этом доме пока не назначен сотрудник. Сохраните черновик; приём ещё не подключён");
        String id = UUID.randomUUID().toString(), time = now();
        jdbc.update(
                """
            INSERT INTO resident_cases(id,house_id,author_id,kind,status,description,location,client_request_id,request_hash,next_step,created_at,updated_at)
            VALUES (?,?,?,?,'RECEIVED',?,?,?,?,?,?,?)
            """,
                id,
                house,
                user,
                kind.name(),
                text,
                location,
                requestId,
                fingerprint,
                "Первичное рассмотрение: определяем, кто поможет",
                time,
                time);
        event(
                id,
                user,
                CaseEventType.REGISTERED,
                "Обращение зарегистрировано. Выбирать организацию не нужно",
                null);
        audit(user, "CASE_REGISTERED", id);
        shared.ensure(id, user);
        return get(id, user);
    }

    public List<CaseView> mine(String user) {
        return jdbc
                .query(
                        "SELECT id FROM resident_cases WHERE author_id=? AND internal_work=0 ORDER BY number DESC",
                        (rs, n) -> rs.getString(1),
                        user)
                .stream()
                .filter(id -> owns(row(id), user))
                .map(id -> get(id, user))
                .toList();
    }

    public List<CaseView> queue(String house, String user) {
        boolean intakeWorker = dispatcher(house, user) || manager(house, user);
        if (!intakeWorker
                && !billing(house, user)
                && !access.staff(house, user)
                && !count(
                        """
            SELECT count(*) FROM case_collaborators a JOIN resident_cases c ON c.id=a.case_id
            WHERE c.house_id=? AND a.user_id=?
            """,
                        house,
                        user)) throw denied();
        if (!member(house, user) && !manager(house, user) && !access.staff(house, user))
            throw denied();
        var result = new ArrayList<CaseView>();
        var seen = new HashSet<String>();
        for (String id :
                jdbc.query(
                        "SELECT id FROM resident_cases WHERE house_id=? ORDER BY number DESC",
                        (rs, n) -> rs.getString(1),
                        house)) {
            var g = shared.group(id);
            String selected = id, key = id;
            if (g != null) {
                if (g.get("merged_into") != null) continue;
                key = (String) g.get("id");
                var active = shared.activeCases(key);
                String root = (String) g.get("root_case_id");
                if (active.isEmpty() && "CLOSED".equals(row(root).get("status"))) continue;
                if (intakeWorker || work(row(root), user))
                    selected =
                            intakeWorker && active.size() == 1 && work(row(active.getFirst()), user)
                                    ? active.getFirst()
                                    : root;
            }
            if (!seen.contains(key) && (intakeWorker || work(row(selected), user))) {
                seen.add(key);
                result.add(view(row(selected), user));
            }
        }
        return result;
    }

    public CaseView get(String id, String user) {
        return view(readable(id, user), user);
    }

    private CaseView view(Map<String, Object> r, String user) {
        String id = str(r, "id"), house = str(r, "house_id");
        boolean author = owns(r, user), canWork = work(r, user), full = author || canWork;
        String address =
                jdbc.queryForObject("SELECT address FROM houses WHERE id=?", String.class, house);
        String responsible = str(r, "responsible_user_id");
        var sharedGroup = shared.group(id);
        boolean withdrawn = shared.withdrawn(id), confirmed = shared.confirmed(id);
        String responsibleName =
                responsible == null
                        ? "Первичное рассмотрение"
                        : jdbc.queryForObject(
                                "SELECT display_name FROM users WHERE id=?",
                                String.class,
                                responsible);
        return new CaseView(
                id,
                ((Number) r.get("number")).longValue(),
                house,
                address,
                full
                        ? jdbc.queryForObject(
                                "SELECT display_name FROM users WHERE id=?",
                                String.class,
                                str(r, "author_id"))
                        : null,
                CaseKind.PROBLEM.name().equals(str(r, "kind"))
                        ? CaseKind.PROBLEM
                        : CaseKind.valueOf(str(r, "kind")),
                author && (withdrawn || confirmed)
                        ? CaseStatus.CLOSED
                        : CaseStatus.valueOf(str(r, "status")),
                full
                        ? str(r, "description")
                        : "Содержание доступно только участникам обработки с необходимыми полномочиями",
                full ? str(r, "location") : null,
                responsible,
                responsibleName,
                author && withdrawn
                        ? "Вы отозвали своё обращение"
                        : author && confirmed
                                ? "Вы подтвердили результат"
                                : full
                                        ? str(r, "next_step")
                                        : "Сотрудник с необходимым доступом рассматривает вопрос",
                full ? str(r, "due_at") : null,
                ((Number) r.get("revision")).intValue(),
                str(r, "created_at"),
                str(r, "updated_at"),
                full,
                manages(r, user),
                author,
                full
                        ? jdbc.query(
                                """
                SELECT e.*,u.display_name FROM case_events e JOIN users u ON u.id=e.actor_id
                WHERE e.case_id=? ORDER BY e.rowid
                """,
                                (rs, n) ->
                                        new Event(
                                                rs.getString("id"),
                                                rs.getString("display_name"),
                                                CaseEventType.valueOf(rs.getString("type")),
                                                rs.getString("text"),
                                                rs.getString("created_at")),
                                id)
                        : List.of(),
                full ? documents(id) : List.of(),
                full ? workCount(id, false) : 0,
                full ? workCount(id, true) : 0,
                full && sharedGroup != null
                        ? ReportCategory.valueOf((String) sharedGroup.get("category"))
                        : full
                                ? jdbc
                                        .query(
                                                "SELECT category FROM case_problem_details WHERE case_id=?",
                                                (rs, n) ->
                                                        rs.getString(1) == null
                                                                ? null
                                                                : ReportCategory.valueOf(
                                                                        rs.getString(1)),
                                                id)
                                        .stream()
                                        .filter(Objects::nonNull)
                                        .findFirst()
                                        .orElse(null)
                                : null,
                sharedGroup == null ? null : (String) sharedGroup.get("id"),
                sharedGroup == null ? 1 : shared.participants((String) sharedGroup.get("id")),
                withdrawn,
                shared.root(id),
                full
                        && count(
                                "SELECT count(*) FROM case_visits WHERE case_id=? AND state='OFFERED'",
                                id));
    }

    private int workCount(String caseId, boolean overdue) {
        var group = shared.group(caseId);
        var ids = new HashSet<String>();
        ids.add(caseId);
        if (group != null) {
            ids.addAll(shared.allCases((String) group.get("id")));
            ids.add((String) group.get("root_case_id"));
        }
        return ids.stream()
                .mapToInt(
                        id ->
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM case_work_orders WHERE case_id=? AND state NOT IN ('ACCEPTED','CANCELLED')"
                                                + (overdue
                                                        ? " AND due_at IS NOT NULL AND datetime(due_at)<datetime('now')"
                                                        : ""),
                                        Integer.class,
                                        id))
                .sum();
    }

    @Transactional
    public CaseView update(
            String id,
            CaseKind kind,
            CaseStatus status,
            String nextStep,
            Instant due,
            String responsible,
            int revision,
            String user) {
        return update(id, kind, status, nextStep, due, responsible, revision, null, user);
    }

    @Transactional
    public CaseView update(
            String id,
            CaseKind kind,
            CaseStatus status,
            String nextStep,
            Instant due,
            String responsible,
            int revision,
            String reason,
            String user) {
        var r = row(id);
        requireWork(r, user);
        if (status == CaseStatus.RESULT_PROPOSED) {
            completion.requireComplete(id);
            var g = shared.group(id);
            if (g != null) {
                for (String member : shared.allCases((String) g.get("id")))
                    completion.requireComplete(member);
                completion.requireComplete(shared.root(id));
            }
        }
        if (kind == CaseKind.PAYMENT && shared.group(id) != null)
            throw invalid("Вопрос по квитанции рассматривается отдельно от общей заявки");
        if (status == CaseStatus.RECEIVED
                || status == CaseStatus.CLOSED
                || status == CaseStatus.REOPENED)
            throw invalid("Используйте рассмотрение или предложите результат жителю");
        if (CaseStatus.CLOSED.name().equals(str(r, "status")))
            throw invalid("Сначала нужно повторное обращение жителя");
        if (kind == null || status == null) throw invalid("Укажите направление и состояние");
        if (CaseKind.PAYMENT.name().equals(str(r, "kind")) && kind != CaseKind.PAYMENT)
            throw invalid("Личное финансовое дело нельзя сделать общим");
        String house = str(r, "house_id");
        if (kind == CaseKind.PAYMENT && !billing(house, user)) throw denied();
        responsible = optional(responsible, 80);
        if (responsible != null
                && ((!member(house, responsible)
                                && !access.staff(house, responsible)
                                && !(kind == CaseKind.PAYMENT && billing(house, responsible)))
                        || (!dispatcher(house, responsible)
                                && !collaborator(id, house, responsible)
                                && !billing(house, responsible))
                        || (kind == CaseKind.PAYMENT && !billing(house, responsible))))
            throw invalid("Сотрудник не имеет необходимого доступа к этому дому и направлению");
        boolean movedDue =
                r.get("due_at") != null && !Objects.equals(Instant.parse(str(r, "due_at")), due);
        boolean reassigned =
                r.get("responsible_user_id") != null
                        && !Objects.equals(str(r, "responsible_user_id"), responsible);
        String explanation = optional(reason, 1000);
        if ((movedDue || reassigned) && explanation == null)
            throw invalid("Укажите причину изменения срока или ответственного");
        int changed =
                jdbc.update(
                        """
            UPDATE resident_cases SET kind=?,status=?,next_step=?,due_at=?,responsible_user_id=?,revision=revision+1,updated_at=?
            WHERE id=? AND revision=?
            """,
                        kind.name(),
                        status.name(),
                        clean(nextStep, 600),
                        due == null ? null : due.toString(),
                        responsible,
                        now(),
                        id,
                        revision);
        if (changed != 1) throw conflict();
        event(
                id,
                user,
                CaseEventType.UPDATED,
                "Следующий шаг: "
                        + nextStep.strip()
                        + ". Срок шага: "
                        + (due == null
                                ? "уточняется"
                                : due.atZone(java.time.ZoneId.of("Europe/Moscow"))
                                                .format(
                                                        java.time.format.DateTimeFormatter
                                                                .ofPattern("dd.MM.yyyy HH:mm"))
                                        + " МСК")
                        + (explanation == null ? "" : ". Причина изменения: " + explanation),
                null);
        if (!Objects.equals(responsible, str(r, "responsible_user_id")))
            event(
                    id,
                    user,
                    CaseEventType.ASSIGNED,
                    responsible == null
                            ? "Обращение сопровождает очередь первичного рассмотрения"
                            : "Назначен ответственный: "
                                    + jdbc.queryForObject(
                                            "SELECT display_name FROM users WHERE id=?",
                                            String.class,
                                            responsible),
                    null);
        audit(user, "CASE_UPDATED", id);
        var group = shared.group(id);
        if (status == CaseStatus.RESULT_PROPOSED
                && group != null
                && shared.participants((String) group.get("id")) == 0)
            jdbc.update(
                    "UPDATE resident_cases SET status='CLOSED',next_step='Все обращения отозваны, оставшиеся работы завершены',revision=revision+1,updated_at=? WHERE id=?",
                    now(),
                    id);
        shared.sync(id, user);
        return get(id, user);
    }

    @Transactional
    public CaseView assign(
            String id,
            String target,
            CaseFunction function,
            boolean enabled,
            int revision,
            String actor) {
        var r = row(id);
        String house = str(r, "house_id");
        if (!manager(house, actor)
                && !(access.coordinator(id, house, actor)
                        && List.of(CaseFunction.ENGINEER, CaseFunction.CONTRACTOR)
                                .contains(function))) throw denied();
        if (!access.eligible(house, target, function)
                || (CaseKind.PAYMENT.name().equals(str(r, "kind")) && !billing(house, target)))
            throw invalid("Выберите сотрудника с необходимым доступом");
        if (jdbc.update(
                        "UPDATE resident_cases SET revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                        now(),
                        id,
                        revision)
                != 1) throw conflict();
        if (function == CaseFunction.COORDINATOR)
            jdbc.update(
                    "DELETE FROM case_control_grants WHERE case_id=? AND user_id=?", id, target);
        if (enabled)
            jdbc.update(
                    """
            INSERT INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,?,?,?,(SELECT organization_id FROM organization_houses WHERE house_id=? AND status='ACTIVE' LIMIT 1))
            ON CONFLICT(case_id,user_id,function) DO UPDATE SET function=excluded.function,assigned_by=excluded.assigned_by,source_organization_id=excluded.source_organization_id
            """,
                    id,
                    target,
                    function.name(),
                    actor,
                    now(),
                    house);
        else
            jdbc.update(
                    "DELETE FROM case_collaborators WHERE case_id=? AND user_id=? AND function=?",
                    id,
                    target,
                    function.name());
        event(
                id,
                actor,
                CaseEventType.ASSIGNED,
                enabled ? "Назначен участник работы по обращению" : "Назначение участника отозвано",
                null);
        audit(actor, "CASE_ASSIGNMENT_CHANGED", id);
        return view(row(id), actor);
    }

    @Transactional
    public CaseView message(String id, String text, String requestId, String user) {
        var r = readable(id, user);
        if (owns(r, user)) shared.active(id);
        text = clean(text, 4000);
        var prior =
                jdbc.queryForList(
                        "SELECT text FROM case_events WHERE case_id=? AND actor_id=? AND client_request_id=?",
                        id,
                        user,
                        requestId);
        if (!prior.isEmpty()) {
            if (!text.equals(str(prior.get(0), "text"))) throw conflict();
            return get(id, user);
        }
        if (CaseStatus.CLOSED.name().equals(str(r, "status")))
            throw invalid("Используйте кнопку повторного рассмотрения");
        event(id, user, CaseEventType.MESSAGE, text, requestId);
        if (owns(r, user) && CaseStatus.WAITING_RESIDENT.name().equals(str(r, "status")))
            jdbc.update(
                    "UPDATE resident_cases SET status='IN_REVIEW',next_step='Сотрудник рассматривает ваш ответ',revision=revision+1,updated_at=? WHERE id=?",
                    now(),
                    id);
        else
            jdbc.update(
                    "UPDATE resident_cases SET revision=revision+1,updated_at=? WHERE id=?",
                    now(),
                    id);
        return get(id, user);
    }

    @Transactional
    public CaseView respond(
            String id, boolean confirmed, String comment, int revision, String user) {
        var r = readable(id, user);
        if (!owns(r, user)) throw denied();
        if (shared.respond(id, confirmed, comment, revision, user, completion)) {
            String note = optional(comment, 2000);
            event(
                    id,
                    user,
                    confirmed ? CaseEventType.CONFIRMED : CaseEventType.REOPENED,
                    confirmed
                            ? "Вы подтвердили результат"
                            : "Вы сообщили, что проблема осталась"
                                    + (note == null ? "" : ": " + note),
                    null);
            return get(id, user);
        }
        String current = str(r, "status");
        if (confirmed) completion.requireComplete(id);
        if (confirmed && !CaseStatus.RESULT_PROPOSED.name().equals(current))
            throw invalid("Результат ещё не предложен");
        if (!confirmed && !List.of("RESULT_PROPOSED", "CLOSED").contains(current))
            throw invalid("Повторная проверка пока не требуется");
        if (jdbc.update(
                        """
            UPDATE resident_cases SET status=?,next_step=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?
            """,
                        confirmed ? "CLOSED" : "REOPENED",
                        confirmed
                                ? "Вы подтвердили результат"
                                : "Сотрудник повторно рассматривает вопрос",
                        now(),
                        id,
                        revision)
                != 1) throw conflict();
        String note = optional(comment, 2000);
        event(
                id,
                user,
                confirmed ? CaseEventType.CONFIRMED : CaseEventType.REOPENED,
                (confirmed ? "Житель подтвердил результат" : "Житель просит повторную проверку")
                        + (note == null ? "" : ". " + note),
                null);
        audit(user, confirmed ? "CASE_CONFIRMED" : "CASE_REOPENED", id);
        return get(id, user);
    }

    private List<Document> documents(String id) {
        return jdbc.query(
                "SELECT id,title FROM case_documents WHERE case_id=? ORDER BY rowid",
                (rs, n) ->
                        new Document(
                                rs.getString(1),
                                rs.getString(2),
                                jdbc.query(
                                        """
                SELECT v.*,u.display_name FROM case_document_versions v JOIN users u ON u.id=v.uploaded_by
                WHERE document_id=? ORDER BY version DESC
                """,
                                        (v, i) ->
                                                new DocumentVersion(
                                                        v.getString("id"),
                                                        v.getInt("version"),
                                                        v.getString("mime"),
                                                        v.getLong("size_bytes"),
                                                        v.getString("display_name"),
                                                        v.getString("created_at")),
                                        rs.getString(1))),
                id);
    }

    @Transactional
    public CaseView upload(
            String id,
            String documentId,
            String title,
            byte[] bytes,
            String mime,
            int expectedVersion,
            String user) {
        var r = readable(id, user);
        if (owns(r, user)) shared.active(id);
        if (CaseStatus.CLOSED.name().equals(str(r, "status")))
            throw invalid("Для добавления документов запросите повторное рассмотрение");
        if (bytes == null
                || bytes.length == 0
                || bytes.length > 10 * 1024 * 1024
                || !validDocument(bytes, mime))
            throw invalid("Допустимы PDF, JPEG или PNG до 10 МБ");
        String digest = hash(bytes);
        if (count(
                """
            SELECT count(*) FROM case_document_versions v JOIN case_documents d ON d.id=v.document_id
            WHERE d.case_id=? AND v.uploaded_by=? AND v.sha256=? AND (? IS NULL OR d.id=?)
            """,
                id,
                user,
                digest,
                documentId,
                documentId)) return get(id, user);
        Long total =
                jdbc.queryForObject(
                        "SELECT coalesce(sum(v.size_bytes),0) FROM case_document_versions v JOIN case_documents d ON d.id=v.document_id WHERE d.case_id=?",
                        Long.class,
                        id);
        if (total != null && total + bytes.length > 100L * 1024 * 1024)
            throw invalid("Суммарный размер документов обращения превышает 100 МБ");
        if (documentId == null || documentId.isBlank()) {
            if (jdbc.queryForObject(
                            "SELECT count(*) FROM case_documents WHERE case_id=?",
                            Integer.class,
                            id)
                    >= 20) throw invalid("В обращении уже 20 документов");
            documentId = UUID.randomUUID().toString();
            jdbc.update(
                    "INSERT INTO case_documents(id,case_id,title,created_at) VALUES (?,?,?,?)",
                    documentId,
                    id,
                    clean(title, 160),
                    now());
        } else if (!count(
                "SELECT count(*) FROM case_documents WHERE id=? AND case_id=?", documentId, id))
            throw missing();
        int latest =
                jdbc.queryForObject(
                        "SELECT coalesce(max(version),0) FROM case_document_versions WHERE document_id=?",
                        Integer.class,
                        documentId);
        if (latest != expectedVersion) throw conflict();
        if (latest >= 20) throw invalid("Достигнут предел версий документа");
        String versionId = UUID.randomUUID().toString(), key = "cases/" + id + "/" + versionId;
        // Private store; files are only returned through the permission-checked download endpoint.
        store.put(key, bytes, mime);
        jdbc.update(
                """
            INSERT INTO case_document_versions(id,document_id,version,uploaded_by,storage_key,mime,sha256,size_bytes,created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,
                versionId,
                documentId,
                latest + 1,
                user,
                key,
                mime,
                digest,
                bytes.length,
                now());
        event(
                id,
                user,
                CaseEventType.DOCUMENT,
                latest == 0 ? "Добавлен документ" : "Сохранена новая версия документа",
                null);
        jdbc.update(
                "UPDATE resident_cases SET revision=revision+1,updated_at=? WHERE id=?", now(), id);
        audit(user, "CASE_DOCUMENT_ADDED", id);
        return get(id, user);
    }

    public FileData download(String version, String user) {
        var rows =
                jdbc.queryForList(
                        """
            SELECT v.storage_key,v.mime,d.case_id FROM case_document_versions v JOIN case_documents d ON d.id=v.document_id WHERE v.id=?
            """,
                        version);
        if (rows.isEmpty()) throw missing();
        var v = rows.get(0);
        var original = row(str(v, "case_id"));
        if (!owns(original, user)
                && !work(original, user)
                && jdbc
                        .query(
                                "SELECT case_id FROM shared_work_document_access WHERE version_id=?",
                                (rs, n) -> rs.getString(1),
                                version)
                        .stream()
                        .noneMatch(id -> work(row(id), user))) throw missing();
        return new FileData(store.get(str(v, "storage_key")), str(v, "mime"));
    }

    public static boolean validDocument(byte[] b, String mime) {
        if (mime == null) return false;
        return switch (mime) {
            case "application/pdf" ->
                    b.length >= 12
                            && new String(b, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")
                            && new String(
                                            b,
                                            Math.max(0, b.length - 1024),
                                            Math.min(1024, b.length),
                                            StandardCharsets.ISO_8859_1)
                                    .contains("%%EOF");
            case "image/jpeg" ->
                    b.length >= 3
                            && (b[0] & 255) == 255
                            && (b[1] & 255) == 216
                            && (b[2] & 255) == 255;
            case "image/png" ->
                    b.length >= 8
                            && Arrays.equals(
                                    Arrays.copyOf(b, 8),
                                    new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            default -> false;
        };
    }

    @Transactional
    public void workflowEvent(String id, String actor, int revision, String text, String nextStep) {
        var c = get(id, actor);
        if ("CLOSED".equals(row(id).get("status")))
            throw invalid("Для продолжения работы житель должен запросить повторное рассмотрение");
        if (jdbc.update(
                        "UPDATE resident_cases SET revision=revision+1,updated_at=?,next_step=coalesce(?,next_step),status=CASE WHEN ? IS NOT NULL THEN 'IN_PROGRESS' ELSE status END WHERE id=? AND revision=?",
                        now(),
                        nextStep,
                        nextStep,
                        id,
                        revision)
                != 1) throw conflict();
        event(id, actor, CaseEventType.UPDATED, text, null);
        audit(actor, "CASE_WORKFLOW_CHANGED", id);
        shared.sync(id, actor);
    }

    private void event(String id, String actor, CaseEventType type, String text, String key) {
        jdbc.update(
                "INSERT INTO case_events(id,case_id,actor_id,type,text,client_request_id,created_at) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                id,
                actor,
                type.name(),
                text,
                key,
                now());
        botNotifications.enqueue(id, actor, type);
    }

    private void audit(String actor, String action, String id) {
        jdbc.update(
                "INSERT INTO audit_events(id,actor_id,action,entity,entity_id,created_at) VALUES (?,?,?,'resident_case',?,?)",
                UUID.randomUUID().toString(),
                actor,
                action,
                id,
                now());
    }

    private static String str(Map<String, Object> r, String key) {
        return r.get(key) == null ? null : r.get(key).toString();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank() || s.strip().length() > max)
            throw invalid("Проверьте заполнение и длину сообщения");
        return s.strip();
    }

    private static String optional(String s, int max) {
        return s == null || s.isBlank() ? null : clean(s, max);
    }

    private static String hash(String s) {
        return hash(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_CASE", message);
    }

    private static BusinessException denied() {
        return new BusinessException(
                "HOUSE_ACCESS_DENIED", "Нет необходимого доступа к этому дому");
    }

    private static BusinessException missing() {
        return new BusinessException("CASE_NOT_FOUND", "Обращение или документ недоступны");
    }

    private static BusinessException conflict() {
        return new BusinessException(
                "CASE_CONFLICT", "Данные изменились. Обновите обращение перед повторным действием");
    }
}
