package ru.pulsedoma.issues;

import static ru.pulsedoma.issues.BillingTypes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

@Service
public class BillingCaseService {
    private final JdbcTemplate jdbc;
    private final ResidentCaseService cases;
    private final CaseWorkAccess access;
    private final HouseTariffService tariffs;
    private final ObjectMapper mapper;

    public BillingCaseService(
            JdbcTemplate jdbc,
            ResidentCaseService cases,
            CaseWorkAccess access,
            HouseTariffService tariffs,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.access = access;
        this.tariffs = tariffs;
        this.mapper = mapper;
    }

    public record Workspace(
            int revision,
            boolean canWork,
            boolean isAuthor,
            List<Map<String, Object>> reviews,
            List<Map<String, Object>> questions,
            List<Map<String, Object>> corrections,
            List<Map<String, Object>> history) {}

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String s(Map<String, Object> r, String key) {
        return Objects.toString(r.get(key), null);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String decimal(BigDecimal d) {
        return d == null ? null : d.toPlainString();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_BILLING", message);
    }

    private static BusinessException missing() {
        return new BusinessException("CASE_NOT_FOUND", "Финансовые сведения недоступны");
    }

    private static BusinessException conflict() {
        return new BusinessException("CASE_CONFLICT", "Обращение изменилось. Обновите проверку");
    }

    private Map<String, Object> row(String table, String id) {
        var rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE id=?", id);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    private ResidentCaseService.CaseView payment(String id, String user) {
        var c = cases.get(id, user);
        if (c.kind() != CaseKind.PAYMENT || !c.fullAccess()) throw missing();
        return c;
    }

    private ResidentCaseService.CaseView staff(String id, String user, int revision) {
        var c = payment(id, user);
        if (c.isAuthor() || !access.billing(c.houseId(), user)) throw missing();
        if (c.status() == CaseStatus.CLOSED)
            throw invalid("Для продолжения житель должен запросить повторное рассмотрение");
        if (c.revision() != revision) throw conflict();
        return c;
    }

    private int cycle(String id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM case_events WHERE case_id=? AND type='REOPENED'",
                Integer.class,
                id);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void history(
            String caseId,
            String entity,
            Action action,
            String note,
            String user,
            Object snapshot) {
        jdbc.update(
                "INSERT INTO billing_history(id,case_id,entity_id,action,note,actor_id,snapshot_json,created_at) VALUES (?,?,?,?,?,?,?,?)",
                id(),
                caseId,
                entity,
                action.name(),
                note,
                user,
                json(snapshot),
                now());
    }

    private void evidence(String version, String caseId) {
        if (optional(version) != null
                && !access.exists(
                        "SELECT count(*) FROM case_document_versions v JOIN case_documents d ON d.id=v.document_id WHERE v.id=? AND d.case_id=?",
                        version,
                        caseId)) throw invalid("Выберите документ из этого обращения");
    }

    private Map<String, Object> reviewSnapshot(String id) {
        var r = new LinkedHashMap<>(row("billing_reviews", id));
        r.put(
                "lines",
                jdbc.queryForList(
                        "SELECT * FROM billing_review_lines WHERE review_id=? ORDER BY position",
                        id));
        return r;
    }

    public record QueueItem(
            ResidentCaseService.CaseView item,
            int openQuestions,
            int openCorrections,
            int overdueCorrections) {}

    public List<QueueItem> queue(String house, String user) {
        if (!access.billing(house, user)) throw missing();
        return cases.queue(house, user).stream()
                .filter(c -> c.kind() == CaseKind.PAYMENT)
                .map(
                        c ->
                                new QueueItem(
                                        c,
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM billing_questions WHERE case_id=? AND state='OPEN'",
                                                Integer.class,
                                                c.id()),
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM billing_corrections WHERE case_id=? AND state NOT IN ('VERIFIED','REFUSAL_REVIEWED','CANCELLED')",
                                                Integer.class,
                                                c.id()),
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM billing_corrections WHERE case_id=? AND state NOT IN ('VERIFIED','REFUSAL_REVIEWED','CANCELLED') AND due_at IS NOT NULL AND datetime(due_at)<datetime('now')",
                                                Integer.class,
                                                c.id())))
                .toList();
    }

    public Workspace workspace(String caseId, String user) {
        var c = payment(caseId, user);
        boolean work = !c.isAuthor() && access.billing(c.houseId(), user);
        var reviews =
                jdbc
                        .queryForList(
                                "SELECT * FROM billing_reviews WHERE case_id=? "
                                        + (work ? "" : "AND published_at IS NOT NULL ")
                                        + "ORDER BY rowid DESC",
                                caseId)
                        .stream()
                        .map(
                                r -> {
                                    var result = new LinkedHashMap<>(r);
                                    result.put(
                                            "lines",
                                            jdbc.queryForList(
                                                    "SELECT * FROM billing_review_lines WHERE review_id=? ORDER BY position",
                                                    r.get("id")));
                                    if (!work) {
                                        result.remove("edited_by");
                                        result.remove("published_by");
                                    }
                                    return (Map<String, Object>) result;
                                })
                        .toList();
        var questions =
                jdbc.queryForList(
                        "SELECT * FROM billing_questions WHERE case_id=? ORDER BY rowid DESC",
                        caseId);
        var corrections =
                jdbc.queryForList(
                        "SELECT * FROM billing_corrections WHERE case_id=? "
                                + (work ? "" : "AND state NOT IN ('DRAFT','CANCELLED') ")
                                + "ORDER BY rowid DESC",
                        caseId);
        var history =
                work
                        ? jdbc.queryForList(
                                "SELECT h.*,u.display_name AS actor FROM billing_history h JOIN users u ON u.id=h.actor_id WHERE h.case_id=? ORDER BY h.rowid",
                                caseId)
                        : List.<Map<String, Object>>of();
        return new Workspace(
                c.revision(),
                work && c.status() != CaseStatus.CLOSED,
                c.isAuthor(),
                reviews,
                questions,
                corrections,
                history);
    }

    @Transactional
    public Workspace saveReview(
            String caseId, String reviewId, BillingRequests.Review r, String user) {
        var c = staff(caseId, user, r.revision());
        evidence(r.evidenceVersionId(), caseId);
        String target = reviewId == null ? id() : reviewId;
        if (reviewId == null) {
            jdbc.update(
                    "INSERT INTO billing_reviews(id,case_id,cycle,state,period,outcome,findings,explanation,limitations,source_reference,evidence_version_id,edited_by,created_at) VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?)",
                    target,
                    caseId,
                    cycle(caseId),
                    r.period(),
                    r.outcome().name(),
                    r.findings(),
                    r.explanation(),
                    r.limitations(),
                    r.sourceReference(),
                    optional(r.evidenceVersionId()),
                    user,
                    now());
        } else {
            var old = row("billing_reviews", target);
            if (!caseId.equals(s(old, "case_id"))) throw missing();
            if (!"DRAFT".equals(s(old, "state"))
                    || ((Number) old.get("cycle")).intValue() != cycle(caseId))
                throw invalid(
                        "Опубликованную или прежнюю проверку нельзя изменить. Создайте новую");
            jdbc.update(
                    "UPDATE billing_reviews SET period=?,outcome=?,findings=?,explanation=?,limitations=?,source_reference=?,evidence_version_id=?,edited_by=? WHERE id=?",
                    r.period(),
                    r.outcome().name(),
                    r.findings(),
                    r.explanation(),
                    r.limitations(),
                    r.sourceReference(),
                    optional(r.evidenceVersionId()),
                    user,
                    target);
            jdbc.update("DELETE FROM billing_review_lines WHERE review_id=?", target);
        }
        int position = 0;
        for (var line : r.lines()) {
            evidence(line.evidenceVersionId(), caseId);
            BigDecimal expected = null, difference = null;
            if (line.mode() == Calculation.SIMPLE_PRODUCT) {
                if (line.quantity() == null || line.rate() == null)
                    throw invalid(
                            "Для простой проверки нужны объём и ставка. Если они неизвестны, выберите ручной разбор");
                expected =
                        line.quantity()
                                .multiply(line.rate())
                                .add(line.adjustment())
                                .setScale(2, RoundingMode.HALF_UP);
                difference = expected.subtract(line.charged());
            } else if (line.quantity() != null
                    || line.rate() != null
                    || line.adjustment().signum() != 0
                    || optional(line.tariffId()) != null)
                throw invalid(
                        "Для ручного разбора оставьте объём, ставку и связь с тарифом пустыми; поправка — 0");
            String tariff = null;
            if (optional(line.tariffId()) != null) {
                var rate = tariff(line.tariffId(), line.period(), c.houseId(), user);
                if (line.rate() == null
                        || rate.amount().compareTo(line.rate()) != 0
                        || !rate.unitLabel().equals(line.unit()))
                    throw invalid("Ставка и единица должны совпадать с выбранной публикацией");
                tariff = json(rate);
            }
            jdbc.update(
                    "INSERT INTO billing_review_lines(review_id,position,title,period,unit,mode,charged,quantity,rate,adjustment,expected,difference,source_reference,evidence_version_id,tariff_id,tariff_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    target,
                    ++position,
                    line.title(),
                    line.period(),
                    line.unit(),
                    line.mode().name(),
                    decimal(line.charged()),
                    decimal(line.quantity()),
                    decimal(line.rate()),
                    decimal(line.adjustment()),
                    decimal(expected),
                    decimal(difference),
                    line.sourceReference(),
                    optional(line.evidenceVersionId()),
                    optional(line.tariffId()),
                    tariff);
        }
        history(caseId, target, Action.REVIEW_DRAFTED, r.reason(), user, reviewSnapshot(target));
        cases.workflowEvent(
                caseId,
                user,
                r.revision(),
                "Специалист готовит проверку начисления. Черновой вывод ещё не опубликован.",
                "Специалист проверяет сведения по начислению");
        syncQuestions(caseId);
        return workspace(caseId, user);
    }

    private HouseTariffService.Rate tariff(String id, String period, String house, String user) {
        var month = YearMonth.parse(period);
        var rate =
                tariffs.catalogue(house, month.atDay(1), user).rates().stream()
                        .filter(t -> t.id().equals(id))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        invalid(
                                                "Нет подтверждённой ставки этого дома на начало периода. Укажите ручное основание"));
        if (rate.effectiveUntil() != null
                && LocalDate.parse(rate.effectiveUntil()).isBefore(month.atEndOfMonth()))
            throw invalid("Ставка действует не весь месяц. Разделите расчёт в ручном пояснении");
        return rate;
    }

    @Transactional
    public Workspace publishReview(String target, BillingRequests.Decision r, String user) {
        var old = row("billing_reviews", target);
        String caseId = s(old, "case_id");
        var c = staff(caseId, user, r.revision());
        if (!"DRAFT".equals(s(old, "state"))
                || ((Number) old.get("cycle")).intValue() != cycle(caseId))
            throw invalid("Можно опубликовать только черновик текущего рассмотрения");
        for (var line :
                jdbc.queryForList("SELECT * FROM billing_review_lines WHERE review_id=?", target))
            if (line.get("tariff_id") != null) {
                var current = tariff(s(line, "tariff_id"), s(line, "period"), c.houseId(), user);
                try {
                    var snapshot = mapper.readTree(s(line, "tariff_snapshot"));
                    if (current.revision() != snapshot.path("revision").asInt())
                        throw invalid("Источник тарифа изменился. Обновите строку проверки");
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
        jdbc.update(
                "UPDATE billing_reviews SET state='SUPERSEDED' WHERE case_id=? AND state='PUBLISHED'",
                caseId);
        jdbc.update(
                "UPDATE billing_reviews SET state='PUBLISHED',published_by=?,published_at=? WHERE id=?",
                user,
                now(),
                target);
        history(caseId, target, Action.REVIEW_PUBLISHED, r.note(), user, reviewSnapshot(target));
        cases.workflowEvent(
                caseId,
                user,
                r.revision(),
                "Опубликована проверка начисления. " + s(old, "explanation"),
                "Посмотрите объяснение проверки. При необходимости сотрудник продолжит работу");
        syncQuestions(caseId);
        return workspace(caseId, user);
    }

    @Transactional
    public Workspace cancelReview(String target, BillingRequests.Decision r, String user) {
        var old = row("billing_reviews", target);
        String caseId = s(old, "case_id");
        staff(caseId, user, r.revision());
        if (!"DRAFT".equals(s(old, "state"))) throw invalid("Отменить можно только черновик");
        jdbc.update("UPDATE billing_reviews SET state='CANCELLED' WHERE id=?", target);
        history(caseId, target, Action.REVIEW_CANCELLED, r.note(), user, reviewSnapshot(target));
        cases.workflowEvent(
                caseId, user, r.revision(), "Черновик проверки отменён: " + r.note(), null);
        syncQuestions(caseId);
        return workspace(caseId, user);
    }

    private void syncQuestions(String caseId) {
        if (access.exists(
                "SELECT count(*) FROM billing_questions WHERE case_id=? AND state='OPEN'", caseId))
            jdbc.update(
                    "UPDATE resident_cases SET status='WAITING_RESIDENT',next_step='Нужен ваш ответ на уточнение. Можно сообщить, что сведений нет' WHERE id=?",
                    caseId);
    }

    public record QuestionPrompt(String id, String caseId, String question, String purpose) {}

    public QuestionPrompt question(String id, String user) {
        var r = row("billing_questions", id);
        var c = payment(s(r, "case_id"), user);
        if (!c.isAuthor() || !"OPEN".equals(s(r, "state"))) throw missing();
        return new QuestionPrompt(id, c.id(), s(r, "question"), s(r, "purpose"));
    }

    @Transactional
    public Workspace ask(String caseId, BillingRequests.Question r, String user) {
        staff(caseId, user, r.revision());
        String target = id();
        jdbc.update(
                "INSERT INTO billing_questions(id,case_id,state,question,purpose,requested_by,created_at) VALUES (?,?,'OPEN',?,?,?,?)",
                target,
                caseId,
                r.question(),
                r.purpose(),
                user,
                now());
        history(
                caseId,
                target,
                Action.QUESTION_ASKED,
                r.question(),
                user,
                row("billing_questions", target));
        cases.workflowEvent(
                caseId,
                user,
                r.revision(),
                "Вопрос сотрудника: " + r.question() + "\nЗачем: " + r.purpose(),
                "Нужен ваш ответ. Если сведений нет, так и напишите");
        jdbc.update("UPDATE resident_cases SET status='WAITING_RESIDENT' WHERE id=?", caseId);
        syncQuestions(caseId);
        return workspace(caseId, user);
    }

    @Transactional
    public Workspace answer(String target, BillingRequests.Answer r, String user) {
        var old = row("billing_questions", target);
        String caseId = s(old, "case_id");
        var c = payment(caseId, user);
        if (!c.isAuthor()) throw missing();
        if (c.revision() != r.revision()) throw conflict();
        if (c.status() == CaseStatus.CLOSED || !"OPEN".equals(s(old, "state")))
            throw invalid("Вопрос уже закрыт или изменился");
        evidence(r.evidenceVersionId(), caseId);
        jdbc.update(
                "UPDATE billing_questions SET state='ANSWERED',response=?,evidence_version_id=?,answered_at=? WHERE id=?",
                r.response(),
                optional(r.evidenceVersionId()),
                now(),
                target);
        history(
                caseId,
                target,
                Action.QUESTION_ANSWERED,
                r.response(),
                user,
                row("billing_questions", target));
        cases.workflowEvent(
                caseId,
                user,
                r.revision(),
                "Ответ жителя на уточнение: " + r.response(),
                "Сотрудник рассматривает ваш ответ");
        syncQuestions(caseId);
        return workspace(caseId, user);
    }

    @Transactional
    public Workspace cancelQuestion(String target, BillingRequests.Decision r, String user) {
        var old = row("billing_questions", target);
        String caseId = s(old, "case_id");
        staff(caseId, user, r.revision());
        if (!"OPEN".equals(s(old, "state"))) throw invalid("Вопрос уже закрыт");
        jdbc.update(
                "UPDATE billing_questions SET state='CANCELLED',response=?,answered_at=? WHERE id=?",
                r.note(),
                now(),
                target);
        history(
                caseId,
                target,
                Action.QUESTION_CANCELLED,
                r.note(),
                user,
                row("billing_questions", target));
        cases.workflowEvent(
                caseId,
                user,
                r.revision(),
                "Уточнение больше не требуется: " + r.note(),
                "Сотрудник продолжает проверку");
        syncQuestions(caseId);
        return workspace(caseId, user);
    }

    @Transactional
    public Workspace prepareCorrection(String caseId, BillingRequests.Correction r, String user) {
        staff(caseId, user, r.revision());
        var review = row("billing_reviews", r.reviewId());
        if (!caseId.equals(s(review, "case_id"))
                || !"PUBLISHED".equals(s(review, "state"))
                || !"DISCREPANCY".equals(s(review, "outcome"))
                || ((Number) review.get("cycle")).intValue() != cycle(caseId))
            throw invalid(
                    "Исправление готовят по опубликованному расхождению текущего рассмотрения");
        if (!r.period().equals(s(review, "period"))
                && !access.exists(
                        "SELECT count(*) FROM billing_review_lines WHERE review_id=? AND period=?",
                        r.reviewId(),
                        r.period())) throw invalid("Период должен входить в проверку");
        if (r.beforeAmount().compareTo(r.afterAmount()) == 0)
            throw invalid("Суммы до и после совпадают");
        String target = id();
        jdbc.update(
                "INSERT INTO billing_corrections(id,case_id,review_id,state,period,scope,before_amount,after_amount,delta,basis,recipient,channel,authority,due_at,due_basis,prepared_by,created_at,updated_at) VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                target,
                caseId,
                r.reviewId(),
                r.period(),
                r.scope(),
                decimal(r.beforeAmount()),
                decimal(r.afterAmount()),
                decimal(r.afterAmount().subtract(r.beforeAmount())),
                r.basis(),
                r.recipient(),
                r.channel(),
                r.authority(),
                r.dueAt(),
                r.dueBasis(),
                user,
                now(),
                now());
        history(
                caseId,
                target,
                Action.CORRECTION_PREPARED,
                "Подготовлено исправление",
                user,
                row("billing_corrections", target));
        cases.workflowEvent(
                caseId,
                user,
                r.revision(),
                "Специалист готовит запрос на исправление. Это ещё не выполненный перерасчёт.",
                "Готовим материалы для исправления начисления");
        syncQuestions(caseId);
        return workspace(caseId, user);
    }

    @Transactional
    public Workspace moveCorrection(String target, BillingRequests.CorrectionMove r, String user) {
        var old = row("billing_corrections", target);
        String caseId = s(old, "case_id");
        var c = staff(caseId, user, r.revision());
        var state = CorrectionState.valueOf(s(old, "state"));
        boolean allowed =
                switch (state) {
                    case DRAFT ->
                            r.state() == CorrectionState.SENT
                                    || r.state() == CorrectionState.CANCELLED;
                    case SENT, ACKNOWLEDGED, REWORK ->
                            r.state() == CorrectionState.APPLIED
                                    || r.state() == CorrectionState.REFUSAL_RECEIVED
                                    || r.state() == CorrectionState.ACKNOWLEDGED
                                            && state == CorrectionState.SENT
                                    || r.state() == CorrectionState.SENT
                                            && state == CorrectionState.REWORK;
                    case APPLIED ->
                            r.state() == CorrectionState.VERIFIED
                                    || r.state() == CorrectionState.REWORK;
                    case REFUSAL_RECEIVED ->
                            r.state() == CorrectionState.REFUSAL_REVIEWED
                                    || r.state() == CorrectionState.REWORK;
                    default -> false;
                };
        if (!allowed) throw invalid("Недопустимый переход запроса на исправление");
        String proof = optional(r.evidenceVersionId());
        BigDecimal actual = r.actualAfter();
        String recorded = s(old, "recorded_by");
        if (List.of(CorrectionState.VERIFIED, CorrectionState.REFUSAL_REVIEWED)
                .contains(r.state())) {
            if (user.equals(s(old, "prepared_by")) || user.equals(recorded))
                throw invalid(
                        "Подтверждение выполняет другой специалист, который не готовил запрос и не фиксировал ответ");
            if (proof != null || actual != null)
                throw invalid("При проверке нельзя менять полученный документ или сумму");
            proof = s(old, "evidence_version_id");
            evidence(proof, caseId);
            if (proof == null) throw invalid("Нет документа с результатом");
            if (r.state() == CorrectionState.VERIFIED) {
                actual = new BigDecimal(s(old, "actual_after"));
                if (actual.compareTo(new BigDecimal(s(old, "after_amount"))) != 0)
                    throw invalid(
                            "Фактическая сумма отличается от ожидаемой. Верните на уточнение");
            }
        } else if (r.state() == CorrectionState.CANCELLED) {
            if (proof != null || actual != null)
                throw invalid("Отмена черновика не фиксирует перерасчёт");
        } else if (r.state() == CorrectionState.REWORK) {
            if (proof != null || actual != null)
                throw invalid("Возврат на уточнение не меняет доказательства результата");
            proof = null;
            actual = null;
            recorded = null;
        } else {
            if (proof == null)
                throw invalid("Приложите документ, подтверждающий именно это действие");
            evidence(proof, caseId);
            if (r.state() == CorrectionState.APPLIED
                    || r.state() == CorrectionState.REFUSAL_RECEIVED) {
                if (access.exists(
                        "SELECT count(*) FROM billing_history WHERE entity_id=? AND json_extract(snapshot_json,'$.evidence_version_id')=?",
                        target,
                        proof))
                    throw invalid(
                            "Документ отправки или прежнего ответа не подтверждает новый результат. Приложите отдельный документ или его новую версию");
                if (r.state() == CorrectionState.APPLIED && actual == null)
                    throw invalid("Внесите фактическую сумму из исправленного документа");
                if (r.state() == CorrectionState.REFUSAL_RECEIVED && actual != null)
                    throw invalid("Отказ не является перерасчётом");
                recorded = user;
            } else if (actual != null)
                throw invalid("Отправка и получение не подтверждают изменение суммы");
        }
        jdbc.update(
                "UPDATE billing_corrections SET state=?,actual_after=?,evidence_version_id=?,recorded_by=?,updated_at=? WHERE id=?",
                r.state().name(),
                decimal(actual),
                proof,
                recorded,
                now(),
                target);
        history(
                caseId,
                target,
                Action.CORRECTION_MOVED,
                r.note(),
                user,
                row("billing_corrections", target));
        String note =
                switch (r.state()) {
                    case SENT ->
                            "Отправка запроса на исправление зафиксирована сотрудником. Перерасчёт ещё не подтверждён";
                    case ACKNOWLEDGED ->
                            "Получение запроса подтверждено документом. Ожидаем результат";
                    case APPLIED ->
                            "Получен документ с новой суммой. Другой специалист проверит результат";
                    case VERIFIED ->
                            "Другой специалист проверил документ и подтвердил исправление суммы. Посмотрите результат";
                    case REFUSAL_RECEIVED ->
                            "Получен отказ в исправлении. Специалист проверит основания";
                    case REFUSAL_REVIEWED ->
                            "Отказ рассмотрен другим специалистом. Исправление суммы не выполнено";
                    case REWORK -> "Результат исправления требует уточнения";
                    case CANCELLED -> "Подготовка запроса отменена. Перерасчёт не выполнен";
                    default -> throw invalid("Неизвестное действие");
                };
        cases.workflowEvent(caseId, user, r.revision(), note + ". " + r.note(), note);
        syncQuestions(caseId);
        return workspace(caseId, user);
    }

    @Transactional
    public Workspace reschedule(String target, BillingRequests.Schedule r, String user) {
        var old = row("billing_corrections", target);
        String caseId = s(old, "case_id");
        staff(caseId, user, r.revision());
        if (List.of("VERIFIED", "REFUSAL_REVIEWED", "CANCELLED").contains(s(old, "state")))
            throw invalid("Завершённый запрос не переносится");
        jdbc.update(
                "UPDATE billing_corrections SET due_at=?,due_basis=?,updated_at=? WHERE id=?",
                r.dueAt(),
                r.dueBasis(),
                now(),
                target);
        history(
                caseId,
                target,
                Action.CORRECTION_RESCHEDULED,
                r.note(),
                user,
                Map.of("before", old, "after", row("billing_corrections", target)));
        cases.workflowEvent(
                caseId,
                user,
                r.revision(),
                "Срок запроса на исправление уточнён: " + r.dueBasis() + ". Причина: " + r.note(),
                null);
        syncQuestions(caseId);
        return workspace(caseId, user);
    }

    public String printCorrection(String target, String user) {
        var r = row("billing_corrections", target);
        payment(s(r, "case_id"), user);
        var c = cases.get(s(r, "case_id"), user);
        if (List.of("DRAFT", "CANCELLED").contains(s(r, "state"))
                && (c.isAuthor() || !access.billing(c.houseId(), user))) throw missing();
        return "<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\"><title>Материалы запроса на исправление</title><h1>Материалы запроса на исправление</h1><p>Обращение №"
                + c.number()
                + " · "
                + escape(c.address())
                + "</p><p>Период: "
                + escape(s(r, "period"))
                + "</p><p>Что исправить: "
                + escape(s(r, "scope"))
                + "</p><p>Сумма до: "
                + escape(s(r, "before_amount"))
                + " ₽; ожидаемая сумма: "
                + escape(s(r, "after_amount"))
                + " ₽. Изменение: "
                + escape(s(r, "delta"))
                + " ₽.</p><p>Основание: "
                + escape(s(r, "basis"))
                + "</p><p>Адресат: "
                + escape(s(r, "recipient"))
                + "</p><p>Канал: "
                + escape(s(r, "channel"))
                + "</p><p>Полномочия сотрудника: "
                + escape(s(r, "authority"))
                + "</p><p>Срок: "
                + escape(s(r, "due_at"))
                + ". "
                + escape(s(r, "due_basis"))
                + "</p><p>Это подготовленные материалы. Файл не подписан электронной подписью и сам по себе не подтверждает отправку, доставку или выполненный перерасчёт.</p></html>";
    }

    private static String escape(String s) {
        return s == null
                ? "не указан"
                : s.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;");
    }

    @Transactional
    public ResidentCaseService.CaseView propose(
            String caseId, BillingRequests.Decision r, String user) {
        var c = staff(caseId, user, r.revision());
        return cases.update(
                caseId,
                CaseKind.PAYMENT,
                CaseStatus.RESULT_PROPOSED,
                "Посмотрите объяснение и документы проверки. "
                        + r.note().substring(0, Math.min(r.note().length(), 450)),
                null,
                c.responsibleUserId(),
                c.revision(),
                "Подготовлен результат финансового рассмотрения",
                user);
    }
}
