package ru.pulsedoma;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static ru.pulsedoma.issues.BillingTypes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.issues.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Transactional
class BillingCaseFlowTest {
    static final Path DB = temp(), FILES = dir();

    static Path temp() {
        try {
            return Files.createTempFile("billing-flow-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Path dir() {
        try {
            return Files.createTempDirectory("billing-files-");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        r.add("storage.demo.path", FILES::toString);
    }

    static final String H = "demo-house-1",
            R = "demo-resident-1",
            A = "demo-admin-1",
            D = "demo-dispatcher-1";
    @Autowired BillingCaseService flow;
    @Autowired ResidentCaseService cases;
    @Autowired CaseWorkAccess access;
    @Autowired HouseTariffService tariffs;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    String c;

    BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    @BeforeEach
    void setup() {
        cases.permission(H, D, true, A);
        cases.permission(H, A, true, A);
        c =
                cases.create(
                                H,
                                CaseKind.PAYMENT,
                                "Не понимаю, почему выросла сумма",
                                null,
                                UUID.randomUUID().toString(),
                                R)
                        .id();
    }

    int rev() {
        return cases.get(c, R).revision();
    }

    BillingCaseService.Workspace w() {
        return flow.workspace(c, D);
    }

    BillingRequests.Line line(String tariff) {
        return new BillingRequests.Line(
                "Холодная вода",
                "2026-10",
                "м³",
                Calculation.SIMPLE_PRODUCT,
                n("350"),
                n("5"),
                n("65"),
                n("0"),
                "Данные вручную сверены с документом",
                null,
                tariff);
    }

    BillingRequests.Review review(Outcome outcome, List<BillingRequests.Line> lines) {
        return new BillingRequests.Review(
                "2026-10",
                outcome,
                "Проверены указанные сведения",
                "Объяснение для жителя",
                "Проверка не подтверждает показания счётчика",
                "Документ и сведения дома",
                null,
                lines,
                rev(),
                "Подготовка версии");
    }

    String draft(Outcome outcome) {
        flow.saveReview(c, null, review(outcome, List.of(line(null))), D);
        return w().reviews().get(0).get("id").toString();
    }

    String published(Outcome outcome) {
        String id = draft(outcome);
        flow.publishReview(
                id, new BillingRequests.Decision(rev(), "Данные и объяснение проверены"), D);
        return id;
    }

    String proof(String title) {
        var v =
                cases.upload(
                        c,
                        null,
                        title,
                        ("%PDF-1.4 " + title + "\n%%EOF").getBytes(),
                        "application/pdf",
                        0,
                        D);
        return v.documents().get(v.documents().size() - 1).versions().get(0).id();
    }

    String correction(String review) {
        flow.prepareCorrection(
                c,
                new BillingRequests.Correction(
                        review,
                        "2026-10",
                        "Холодная вода",
                        n("350"),
                        n("325"),
                        "Расхождение по проверенной строке",
                        "Расчётная служба",
                        "Канал, согласованный с организацией",
                        "Полномочия сотрудника подтверждены",
                        null,
                        "Срок уточняется у адресата",
                        rev()),
                D);
        return w().corrections().get(0).get("id").toString();
    }

    void move(String id, CorrectionState state, String evidence, BigDecimal actual, String user) {
        flow.moveCorrection(
                id,
                new BillingRequests.CorrectionMove(
                        state, "Действие зафиксировано и объяснено", evidence, actual, rev()),
                user);
    }

    @Test
    void draftsAndInternalHistoryAreHiddenFromResidentAndUnprivilegedStaff() throws Exception {
        draft(Outcome.EXPLAINED);
        assertTrue(flow.workspace(c, R).reviews().isEmpty());
        assertTrue(flow.workspace(c, R).history().isEmpty());
        cases.permission(H, A, false, A);
        assertThrows(BusinessException.class, () -> flow.workspace(c, A));
        mvc.perform(get("/v1/cases/" + c + "/billing").header("X-Demo-Session", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canWork").value(false))
                .andExpect(jsonPath("$.reviews").isEmpty());
    }

    @Test
    void manualReviewWorksWithoutResidentInvoiceAndDoesNotInventCalculation() {
        var line =
                new BillingRequests.Line(
                        "Пояснение по отоплению",
                        "2026-10",
                        "Гкал",
                        Calculation.MANUAL,
                        n("1000"),
                        null,
                        null,
                        n("0"),
                        "Метод расчёта разбирается вручную",
                        null,
                        null);
        flow.saveReview(c, null, review(Outcome.EXPLAINED, List.of(line)), D);
        var row = w().reviews().get(0);
        var lines = (List<?>) row.get("lines");
        assertNull(((Map<?, ?>) lines.get(0)).get("expected"));
        assertTrue(cases.get(c, R).documents().isEmpty());
    }

    @Test
    void simpleArithmeticIsDecimalAndRoundedToCents() {
        var l =
                new BillingRequests.Line(
                        "Услуга",
                        "2026-10",
                        "ед.",
                        Calculation.SIMPLE_PRODUCT,
                        n("0.02"),
                        n("0.1"),
                        n("0.15"),
                        n("0"),
                        "Ручной ввод по источнику",
                        null,
                        null);
        flow.saveReview(c, null, review(Outcome.EXPLAINED, List.of(l)), D);
        var lines = (List<?>) w().reviews().get(0).get("lines");
        assertEquals("0.02", ((Map<?, ?>) lines.get(0)).get("expected"));
        assertEquals("0.00", ((Map<?, ?>) lines.get(0)).get("difference"));
    }

    @Test
    void publishedReviewsAreImmutableAndNewVersionsKeepHistory() {
        String id = published(Outcome.EXPLAINED);
        assertThrows(
                BusinessException.class,
                () -> flow.saveReview(c, id, review(Outcome.DISCREPANCY, List.of()), D));
        published(Outcome.EXPLAINED);
        assertEquals(2, flow.workspace(c, R).reviews().size());
        assertEquals(
                1, w().reviews().stream().filter(r -> r.get("state").equals("PUBLISHED")).count());
    }

    @Test
    void absentInformationIsAValidAnswerAndDoesNotRequireReenteringInvoice() {
        flow.ask(
                c,
                new BillingRequests.Question(
                        "Есть предыдущая квитанция?",
                        "Она поможет сравнить объёмы. Если её нет — сообщите об этом",
                        rev()),
                D);
        String q = w().questions().get(0).get("id").toString();
        assertEquals(CaseStatus.WAITING_RESIDENT, cases.get(c, R).status());
        flow.answer(q, new BillingRequests.Answer("Документа нет", null, rev()), R);
        assertEquals("ANSWERED", w().questions().get(0).get("state"));
        assertEquals("Документа нет", w().questions().get(0).get("response"));
        assertEquals(CaseStatus.IN_PROGRESS, cases.get(c, R).status());
    }

    @Test
    void answeringOneQuestionDoesNotHideOtherPendingQuestions() {
        flow.ask(c, new BillingRequests.Question("Первый вопрос", "Пояснение", rev()), D);
        String q = w().questions().get(0).get("id").toString();
        flow.ask(c, new BillingRequests.Question("Второй вопрос", "Можно не знать", rev()), D);
        flow.answer(q, new BillingRequests.Answer("Не знаю", null, rev()), R);
        assertEquals(CaseStatus.WAITING_RESIDENT, cases.get(c, R).status());
    }

    @Test
    void onlyResidentCanAnswerTheirQuestionAndStaffCanCancelWithReason() {
        flow.ask(
                c,
                new BillingRequests.Question("Уточните период", "Чтобы проверить документ", rev()),
                D);
        String q = w().questions().get(0).get("id").toString();
        assertThrows(
                BusinessException.class,
                () -> flow.answer(q, new BillingRequests.Answer("Подмена", null, rev()), D));
        flow.cancelQuestion(
                q, new BillingRequests.Decision(rev(), "Период найден в приложенном документе"), D);
        assertEquals("CANCELLED", w().questions().get(0).get("state"));
        assertEquals(CaseStatus.IN_PROGRESS, cases.get(c, R).status());
    }

    @Test
    void reviewAndQuestionsPreventGenericResultShortcut() {
        assertThrows(
                BusinessException.class,
                () -> flow.propose(c, new BillingRequests.Decision(rev(), "Решено"), D));
        published(Outcome.NEED_INFORMATION);
        assertThrows(
                BusinessException.class,
                () ->
                        cases.update(
                                c,
                                CaseKind.PAYMENT,
                                CaseStatus.RESULT_PROPOSED,
                                "Решено",
                                null,
                                null,
                                rev(),
                                D));
        published(Outcome.EXPLAINED);
        flow.ask(c, new BillingRequests.Question("Нужны сведения", "Для ответа", rev()), D);
        assertThrows(
                BusinessException.class,
                () -> flow.propose(c, new BillingRequests.Decision(rev(), "Решено"), D));
    }

    @Test
    void completeCorrectionRequiresSeparateProofAndIndependentVerification() {
        String check = published(Outcome.DISCREPANCY), request = correction(check);
        assertTrue(flow.workspace(c, R).corrections().isEmpty());
        assertThrows(BusinessException.class, () -> flow.printCorrection(request, R));
        String sent = proof("Запрос отправлен");
        move(request, CorrectionState.SENT, sent, null, D);
        assertThrows(
                BusinessException.class,
                () -> move(request, CorrectionState.APPLIED, sent, n("325"), D));
        move(request, CorrectionState.APPLIED, proof("Исправленная квитанция"), n("325"), D);
        assertThrows(
                BusinessException.class,
                () -> flow.propose(c, new BillingRequests.Decision(rev(), "Закрыть"), D));
        assertThrows(
                BusinessException.class,
                () -> move(request, CorrectionState.VERIFIED, null, null, D));
        move(request, CorrectionState.VERIFIED, null, null, A);
        assertEquals("VERIFIED", flow.workspace(c, R).corrections().get(0).get("state"));
        assertEquals("-25", w().corrections().get(0).get("delta"));
        var result =
                flow.propose(
                        c,
                        new BillingRequests.Decision(rev(), "Проверьте новую сумму в документе"),
                        D);
        assertEquals(CaseStatus.RESULT_PROPOSED, result.status());
        assertEquals(CaseStatus.CLOSED, cases.respond(c, true, "Проверено", rev(), R).status());
    }

    @Test
    void matchingDocumentCanBeRecordedButDifferentActualAmountCannotBeVerified() {
        String request = correction(published(Outcome.DISCREPANCY));
        move(request, CorrectionState.SENT, proof("Отправлено"), null, D);
        move(request, CorrectionState.APPLIED, proof("Ответ с иной суммой"), n("340"), D);
        assertThrows(
                BusinessException.class,
                () -> move(request, CorrectionState.VERIFIED, null, null, A));
        move(request, CorrectionState.REWORK, null, null, A);
        assertEquals("REWORK", w().corrections().get(0).get("state"));
    }

    @Test
    void refusalIsNotReportedAsSuccessfulRecalculation() {
        String request = correction(published(Outcome.DISCREPANCY));
        move(request, CorrectionState.SENT, proof("Отправлено"), null, D);
        move(request, CorrectionState.REFUSAL_RECEIVED, proof("Отказ организации"), null, D);
        assertThrows(
                BusinessException.class,
                () -> move(request, CorrectionState.REFUSAL_REVIEWED, null, null, D));
        move(request, CorrectionState.REFUSAL_REVIEWED, null, null, A);
        assertNull(w().corrections().get(0).get("actual_after"));
        assertTrue(cases.get(c, R).nextStep().contains("не выполнено"));
        assertEquals(
                CaseStatus.RESULT_PROPOSED,
                flow.propose(
                                c,
                                new BillingRequests.Decision(
                                        rev(),
                                        "Отказ разобран; при несогласии запросите повторное рассмотрение"),
                                D)
                        .status());
    }

    @Test
    void cancelledPreparationDoesNotResolveDiscrepancy() {
        String request = correction(published(Outcome.DISCREPANCY));
        move(request, CorrectionState.CANCELLED, null, null, D);
        assertThrows(
                BusinessException.class,
                () -> flow.propose(c, new BillingRequests.Decision(rev(), "Закрыто"), D));
        assertTrue(flow.workspace(c, R).corrections().isEmpty());
    }

    @Test
    void closedAndReopenedCaseNeedsNewReviewCycle() {
        published(Outcome.EXPLAINED);
        flow.propose(c, new BillingRequests.Decision(rev(), "Проверьте объяснение"), D);
        cases.respond(c, true, "Решено", rev(), R);
        assertThrows(BusinessException.class, () -> draft(Outcome.EXPLAINED));
        cases.respond(c, false, "Остались вопросы", rev(), R);
        assertThrows(
                BusinessException.class,
                () -> flow.propose(c, new BillingRequests.Decision(rev(), "Старое объяснение"), D));
        published(Outcome.EXPLAINED);
        assertEquals(
                CaseStatus.RESULT_PROPOSED,
                flow.propose(
                                c,
                                new BillingRequests.Decision(
                                        rev(), "Дополнительная проверка выполнена"),
                                D)
                        .status());
    }

    @Test
    void deadlineChangesKeepOldBasisAndQueueCountsOverdueCorrections() {
        String request = correction(published(Outcome.DISCREPANCY));
        flow.reschedule(
                request,
                new BillingRequests.Schedule(
                        Instant.now().minusSeconds(60),
                        "Срок из письма",
                        "Получено уточнение адресата",
                        rev()),
                D);
        var q =
                flow.queue(H, D).stream()
                        .filter(i -> i.item().id().equals(c))
                        .findFirst()
                        .orElseThrow();
        assertEquals(1, q.overdueCorrections());
        assertTrue(
                w().history().stream()
                        .anyMatch(
                                h ->
                                        h.get("action").equals("CORRECTION_RESCHEDULED")
                                                && h.get("snapshot_json")
                                                        .toString()
                                                        .contains("Срок уточняется у адресата")));
    }

    @Test
    void documentAndReviewReferencesCannotCrossCases() {
        String other =
                cases.create(
                                H,
                                CaseKind.PAYMENT,
                                "Другой вопрос",
                                null,
                                UUID.randomUUID().toString(),
                                R)
                        .id();
        var doc =
                cases.upload(
                                other,
                                null,
                                "Чужой для этого дела",
                                "%PDF-1.4\n%%EOF".getBytes(),
                                "application/pdf",
                                0,
                                D)
                        .documents()
                        .get(0)
                        .versions()
                        .get(0)
                        .id();
        flow.ask(c, new BillingRequests.Question("Вопрос", "Цель", rev()), D);
        String q = w().questions().get(0).get("id").toString();
        assertThrows(
                BusinessException.class,
                () -> flow.answer(q, new BillingRequests.Answer("Ответ", doc, rev()), R));
        assertThrows(
                BusinessException.class,
                () ->
                        flow.saveReview(
                                c,
                                null,
                                new BillingRequests.Review(
                                        "2026-10",
                                        Outcome.EXPLAINED,
                                        "Факт",
                                        "Ответ",
                                        "Границы",
                                        "Источник",
                                        doc,
                                        List.of(),
                                        rev(),
                                        "Причина"),
                                D));
    }

    @Test
    void onlyPaymentCasesAndCurrentScopedPermissionAreAccepted() {
        String other =
                cases.create(H, CaseKind.PROBLEM, "Протечка", null, UUID.randomUUID().toString(), R)
                        .id();
        assertThrows(BusinessException.class, () -> flow.workspace(other, D));
        cases.permission(H, D, false, A);
        assertThrows(BusinessException.class, () -> flow.workspace(c, D));
        assertThrows(BusinessException.class, () -> flow.queue(H, D));
    }

    @Test
    void organizationSuspensionAndStaffRevocationCloseFinancialAccess() {
        jdbc.update(
                "INSERT OR IGNORE INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES (?,?,'RESIDENT','VERIFIED','ACTIVE')",
                H,
                D);
        jdbc.update(
                "UPDATE house_memberships SET access_status='REVOKED' WHERE house_id=? AND user_id=? AND role='DISPATCHER'",
                H,
                D);
        assertThrows(BusinessException.class, () -> flow.workspace(c, D));
        jdbc.update(
                "UPDATE house_memberships SET access_status='ACTIVE' WHERE house_id=? AND user_id=?",
                H,
                D);
        jdbc.update("UPDATE organization_houses SET status='SUSPENDED' WHERE house_id=?", H);
        assertThrows(BusinessException.class, () -> flow.workspace(c, D));
        assertTrue(flow.workspace(c, R).isAuthor());
    }

    @Test
    void ordinaryResidentCannotBeGrantedFinancialWorkAndOwnCaseCannotBeReviewed() {
        assertThrows(BusinessException.class, () -> cases.permission(H, R, true, A));
        jdbc.update(
                "INSERT OR IGNORE INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES (?,?,'DISPATCHER','VERIFIED','ACTIVE')",
                H,
                R);
        cases.permission(H, R, true, A);
        assertFalse(flow.workspace(c, R).canWork());
        assertThrows(
                BusinessException.class,
                () -> flow.saveReview(c, null, review(Outcome.EXPLAINED, List.of()), R));
    }

    @Test
    void ukManagerCanWorkWithExplicitPermissionWithoutBecomingResident() {
        jdbc.update("DELETE FROM house_memberships WHERE house_id=? AND user_id=?", H, A);
        assertTrue(access.billing(H, A));
        assertTrue(
                cases.context(H, A).staff().stream()
                        .anyMatch(s -> s.id().equals(A) && s.billingAccess()));
        var updated =
                cases.update(
                        c,
                        CaseKind.PAYMENT,
                        CaseStatus.IN_REVIEW,
                        "Проверяем вопрос",
                        null,
                        A,
                        rev(),
                        "Назначен специалист",
                        D);
        assertEquals(A, updated.responsibleUserId());
        assertTrue(flow.workspace(c, A).canWork());
    }

    @Test
    void revisionsStopStaleEditsAndPublications() {
        var r = review(Outcome.EXPLAINED, List.of());
        draft(Outcome.EXPLAINED);
        assertThrows(BusinessException.class, () -> flow.saveReview(c, null, r, D));
    }

    @Test
    void tariffSnapshotIsScopedAndChangesBeforePublicationRequireRecheck() {
        tariffs.grant(
                H,
                A,
                new TariffRequests.Grant(TariffTypes.Permission.EDITOR, true, "Подготовка"),
                A);
        tariffs.grant(
                H,
                D,
                new TariffRequests.Grant(TariffTypes.Permission.PUBLISHER, true, "Проверка"),
                A);
        var t =
                tariffs.save(
                        H,
                        null,
                        new TariffRequests.Draft(
                                "Холодная вода",
                                TariffTypes.Category.UTILITY,
                                "Поставщик",
                                "Все жилые помещения",
                                TariffTypes.Unit.M3,
                                "м³",
                                n("65"),
                                LocalDate.parse("2026-01-01"),
                                null,
                                "Документ",
                                null,
                                "Пояснение",
                                0,
                                "Причина"),
                        A);
        t =
                tariffs.upload(
                        t.id(),
                        "Источник",
                        "%PDF-1.4\n%%EOF".getBytes(),
                        "application/pdf",
                        t.revision(),
                        A);
        t = tariffs.publish(t.id(), new TariffRequests.Decision(t.revision(), "Проверено"), D);
        flow.saveReview(c, null, review(Outcome.EXPLAINED, List.of(line(t.id()))), D);
        String check = w().reviews().get(0).get("id").toString();
        tariffs.withdraw(t.id(), new TariffRequests.Decision(t.revision(), "Уточняется"), D);
        assertThrows(
                BusinessException.class,
                () ->
                        flow.publishReview(
                                check, new BillingRequests.Decision(rev(), "Проверено"), D));
    }

    @Test
    void apiValidatesLineAmountsAndProtectsFinancialQueue() throws Exception {
        var body =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.valueToTree(review(Outcome.EXPLAINED, List.of(line(null))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("lines").get(0))
                .put("quantity", -1);
        mvc.perform(
                        post("/v1/cases/" + c + "/billing/reviews")
                                .header("X-Demo-Session", "dispatcher")
                                .contentType("application/json")
                                .content(body.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/houses/" + H + "/billing-queue").header("X-Demo-Session", "true"))
                .andExpect(status().isNotFound());
    }

    @Test
    void printableRequestIsEscapedAndNeverClaimsSendingOrSignature() throws Exception {
        String request = correction(published(Outcome.DISCREPANCY));
        jdbc.update(
                "UPDATE billing_corrections SET scope='<script>alert(1)</script>' WHERE id=?",
                request);
        String html = flow.printCorrection(request, D);
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("сам по себе не подтверждает отправку"));
        mvc.perform(
                        get("/v1/billing-corrections/" + request + "/print")
                                .header("X-Demo-Session", "dispatcher"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
