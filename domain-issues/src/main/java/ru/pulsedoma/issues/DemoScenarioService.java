package ru.pulsedoma.issues;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.duplicates.*;

/**
 * Explicit, repeatable fixtures for named demo houses. Never removes users, houses or their access.
 */
@Service
public class DemoScenarioService {
    private final JdbcTemplate db;
    private final SharedRequestRegistry registry;
    private final ObjectMapper mapper;

    public DemoScenarioService(
            JdbcTemplate db, SharedRequestRegistry registry, ObjectMapper mapper) {
        this.db = db;
        this.registry = registry;
        this.mapper = mapper;
    }

    private String uid(String house, String key) {
        return UUID.nameUUIDFromBytes(
                        ("pulse-final-v3:" + house + ":" + key)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    private String now() {
        return Instant.now().toString();
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void allowed(String house) {
        if (!house.equals("demo-house-1")
                && !db.queryForObject(
                                "SELECT count(*) FROM guided_demo_houses WHERE house_id=?",
                                Integer.class,
                                house)
                        .equals(1))
            throw new BusinessException(
                    "INVALID_CASE", "Обновлять можно только явно выделенный демонстрационный дом");
    }

    private void actor(String id, String name) {
        db.update(
                "INSERT OR IGNORE INTO users(id,display_name,status) VALUES (?,?,'ACTIVE')",
                id,
                name);
    }

    private void role(String house, String user, String role, String org) {
        db.update(
                "INSERT INTO house_memberships(house_id,user_id,role,verification_status,access_status,source_organization_id) VALUES (?,?,?,'VERIFIED','ACTIVE',?) ON CONFLICT(house_id,user_id,role) DO UPDATE SET access_status='ACTIVE',verification_status='VERIFIED'",
                house,
                user,
                role,
                org);
    }

    @Transactional
    public void refresh(String house, String resident, String dispatcher, String org) {
        allowed(house);
        if (house.equals("demo-house-1")) {
            for (String table : List.of("resident_cases"))
                if (db.queryForObject(
                                "SELECT count(*) FROM "
                                        + table
                                        + " WHERE house_id=? AND author_id NOT LIKE 'demo-%' AND author_id NOT LIKE 'scenario-%'",
                                Integer.class,
                                house)
                        > 0)
                    throw new BusinessException(
                            "INVALID_CASE",
                            "В демонстрационном доме есть неидентифицированные записи. Автоматическая очистка остановлена");
        }
        cleanup(house);
        String third = "scenario-" + uid(house, "third-resident");
        actor(third, "Ещё один сосед · демо");
        role(house, third, "RESIDENT", org);
        String neighbour = "scenario-" + uid(house, "neighbour"),
                worker = "scenario-" + uid(house, "worker");
        actor(neighbour, "Сосед · демо");
        actor(worker, "Инженер · демо");
        actor(dispatcher, "Диспетчер · демо");
        role(house, neighbour, "RESIDENT", org);
        role(house, dispatcher, "DISPATCHER", org);
        db.update(
                "INSERT INTO workflow_staff(house_id,user_id,function,organization_id,organization_label,authority,granted_by,active,created_at) VALUES (?,?,'ENGINEER',?,'Демонстрационная служба','Учебный сценарий',?,1,?) ON CONFLICT(house_id,user_id,function) DO UPDATE SET active=1",
                house,
                worker,
                org,
                dispatcher,
                now());
        db.update(
                "INSERT INTO workflow_staff(house_id,user_id,function,organization_id,organization_label,authority,granted_by,active,created_at) VALUES (?,?,'CONTRACTOR',?,'Демонстрационный подрядчик','Учебный сценарий',?,1,?) ON CONFLICT(house_id,user_id,function) DO UPDATE SET active=1",
                house,
                worker,
                org,
                dispatcher,
                now());
        String light =
                story(
                        house,
                        "light",
                        resident,
                        dispatcher,
                        ReportCategory.LIGHTING,
                        "Не горит свет в подъезде",
                        CaseStatus.IN_PROGRESS,
                        "Выберите удобное время осмотра",
                        "Подъезд 1, этаж 2");
        String lift =
                story(
                        house,
                        "lift",
                        neighbour,
                        dispatcher,
                        ReportCategory.ELEVATOR,
                        "Не работает пассажирский лифт",
                        CaseStatus.IN_PROGRESS,
                        "Мастер проверяет привод лифта",
                        "Подъезд 2");
        member(house, "lift-member", resident, dispatcher, lift, "Лифт не приезжает на этаж");
        member(house, "lift-third", third, dispatcher, lift, "Жители сообщили об остановке лифта");
        String leak =
                story(
                        house,
                        "leak",
                        resident,
                        dispatcher,
                        ReportCategory.WATER,
                        "После дождя мокнет стена возле окна",
                        CaseStatus.IN_PROGRESS,
                        "Гарантийная служба и подрядчик проверяют причину",
                        "Подъезд 1, этаж 4");
        db.update(
                "UPDATE resident_cases SET kind='WARRANTY' WHERE id IN (?,?)",
                leak,
                registry.root(leak));
        String done =
                story(
                        house,
                        "done",
                        resident,
                        dispatcher,
                        ReportCategory.ENTRANCE_CLEANING,
                        "Не убран подъезд",
                        CaseStatus.RESULT_PROPOSED,
                        "Уборка выполнена. Проверьте результат",
                        "Подъезд 3");
        member(house, "done-member", neighbour, dispatcher, done, "Мусор на лестнице");
        String reopened =
                story(
                        house,
                        "reopened",
                        resident,
                        dispatcher,
                        ReportCategory.YARD_CLEANING,
                        "Во дворе остался строительный мусор",
                        CaseStatus.REOPENED,
                        "Житель сообщил, что мусор убран не полностью. Повторная уборка назначена",
                        "Двор");
        event(
                reopened,
                resident,
                "REOPENED",
                "Демонстрация: житель сообщил, что проблема осталась");
        String payment =
                plain(
                        house,
                        "payment",
                        resident,
                        dispatcher,
                        "Почему выросла сумма за воду?",
                        CaseKind.PAYMENT,
                        CaseStatus.IN_REVIEW,
                        "Специалист проверяет основания начисления",
                        null);
        db.update(
                "INSERT INTO case_billing_permissions(house_id,user_id,granted_by,created_at,source_organization_id) VALUES (?,?,?,?,?) ON CONFLICT(house_id,user_id) DO UPDATE SET source_organization_id=excluded.source_organization_id",
                house,
                dispatcher,
                dispatcher,
                now(),
                org);
        for (String c : List.of(light, lift, leak, done, reopened)) {
            db.update(
                    "INSERT INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) VALUES (?,?,'ENGINEER',?,?,?) ON CONFLICT DO NOTHING",
                    registry.root(c),
                    worker,
                    dispatcher,
                    now(),
                    org);
        }
        String visit = uid(house, "light-visit"), slot = uid(house, "light-slot");
        Instant start =
                LocalDate.now(ZoneId.of("Europe/Moscow"))
                        .plusDays(1)
                        .atTime(14, 0)
                        .atZone(ZoneId.of("Europe/Moscow"))
                        .toInstant();
        db.update(
                "INSERT INTO case_visits(id,case_id,source_organization_id,purpose,worker_id,state,created_by,created_at,updated_at) VALUES (?,?,?,?,?,'OFFERED',?,?,?)",
                visit,
                light,
                org,
                "Осмотр освещения",
                worker,
                dispatcher,
                now(),
                now());
        db.update(
                "INSERT INTO visit_slots(id,visit_id,starts_at,ends_at) VALUES (?,?,?,?)",
                slot,
                visit,
                start.toString(),
                start.plusSeconds(7200).toString());
        String work = uid(house, "leak-work");
        db.update(
                "INSERT INTO case_work_orders(id,case_id,title,scope,assignee_id,basis_type,basis,due_at,due_basis,state,created_by,created_at,updated_at) VALUES (?,?,?,?,?,'WARRANTY','Демонстрационный договор',?,'Учебный план работ','IN_PROGRESS',?,?,?)",
                work,
                registry.root(leak),
                "Проверить герметичность оконного узла",
                "Обследование и устранение подтверждённого дефекта",
                worker,
                Instant.now().plusSeconds(86400 * 3).toString(),
                dispatcher,
                now(),
                now());
        String warranty = uid(house, "warranty");
        db.update(
                "INSERT INTO house_warranties(id,house_id,title,object_type,state,developer,contractor,contract_reference,basis,starts_on,ends_on,checked_by,checked_at,created_at,updated_at) VALUES (?,?,?,'CONSTRUCTION','VERIFIED','Демонстрационный застройщик','Демонстрационный подрядчик','ДЕМО-01','Учебный документ, не юридическое заключение',?,?,?,?,?,?)",
                warranty,
                house,
                "Оконные узлы · демонстрационный пример",
                LocalDate.now().minusYears(1).toString(),
                LocalDate.now().plusYears(2).toString(),
                dispatcher,
                now(),
                now(),
                now());
        db.update(
                "INSERT INTO case_warranty_reviews(id,case_id,warranty_id,outcome,basis,explanation,reviewed_by,created_at) VALUES (?,?,?,'POSSIBLE_WARRANTY','ДЕМО-01','Учебный пример: требуется осмотр',?,?)",
                uid(house, "warranty-review"),
                leak,
                warranty,
                dispatcher,
                now());
        for (int i = 0; i < 2; i++) {
            String tariff = uid(house, "water-tariff-" + i);
            LocalDate from = LocalDate.now().minusMonths(i == 0 ? 2 : 0).withDayOfMonth(1);
            LocalDate until = i == 0 ? LocalDate.now().withDayOfMonth(1).minusDays(1) : null;
            db.update(
                    "INSERT INTO house_tariffs(id,house_id,series_id,state,title,category,provider,applicability,unit,unit_label,amount,valid_from,valid_until,source_reference,explanation,edited_by,published_by,published_at,created_at,updated_at) VALUES (?,?,?,'PUBLISHED','Вода · учебный тариф','UTILITY','Демонстрационный поставщик','Только учебный пример','M3','м³',?,?,?,?,?,?,?,?,?,?)",
                    tariff,
                    house,
                    uid(house, "water-series"),
                    i == 0 ? "40.00" : "44.00",
                    from.toString(),
                    until == null ? null : until.toString(),
                    "ДЕМО-ТАРИФ-" + i,
                    "Условные значения для демонстрации. Не являются действующими тарифами",
                    dispatcher,
                    dispatcher,
                    now(),
                    now(),
                    now());
            db.update(
                    "INSERT INTO tariff_history(id,tariff_id,revision,actor_id,action,reason,snapshot_json,created_at) VALUES (?,?,0,?,'PUBLISHED','Учебный пример','{}',?)",
                    uid(house, "tariff-history-" + i),
                    tariff,
                    dispatcher,
                    now());
        }
        db.update(
                "INSERT INTO billing_questions(id,case_id,state,question,purpose,requested_by,created_at) VALUES (?,?,'OPEN','За какой месяц вы заметили увеличение?','Чтобы сравнить объём и учебные тарифы. Можно ответить, что квитанции нет',?,?)",
                uid(house, "billing-question"),
                payment,
                dispatcher,
                now());
        String channel = uid(house, "channel"), packet = uid(house, "packet");
        db.update(
                "INSERT INTO exchange_channels(id,house_id,source_organization_id,organization_name,participant_type,mode,contact,source_reference,disclosure_scope,state,checked_by,checked_at,created_at) VALUES (?,?,?,'Гарантийная служба · учебный пример','WARRANTY','PORTAL','Учебный кабинет получателя','ДЕМО-КАНАЛ','Описание дефекта без личных документов','VERIFIED',?,?,?)",
                channel,
                house,
                org,
                dispatcher,
                now(),
                now());
        String snapshot =
                json(db.queryForMap("SELECT * FROM exchange_channels WHERE id=?", channel));
        db.update(
                "INSERT INTO exchange_packets(id,case_id,channel_id,channel_snapshot,source_organization_id,subject,statement,disclosure_basis,authority,materials_note,include_address,state,due_basis,created_by,created_at,updated_at) VALUES (?,?,?,?,?,'Проверка оконного узла · учебный пример','После дождя мокнет стена возле окна. Просим предложить обследование','Учебный сценарий обмена между участниками','Демонстрационные полномочия','Только описание дефекта; личных файлов нет',0,'SENT','Срок уточняется',?,?,?)",
                packet,
                registry.root(leak),
                channel,
                snapshot,
                org,
                dispatcher,
                now(),
                now());
        if (house.equals("demo-house-1"))
            db.update(
                    "INSERT INTO exchange_invitations(id,packet_id,token_hash,recipient_name,participation_basis,expires_at,created_by,created_at,accepted_by) VALUES (?,?,?,'Получатель · демо','Изолированный учебный доступ',?,?,?,'demo-recipient-1')",
                    uid(house, "recipient-invitation"),
                    packet,
                    uid(house, "unavailable-demo-token"),
                    Instant.now().plusSeconds(86400 * 30).toString(),
                    dispatcher,
                    now());
        db.update(
                "INSERT INTO exchange_history(id,packet_id,action,note,actor_id,created_at,snapshot_json) VALUES (?,?,'PACKET_SENT','Учебный пакет доступен отдельному получателю',?,?,'{}')",
                uid(house, "packet-history"),
                packet,
                dispatcher,
                now());
        String poll = uid(house, "poll");
        db.update(
                "INSERT INTO polls(id,house_id,question,is_official,results_hidden_until_close,closes_at,created_at) VALUES (?,?,?,0,0,?,?)",
                poll,
                house,
                "Что улучшить во дворе?",
                Instant.now().plusSeconds(86400 * 7).toString(),
                now());
        for (int i = 0; i < 3; i++)
            db.update(
                    "INSERT INTO poll_options(id,poll_id,label,position) VALUES (?,?,?,?)",
                    uid(house, "poll-" + i),
                    poll,
                    List.of("Освещение", "Озеленение", "Велопарковка").get(i),
                    i);
        db.update(
                "INSERT INTO house_operations_notices(id,house_id,source_organization_id,title,kind,resource,scope,summary,advice,contact,source_reference,starts_at,ends_at,end_basis,state,published_at,created_by,created_at,updated_at) VALUES (?,?,?,'Плановое отключение горячей воды','OUTAGE','HOT_WATER','Весь дом','Учебный пример плановых работ','Подготовьте запас воды','Демонстрационная диспетчерская','ДЕМО-УВЕДОМЛЕНИЕ',?,?,'Время учебного сценария','PUBLISHED',?,?,?,?)",
                uid(house, "notice"),
                house,
                org,
                start.toString(),
                start.plusSeconds(14400).toString(),
                now(),
                dispatcher,
                now(),
                now());
        db.update(
                "INSERT INTO demo_scenario_sets(house_id,version,refreshed_at) VALUES (?,'independent-v4',?) ON CONFLICT(house_id) DO UPDATE SET version=excluded.version,refreshed_at=excluded.refreshed_at",
                house,
                now());
    }

    private String plain(
            String house,
            String key,
            String author,
            String dispatcher,
            String text,
            CaseKind kind,
            CaseStatus status,
            String next,
            String location) {
        String id = uid(house, key);
        db.update(
                "INSERT INTO resident_cases(id,house_id,author_id,kind,status,description,location,client_request_id,request_hash,responsible_user_id,next_step,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                house,
                author,
                kind.name(),
                status.name(),
                text,
                location,
                "scenario:" + id,
                "scenario:" + id,
                dispatcher,
                next,
                now(),
                now());
        event(id, author, "REGISTERED", "Демонстрационный пример: обращение зарегистрировано");
        return id;
    }

    private void event(String id, String actor, String type, String text) {
        db.update(
                "INSERT INTO case_events(id,case_id,actor_id,type,text,created_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                id,
                actor,
                type,
                text,
                now());
    }

    private String story(
            String house,
            String key,
            String author,
            String dispatcher,
            ReportCategory category,
            String text,
            CaseStatus status,
            String next,
            String location) {
        String id =
                plain(
                        house,
                        key,
                        author,
                        dispatcher,
                        text,
                        CaseKind.PROBLEM,
                        status,
                        next,
                        location);
        registry.ensure(id, author);
        String features = null;
        if (category == ReportCategory.LIGHTING)
            features =
                    json(
                            new LocationFeatures(
                                    LocationFeatures.Area.ENTRANCE,
                                    1,
                                    2,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null));
        if (category == ReportCategory.ELEVATOR)
            features =
                    json(
                            new LocationFeatures(
                                    LocationFeatures.Area.ENTRANCE,
                                    2,
                                    null,
                                    LocationFeatures.LiftType.PASSENGER,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null));
        db.update(
                "INSERT INTO case_problem_details(case_id,category,location_features) VALUES (?,?,?)",
                id,
                category.name(),
                features);
        registry.configure(id, category, features, location);
        registry.sync(id, dispatcher);
        return id;
    }

    private void member(
            String house, String key, String author, String dispatcher, String root, String text) {
        String id =
                plain(
                        house,
                        key,
                        author,
                        dispatcher,
                        text,
                        CaseKind.PROBLEM,
                        CaseStatus.IN_PROGRESS,
                        "Общая работа выполняется",
                        null);
        db.update(
                "INSERT INTO shared_request_members(case_id,request_id,user_id,joined_at) VALUES (?,?,?,?)",
                id,
                registry.group(root).get("id"),
                author,
                now());
        db.update(
                "INSERT INTO case_problem_details(case_id,category) SELECT ?,category FROM shared_requests WHERE id=?",
                id,
                registry.group(root).get("id"));
        var g =
                db.queryForMap(
                        "SELECT * FROM shared_requests WHERE id=?", registry.group(root).get("id"));
        db.update(
                "UPDATE resident_cases SET status=?,next_step=? WHERE id=?",
                g.get("status"),
                g.get("next_step"),
                id);
        registry.history(
                (String) registry.group(root).get("id"),
                author,
                SharedRequestRegistry.Action.JOINED,
                "Житель присоединился к той же проблеме");
    }

    @Transactional
    public void resetAllBusinessData() {
        for (String house : db.query("SELECT id FROM houses", (r, n) -> r.getString(1)))
            cleanup(house);
        for (String table :
                List.of(
                        "audit_events",
                        "max_bot_outbox",
                        "max_bot_draft_files",
                        "max_bot_sessions",
                        "max_bot_problem_categories",
                        "max_bot_problem_matches",
                        "operations_notifications")) db.update("DELETE FROM " + table);
    }

    private void cleanup(String house) {
        // Compute a foreign-key closure scoped to demo business records. Identity and access tables
        // are never roots.
        List<String> tables =
                db.query(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '%fts%' AND name<>'flyway_schema_history' AND sql NOT LIKE '%VIRTUAL TABLE%'",
                        (r, n) -> r.getString(1));
        Map<String, Set<Long>> selected = new LinkedHashMap<>();
        for (String t : tables) selected.put(t, new HashSet<>());
        for (String t :
                List.of(
                        "resident_cases",
                        "polls",
                        "house_warranties",
                        "house_tariffs",
                        "house_mass_problems",
                        "house_operations_notices",
                        "house_events",
                        "announcements",
                        "operations_history",
                        "exchange_channels",
                        "demo_scenario_sets"))
            if (selected.containsKey(t))
                selected.get(t)
                        .addAll(
                                db.query(
                                        "SELECT rowid FROM " + t + " WHERE house_id=?",
                                        (r, n) -> r.getLong(1),
                                        house));
        boolean changed;
        do {
            changed = false;
            for (String child : tables)
                for (var fk : db.queryForList("PRAGMA foreign_key_list('" + child + "')")) {
                    String parent = (String) fk.get("table"),
                            from = (String) fk.get("from"),
                            to = (String) fk.get("to");
                    var ids = selected.get(parent);
                    if (ids == null || ids.isEmpty()) continue;
                    if (to == null) to = "id";
                    for (Long row : ids) {
                        var children =
                                db.query(
                                        "SELECT rowid FROM "
                                                + child
                                                + " WHERE \""
                                                + from
                                                + "\" IN (SELECT \""
                                                + to
                                                + "\" FROM "
                                                + parent
                                                + " WHERE rowid=?)",
                                        (r, n) -> r.getLong(1),
                                        row);
                        if (selected.get(child).addAll(children)) changed = true;
                    }
                }
        } while (changed);
        for (String t :
                List.of(
                        "users",
                        "houses",
                        "house_memberships",
                        "organizations",
                        "organization_memberships",
                        "organization_houses",
                        "uk_admin_houses",
                        "guided_demo_sessions",
                        "guided_demo_houses",
                        "guided_demo_organizations"))
            if (selected.containsKey(t) && !selected.get(t).isEmpty())
                throw new IllegalStateException("Demo cleanup reached protected identities: " + t);
        var visits = selected.getOrDefault("case_visits", Set.of());
        for (long r : visits)
            db.update("UPDATE case_visits SET selected_slot_id=NULL WHERE rowid=?", r);
        int remaining = selected.values().stream().mapToInt(Set::size).sum();
        while (remaining > 0) {
            int before = remaining;
            for (String t : tables) {
                var ids = selected.get(t);
                if (ids.isEmpty()) continue;
                String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
                try {
                    db.update(
                            "DELETE FROM " + t + " WHERE rowid IN (" + marks + ")", ids.toArray());
                    remaining -= ids.size();
                    ids.clear();
                } catch (org.springframework.dao.DataAccessException e) {
                    if (!(e.getRootCause() instanceof java.sql.SQLException sql
                            && sql.getErrorCode() == 19
                            && sql.getMessage().contains("FOREIGNKEY")))
                        throw e; /* children are removed first */
                }
            }
            if (before == remaining)
                throw new IllegalStateException("Unresolved demo foreign keys; no reset committed");
        }
    }
}
