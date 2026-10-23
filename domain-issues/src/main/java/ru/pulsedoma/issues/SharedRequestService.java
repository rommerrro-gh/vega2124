package ru.pulsedoma.issues;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

@Service
public class SharedRequestService {
    private final JdbcTemplate db;
    private final ResidentCaseService cases;
    private final SharedRequestRegistry registry;
    private final CaseWorkAccess access;
    private final ObjectMapper mapper;
    private final CaseBotNotifications bot;
    private final CaseCompletionGuard completion;
    private final SharedWorkRegistry work;

    public SharedRequestService(
            JdbcTemplate db,
            ResidentCaseService cases,
            SharedRequestRegistry registry,
            CaseWorkAccess access,
            ObjectMapper mapper,
            CaseBotNotifications bot,
            CaseCompletionGuard completion,
            SharedWorkRegistry work) {
        this.db = db;
        this.cases = cases;
        this.registry = registry;
        this.access = access;
        this.mapper = mapper;
        this.bot = bot;
        this.completion = completion;
        this.work = work;
    }

    private String now() {
        return Instant.now().toString();
    }

    private BusinessException missing() {
        return new BusinessException("CASE_NOT_FOUND", "Общая заявка недоступна");
    }

    private BusinessException invalid(String text) {
        return new BusinessException("INVALID_CASE", text);
    }

    private BusinessException conflict() {
        return new BusinessException("CASE_CONFLICT", "Заявка изменилась. Обновите данные");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> row(String id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM shared_requests WHERE id=? AND merged_into IS NULL", id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private boolean resident(String house, String user) {
        return access.member(house, user)
                && access.exists(
                        "SELECT count(*) FROM house_memberships WHERE house_id=? AND user_id=? AND role='RESIDENT' AND verification_status='VERIFIED' AND access_status='ACTIVE'",
                        house,
                        user);
    }

    public boolean canManage(Map<String, Object> group, String user) {
        return access.manager((String) group.get("house_id"), user)
                || access.coordinator(
                        (String) group.get("root_case_id"), (String) group.get("house_id"), user);
    }

    private void check(Map<String, Object> g, String user) {
        if (!resident((String) g.get("house_id"), user)
                && !canManage(g, user)
                && !access.assigned(
                        (String) g.get("root_case_id"),
                        (String) g.get("house_id"),
                        user,
                        CaseFunction.values())) throw missing();
    }

    public record Member(
            String caseId,
            long number,
            String name,
            String status,
            boolean withdrawn,
            boolean confirmed) {}

    public record PublicEvent(String action, String note, String createdAt) {}

    public record View(
            String id,
            String title,
            ReportCategory category,
            String location,
            CaseStatus status,
            String nextStep,
            String dueAt,
            String responsibleName,
            int participants,
            int confirmedCount,
            int revision,
            String myCaseId,
            boolean canManage,
            List<Member> members,
            List<PublicEvent> history,
            List<SharedWorkRegistry.Item> work,
            String workflowCaseId) {}

    public View get(String id, String user) {
        var g = row(id);
        check(g, user);
        boolean manage = canManage(g, user);
        String house = (String) g.get("house_id");
        var mine =
                db.query(
                        "SELECT case_id FROM shared_request_members WHERE request_id=? AND user_id=? AND withdrawn_at IS NULL ORDER BY joined_at,case_id",
                        (r, n) -> r.getString(1),
                        id,
                        user);
        List<Member> members =
                manage
                        ? db.query(
                                "SELECT m.*,c.number,c.status,u.display_name FROM shared_request_members m JOIN resident_cases c ON c.id=m.case_id JOIN users u ON u.id=m.user_id WHERE m.request_id=? ORDER BY m.joined_at,m.case_id",
                                (r, n) ->
                                        new Member(
                                                r.getString("case_id"),
                                                r.getLong("number"),
                                                r.getString("display_name"),
                                                r.getString("status"),
                                                r.getString("withdrawn_at") != null,
                                                r.getString("confirmed_at") != null),
                                id)
                        : List.of();
        String responsible =
                g.get("responsible_user_id") == null
                        ? "Очередь первичного рассмотрения"
                        : db.queryForObject(
                                "SELECT display_name FROM users WHERE id=?",
                                String.class,
                                g.get("responsible_user_id"));
        int yes =
                db.queryForObject(
                        "SELECT count(DISTINCT user_id) FROM shared_request_members WHERE request_id=? AND withdrawn_at IS NULL AND confirmed_at IS NOT NULL",
                        Integer.class,
                        id);
        var events =
                db.query(
                        "SELECT action,note,created_at FROM shared_request_history WHERE request_id=? ORDER BY rowid",
                        (r, n) -> new PublicEvent(r.getString(1), r.getString(2), r.getString(3)),
                        id);
        return new View(
                id,
                (String) g.get("title"),
                ReportCategory.valueOf((String) g.get("category")),
                (String) g.get("location"),
                CaseStatus.valueOf((String) g.get("status")),
                (String) g.get("next_step"),
                (String) g.get("due_at"),
                responsible,
                registry.participants(id),
                yes,
                ((Number) g.get("revision")).intValue(),
                mine.isEmpty() ? null : mine.getFirst(),
                manage,
                members,
                events,
                manage ? work.list((String) g.get("root_case_id")) : List.of(),
                (String) g.get("root_case_id"));
    }

    public ResidentCaseService.CaseView byCase(String caseId, String user) {
        return cases.get(caseId, user);
    }

    @Transactional
    public ResidentCaseService.CaseView join(
            String id, ProblemIntakeService.Input input, String key, String user) {
        var g = row(id);
        if (!resident((String) g.get("house_id"), user)
                || !g.get("house_id").equals(input.houseId())) throw missing();
        String body = json(List.of(id, input));
        var prior =
                db.queryForList(
                        "SELECT request_body,case_id FROM shared_request_commands WHERE user_id=? AND client_request_id=?",
                        user,
                        key);
        if (!prior.isEmpty()) {
            if (!body.equals(prior.getFirst().get("request_body"))) throw conflict();
            return cases.get((String) prior.getFirst().get("case_id"), user);
        }
        var mine =
                db.query(
                        "SELECT case_id FROM shared_request_members WHERE request_id=? AND user_id=? AND withdrawn_at IS NULL ORDER BY joined_at",
                        (r, n) -> r.getString(1),
                        id,
                        user);
        if (!mine.isEmpty()) return cases.get(mine.getFirst(), user);
        if ("CLOSED".equals(g.get("status")) || registry.participants(id) == 0)
            throw invalid("Заявка уже закрыта. Создайте новую");
        if (access.exists(
                "SELECT count(*) FROM resident_cases WHERE author_id=? AND client_request_id=?",
                user,
                key)) throw conflict();
        String location =
                input.locationFeatures() == null
                        ? input.location()
                        : input.locationFeatures().label();
        var created =
                cases.create(
                        input.houseId(),
                        CaseKind.PROBLEM,
                        input.description(),
                        location,
                        key,
                        user);
        var temporary = registry.group(created.id());
        db.update(
                "UPDATE shared_request_members SET request_id=? WHERE case_id=?", id, created.id());
        db.update("DELETE FROM shared_request_history WHERE request_id=?", temporary.get("id"));
        db.update("DELETE FROM shared_requests WHERE id=?", temporary.get("id"));
        db.update("DELETE FROM resident_cases WHERE id=?", temporary.get("root_case_id"));
        String kind =
                db.queryForObject(
                        "SELECT kind FROM resident_cases WHERE id=?",
                        String.class,
                        g.get("root_case_id"));
        db.update(
                "UPDATE resident_cases SET kind=?,status=?,next_step=?,due_at=?,responsible_user_id=?,revision=revision+1,updated_at=? WHERE id=?",
                kind,
                g.get("status"),
                g.get("next_step"),
                g.get("due_at"),
                g.get("responsible_user_id"),
                now(),
                created.id());
        db.update(
                "INSERT INTO case_problem_details(case_id,category,location_features) VALUES (?,?,?)",
                created.id(),
                input.category() == null ? "OTHER" : input.category().name(),
                input.locationFeatures() == null ? null : json(input.locationFeatures()));
        db.update(
                "INSERT INTO shared_request_commands(user_id,client_request_id,request_body,case_id) VALUES (?,?,?,?)",
                user,
                key,
                body,
                created.id());
        db.update(
                "UPDATE shared_requests SET revision=revision+1,updated_at=? WHERE id=?",
                now(),
                id);
        registry.history(
                id,
                user,
                SharedRequestRegistry.Action.JOINED,
                "К заявке присоединился ещё один житель");
        return cases.get(created.id(), user);
    }

    @Transactional
    public ResidentCaseService.CaseView withdraw(
            String caseId, int revision, String reason, String user) {
        var c = cases.get(caseId, user);
        if (!c.isAuthor()) throw missing();
        if (c.revision() != revision) throw conflict();
        registry.active(caseId);
        var g = registry.group(caseId);
        if (g == null) throw invalid("Отзыв финансового вопроса согласуйте в переписке");
        db.update(
                "UPDATE shared_request_members SET withdrawn_at=? WHERE case_id=?", now(), caseId);
        db.update(
                "UPDATE case_visits SET state='CANCELLED',resident_note=?,revision=revision+1,updated_at=? WHERE case_id=? AND state IN ('OFFERED','CONFIRMED','CHANGE_REQUESTED')",
                "Житель отозвал обращение: " + reason,
                now(),
                caseId);
        db.update(
                "UPDATE resident_cases SET revision=revision+1,updated_at=? WHERE id=?",
                now(),
                caseId);
        db.update(
                "INSERT INTO case_events(id,case_id,actor_id,type,text,created_at) VALUES (?,?,?,'UPDATED',?,?)",
                UUID.randomUUID().toString(),
                caseId,
                user,
                "Вы отозвали своё обращение: " + reason,
                now());
        db.update(
                "UPDATE max_bot_outbox SET cancelled_at=? WHERE case_id=? AND user_id=? AND sent_at IS NULL",
                now(),
                caseId,
                user);
        registry.history(
                (String) g.get("id"),
                user,
                SharedRequestRegistry.Action.WITHDRAWN,
                "Участник отозвал своё обращение. Остальные обращения сохранены");
        if (registry.participants((String) g.get("id")) == 0) {
            boolean complete = true;
            try {
                for (String member : registry.allCases((String) g.get("id")))
                    completion.requireComplete(member);
                completion.requireComplete((String) g.get("root_case_id"));
            } catch (BusinessException unfinished) {
                complete = false;
            }
            if (!complete) {
                db.update(
                        "UPDATE resident_cases SET status='IN_REVIEW',next_step='Все участники отозвали обращения. Завершите или отмените оставшиеся работы',revision=revision+1,updated_at=? WHERE id=?",
                        now(),
                        g.get("root_case_id"));
                db.update(
                        "UPDATE shared_requests SET status='IN_REVIEW',next_step='Остались незавершённые работы после отзыва обращений',revision=revision+1,updated_at=? WHERE id=?",
                        now(),
                        g.get("id"));
                return cases.get(caseId, user);
            }
            db.update(
                    "UPDATE shared_requests SET status='CLOSED',next_step='Все обращения отозваны',revision=revision+1,updated_at=? WHERE id=?",
                    now(),
                    g.get("id"));
            db.update(
                    "UPDATE resident_cases SET status='CLOSED',next_step='Все обращения отозваны',revision=revision+1,updated_at=? WHERE id=?",
                    now(),
                    g.get("root_case_id"));
        } else
            db.update(
                    "UPDATE shared_requests SET revision=revision+1,updated_at=? WHERE id=?",
                    now(),
                    g.get("id"));
        return cases.get(caseId, user);
    }

    private void manage(Map<String, Object> g, int revision, String user) {
        if (!canManage(g, user)) throw missing();
        if (((Number) g.get("revision")).intValue() != revision) throw conflict();
    }

    @Transactional
    public View classify(
            String id,
            String title,
            ReportCategory category,
            String location,
            int revision,
            String reason,
            String user) {
        var g = row(id);
        manage(g, revision, user);
        db.update(
                "UPDATE shared_requests SET title=?,category=?,location=?,location_features=CASE WHEN category=? AND location IS ? THEN location_features ELSE NULL END,normalized_text=?,revision=revision+1,updated_at=? WHERE id=?",
                title,
                category.name(),
                location,
                category.name(),
                location,
                new ru.pulsedoma.duplicates.TextNormalizer().normalize(title),
                now(),
                id);
        db.update(
                "UPDATE case_problem_details SET category=? WHERE case_id IN (SELECT case_id FROM shared_request_members WHERE request_id=?)",
                category.name(),
                id);
        registry.history(
                id,
                user,
                SharedRequestRegistry.Action.CLASSIFIED,
                "Уточнена категория и описание общей проблемы: " + reason);
        return get(id, user);
    }

    @Transactional
    public View merge(
            String source,
            String target,
            int sourceRevision,
            int targetRevision,
            String reason,
            String user) {
        if (source.equals(target)) throw invalid("Выберите другую заявку");
        var a = row(source);
        var b = row(target);
        manage(a, sourceRevision, user);
        manage(b, targetRevision, user);
        if (!a.get("house_id").equals(b.get("house_id")))
            throw invalid("Нельзя объединять разные дома");
        if ("CLOSED".equals(a.get("status")) || "CLOSED".equals(b.get("status")))
            throw invalid("Объединяются только активные заявки");
        if (!a.get("category").equals(b.get("category"))
                && !"OTHER".equals(a.get("category"))
                && !"OTHER".equals(b.get("category")))
            throw invalid("Сначала уточните категорию: у заявок разные категории");
        work.merge((String) a.get("root_case_id"), (String) b.get("root_case_id"));
        db.update(
                "UPDATE shared_request_members SET request_id=?,confirmed_at=NULL WHERE request_id=?",
                target,
                source);
        db.update(
                "UPDATE shared_requests SET merged_into=?,revision=revision+1,updated_at=? WHERE id=?",
                target,
                now(),
                source);
        registry.history(
                source,
                user,
                SharedRequestRegistry.Action.MERGED,
                "Заявка объединена с другой. История и материалы сохранены: " + reason);
        registry.history(
                target,
                user,
                SharedRequestRegistry.Action.MERGED,
                "Присоединены обращения другой заявки. История и материалы сохранены: " + reason);
        db.update(
                "UPDATE resident_cases SET status='IN_REVIEW',next_step='Обращения объединены. Сотрудник уточняет общий план работ',revision=revision+1,updated_at=? WHERE id=?",
                now(),
                b.get("root_case_id"));
        registry.sync((String) b.get("root_case_id"), user);
        return get(target, user);
    }

    @Transactional
    public View split(String source, String caseId, int revision, String reason, String user) {
        return split(source, caseId, revision, reason, List.of(), user);
    }

    @Transactional
    public View split(
            String source,
            String caseId,
            int revision,
            String reason,
            List<SharedWorkRegistry.Allocation> allocation,
            String user) {
        var g = row(source);
        manage(g, revision, user);
        var links =
                db.queryForList(
                        "SELECT * FROM shared_request_members WHERE request_id=? AND case_id=? AND withdrawn_at IS NULL",
                        source,
                        caseId);
        if (links.isEmpty()) throw missing();
        if (registry.activeCases(source).size() < 2)
            throw invalid("В заявке осталось одно обращение");
        db.update("DELETE FROM shared_request_members WHERE case_id=?", caseId);
        registry.ensure(caseId, user);
        var created = registry.group(caseId);
        String newId = (String) created.get("id");
        work.allocate(
                (String) g.get("root_case_id"), (String) created.get("root_case_id"), allocation);
        db.update(
                "UPDATE resident_cases SET status='IN_REVIEW',next_step='Выделено отдельное обращение. Сотрудник проверяет проблему',revision=revision+1,updated_at=? WHERE id=?",
                now(),
                caseId);
        db.update(
                "UPDATE shared_requests SET category=?,location=?,location_features=?,status='IN_REVIEW',next_step='Сотрудник проверяет выделенную проблему' WHERE id=?",
                g.get("category"),
                g.get("location"),
                g.get("location_features"),
                newId);
        registry.history(
                source,
                user,
                SharedRequestRegistry.Action.SPLIT,
                "Одно обращение выделено в отдельную заявку: " + reason);
        registry.history(
                newId,
                user,
                SharedRequestRegistry.Action.SPLIT,
                "Обращение выделено из общей заявки: " + reason);
        db.update(
                "UPDATE shared_requests SET revision=revision+1,updated_at=? WHERE id=?",
                now(),
                source);
        registry.sync(caseId, user);
        return get(newId, user);
    }

    public List<View> queue(String house, String user) {
        if (!access.dispatcher(house, user)
                && !access.manager(house, user)
                && !access.staff(house, user)) throw missing();
        return db
                .query(
                        "SELECT id FROM shared_requests WHERE house_id=? AND merged_into IS NULL ORDER BY updated_at DESC",
                        (r, n) -> r.getString(1),
                        house)
                .stream()
                .filter(id -> canManage(row(id), user))
                .map(id -> get(id, user))
                .toList();
    }
}
