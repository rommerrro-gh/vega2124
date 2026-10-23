package ru.pulsedoma.issues;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.duplicates.TextNormalizer;

/** One public problem; every member case retains its own files, messages and visits. */
@Service
public class SharedRequestRegistry {
    public enum Action {
        CREATED,
        JOINED,
        UPDATED,
        CONFIRMED,
        REOPENED,
        WITHDRAWN,
        MERGED,
        SPLIT,
        CLASSIFIED
    }

    private final JdbcTemplate db;
    private final TextNormalizer normalizer;
    private final CaseBotNotifications bot;

    public SharedRequestRegistry(
            JdbcTemplate db, TextNormalizer normalizer, CaseBotNotifications bot) {
        this.db = db;
        this.normalizer = normalizer;
        this.bot = bot;
    }

    private String now() {
        return Instant.now().toString();
    }

    public Map<String, Object> group(String caseId) {
        var rows =
                db.queryForList(
                        "SELECT DISTINCT s.* FROM shared_requests s LEFT JOIN shared_request_members m ON s.id=m.request_id WHERE m.case_id=? OR s.root_case_id=?",
                        caseId,
                        caseId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public String root(String caseId) {
        var g = group(caseId);
        return g == null ? caseId : (String) g.get("root_case_id");
    }

    public boolean withdrawn(String caseId) {
        return db.queryForObject(
                        "SELECT count(*) FROM shared_request_members WHERE case_id=? AND withdrawn_at IS NOT NULL",
                        Integer.class,
                        caseId)
                > 0;
    }

    public boolean confirmed(String caseId) {
        return db.queryForObject(
                        "SELECT count(*) FROM shared_request_members WHERE case_id=? AND confirmed_at IS NOT NULL",
                        Integer.class,
                        caseId)
                > 0;
    }

    public void history(String group, String actor, Action action, String note) {
        db.update(
                "INSERT INTO shared_request_history(id,request_id,actor_id,action,note,created_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                group,
                actor,
                action.name(),
                note,
                now());
    }

    public void ensure(String caseId, String actor) {
        if (group(caseId) != null) return;
        var c = db.queryForMap("SELECT * FROM resident_cases WHERE id=?", caseId);
        if ("PAYMENT".equals(c.get("kind"))) return;
        String group = UUID.randomUUID().toString(), work = UUID.randomUUID().toString();
        db.update(
                "INSERT INTO resident_cases(id,house_id,author_id,kind,status,description,location,client_request_id,request_hash,next_step,created_at,updated_at,internal_work) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1)",
                work,
                c.get("house_id"),
                actor,
                c.get("kind"),
                c.get("status"),
                c.get("description"),
                c.get("location"),
                "shared-work:" + work,
                "shared-work:" + work,
                c.get("next_step"),
                now(),
                now());
        db.update(
                "INSERT INTO shared_requests(id,house_id,root_case_id,title,category,location,normalized_text,status,next_step,due_at,responsible_user_id,created_at,updated_at) VALUES (?,?,?,?,'OTHER',?,?,?,?,?,?,?,?)",
                group,
                c.get("house_id"),
                work,
                ((String) c.get("description"))
                        .substring(0, Math.min(160, ((String) c.get("description")).length())),
                c.get("location"),
                normalizer.normalize((String) c.get("description")),
                c.get("status"),
                c.get("next_step"),
                c.get("due_at"),
                c.get("responsible_user_id"),
                now(),
                now());
        db.update(
                "INSERT INTO shared_request_members(case_id,request_id,user_id,joined_at) VALUES (?,?,?,?)",
                caseId,
                group,
                c.get("author_id"),
                now());
        history(group, actor, Action.CREATED, "Зарегистрирована общая заявка");
    }

    public void configure(
            String caseId, ReportCategory category, String features, String location) {
        var g = group(caseId);
        if (g == null) return;
        db.update(
                "UPDATE shared_requests SET category=?,location_features=?,location=?,updated_at=? WHERE id=?",
                category.name(),
                features,
                location,
                now(),
                g.get("id"));
    }

    public void active(String caseId) {
        if (withdrawn(caseId))
            throw new BusinessException(
                    "INVALID_CASE",
                    "Обращение отозвано. Можно создать новое или присоединиться к действующей заявке");
    }

    public List<String> allCases(String group) {
        return db.query(
                "SELECT case_id FROM shared_request_members WHERE request_id=?",
                (r, n) -> r.getString(1),
                group);
    }

    public List<String> activeCases(String group) {
        return db.query(
                "SELECT case_id FROM shared_request_members WHERE request_id=? AND withdrawn_at IS NULL",
                (r, n) -> r.getString(1),
                group);
    }

    public int participants(String group) {
        return db.queryForObject(
                "SELECT count(DISTINCT user_id) FROM shared_request_members WHERE request_id=? AND withdrawn_at IS NULL",
                Integer.class,
                group);
    }

    public void sync(String source, String actor) {
        var g = group(source);
        if (g == null) return;
        var c =
                db.queryForMap(
                        "SELECT kind,status,next_step,due_at,responsible_user_id FROM resident_cases WHERE id=?",
                        source);
        String group = (String) g.get("id"), root = (String) g.get("root_case_id");
        for (String id : activeCases(group))
            if (!id.equals(source)) {
                db.update(
                        "UPDATE resident_cases SET kind=?,status=?,next_step=?,due_at=?,responsible_user_id=?,revision=revision+1,updated_at=? WHERE id=?",
                        c.get("kind"),
                        c.get("status"),
                        c.get("next_step"),
                        c.get("due_at"),
                        c.get("responsible_user_id"),
                        now(),
                        id);
                db.update(
                        "INSERT INTO case_events(id,case_id,actor_id,type,text,created_at) VALUES (?,?,?,'UPDATED',?,?)",
                        UUID.randomUUID().toString(),
                        id,
                        actor,
                        "Общая заявка: " + c.get("next_step"),
                        now());
                bot.enqueue(id, actor, CaseEventType.UPDATED);
            }
        if (!activeCases(group).contains(root))
            db.update(
                    "UPDATE resident_cases SET kind=?,status=?,next_step=?,due_at=?,responsible_user_id=?,revision=revision+1,updated_at=? WHERE id=?",
                    c.get("kind"),
                    c.get("status"),
                    c.get("next_step"),
                    c.get("due_at"),
                    c.get("responsible_user_id"),
                    now(),
                    root);
        if (!"RESULT_PROPOSED".equals(c.get("status")))
            db.update(
                    "UPDATE shared_request_members SET confirmed_at=NULL WHERE request_id=? AND withdrawn_at IS NULL",
                    group);
        db.update(
                "UPDATE shared_requests SET status=?,next_step=?,due_at=?,responsible_user_id=?,revision=revision+1,updated_at=? WHERE id=?",
                c.get("status"),
                c.get("next_step"),
                c.get("due_at"),
                c.get("responsible_user_id"),
                now(),
                group);
        history(group, actor, Action.UPDATED, "Следующий шаг: " + c.get("next_step"));
    }

    public boolean respond(
            String caseId,
            boolean yes,
            String comment,
            int revision,
            String actor,
            CaseCompletionGuard completion) {
        var g = group(caseId);
        if (g == null) return false;
        active(caseId);
        var c = db.queryForMap("SELECT * FROM resident_cases WHERE id=?", caseId);
        if (((Number) c.get("revision")).intValue() != revision)
            throw new BusinessException(
                    "CASE_CONFLICT", "Данные изменились. Обновите заявку перед ответом");
        String group = (String) g.get("id"), root = (String) g.get("root_case_id");
        if (yes) {
            if (confirmed(caseId) || !"RESULT_PROPOSED".equals(c.get("status")))
                throw new BusinessException(
                        "INVALID_CASE", "Результат ещё не предложен или уже подтверждён");
            for (String id : allCases(group)) completion.requireComplete(id);
            if (!activeCases(group).contains(root)) completion.requireComplete(root);
            db.update(
                    "UPDATE shared_request_members SET confirmed_at=? WHERE request_id=? AND user_id=? AND withdrawn_at IS NULL",
                    now(),
                    group,
                    actor);
            for (String own :
                    db.query(
                            "SELECT case_id FROM shared_request_members WHERE request_id=? AND user_id=? AND withdrawn_at IS NULL",
                            (rs, n) -> rs.getString(1),
                            group,
                            actor)) {
                if (!own.equals(root))
                    db.update(
                            "UPDATE resident_cases SET status='CLOSED',next_step='Вы подтвердили результат',revision=revision+1,updated_at=? WHERE id=?",
                            now(),
                            own);
                else
                    db.update(
                            "UPDATE resident_cases SET revision=revision+1,updated_at=? WHERE id=?",
                            now(),
                            own);
            }
            if (db.queryForObject(
                            "SELECT count(*) FROM shared_request_members WHERE request_id=? AND withdrawn_at IS NULL AND confirmed_at IS NULL",
                            Integer.class,
                            group)
                    == 0)
                db.update(
                        "UPDATE resident_cases SET status='CLOSED',next_step='Все участники подтвердили результат',revision=revision+1,updated_at=? WHERE id=?",
                        now(),
                        root);
            history(group, actor, Action.CONFIRMED, "Участник подтвердил результат");
        } else {
            if (!List.of("RESULT_PROPOSED", "CLOSED").contains((String) c.get("status")))
                throw new BusinessException(
                        "INVALID_CASE", "Повторное рассмотрение пока не требуется");
            db.update(
                    "UPDATE shared_request_members SET confirmed_at=NULL WHERE request_id=? AND withdrawn_at IS NULL",
                    group);
            for (String id : activeCases(group)) {
                db.update(
                        "UPDATE resident_cases SET status='REOPENED',next_step='Участник сообщил, что проблема осталась. Нужна повторная проверка',revision=revision+1,updated_at=? WHERE id=?",
                        now(),
                        id);
                if (!id.equals(caseId)) {
                    db.update(
                            "INSERT INTO case_events(id,case_id,actor_id,type,text,created_at) VALUES (?,?,?,'REOPENED','Общая проблема осталась. Работа возобновлена',?)",
                            UUID.randomUUID().toString(),
                            id,
                            actor,
                            now());
                    bot.enqueue(id, actor, CaseEventType.UPDATED);
                }
            }
            if (!activeCases(group).contains(root))
                db.update(
                        "UPDATE resident_cases SET status='REOPENED',next_step='Нужна повторная проверка общей проблемы',revision=revision+1,updated_at=? WHERE id=?",
                        now(),
                        root);
            history(
                    group,
                    actor,
                    Action.REOPENED,
                    "Участник сообщил, что проблема осталась. Работа возобновлена");
        }
        var work = db.queryForMap("SELECT status,next_step FROM resident_cases WHERE id=?", root);
        db.update(
                "UPDATE shared_requests SET status=?,next_step=?,revision=revision+1,updated_at=? WHERE id=?",
                work.get("status"),
                work.get("next_step"),
                now(),
                group);
        return true;
    }
}
