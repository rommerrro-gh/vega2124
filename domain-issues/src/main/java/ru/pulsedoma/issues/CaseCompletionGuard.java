package ru.pulsedoma.issues;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.pulsedoma.common.BusinessException;

@Service
public class CaseCompletionGuard {
    private final JdbcTemplate jdbc;
    public CaseCompletionGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void requireComplete(String id){
        if(jdbc.queryForObject("SELECT count(*) FROM case_visits WHERE case_id=? AND state NOT IN ('COMPLETED','CANCELLED')",Integer.class,id)>0)throw new BusinessException("INVALID_CASE","Сначала завершите или обоснованно отмените согласование визита.");
        if(jdbc.queryForObject("SELECT count(*) FROM case_mass_links l JOIN house_mass_problems p ON p.id=l.problem_id WHERE l.case_id=? AND p.state IN ('DRAFT','ACTIVE')",Integer.class,id)>0)throw new BusinessException("INVALID_CASE","Общая работа ещё не завершена. Если личный вопрос уже решён независимо, снимите связь с общей проблемой с пояснением.");
        if(jdbc.queryForObject("SELECT count(*) FROM exchange_packets WHERE case_id=? AND state NOT IN ('REVIEWED','CANCELLED')",Integer.class,id)>0)throw new BusinessException("INVALID_CASE","Сначала завершите передачу материалов и рассмотрите ответ организации или обоснованно отмените передачу.");
        if(jdbc.queryForObject("SELECT count(*) FROM resident_cases WHERE id=? AND kind='PAYMENT'",Integer.class,id)>0){
            var reviews=jdbc.queryForList("SELECT id,outcome FROM billing_reviews WHERE case_id=? AND state='PUBLISHED' AND cycle=(SELECT count(*) FROM case_events WHERE case_id=? AND type='REOPENED') ORDER BY rowid DESC LIMIT 1",id,id);
            if(reviews.isEmpty()||"NEED_INFORMATION".equals(reviews.get(0).get("outcome")))throw new BusinessException("INVALID_CASE","Сначала опубликуйте результат проверки текущего финансового рассмотрения. Недостаток сведений не означает решённый вопрос.");
            boolean pending=jdbc.queryForObject("SELECT count(*) FROM billing_reviews WHERE case_id=? AND state='DRAFT'",Integer.class,id)>0
                ||jdbc.queryForObject("SELECT count(*) FROM billing_questions WHERE case_id=? AND state='OPEN'",Integer.class,id)>0
                ||jdbc.queryForObject("SELECT count(*) FROM billing_corrections WHERE case_id=? AND state NOT IN ('VERIFIED','REFUSAL_REVIEWED','CANCELLED')",Integer.class,id)>0;
            if(pending)throw new BusinessException("INVALID_CASE","Сначала завершите финансовые уточнения и проверку исправления. Полученный ответ ещё не подтверждает перерасчёт.");
            if("DISCREPANCY".equals(reviews.get(0).get("outcome"))&&jdbc.queryForObject("SELECT count(*) FROM billing_corrections WHERE review_id=? AND state IN ('VERIFIED','REFUSAL_REVIEWED')",Integer.class,reviews.get(0).get("id"))==0)
                throw new BusinessException("INVALID_CASE","По найденному расхождению нужен подтверждённый результат исправления или рассмотренный отказ.");
        }
        boolean open=jdbc.queryForObject("SELECT count(*) FROM inspection_acts a JOIN case_inspections i ON i.id=a.inspection_id WHERE i.case_id=? AND a.state='DRAFT'",Integer.class,id)>0
            ||jdbc.queryForObject("SELECT count(*) FROM case_work_orders WHERE case_id=? AND state NOT IN ('ACCEPTED','CANCELLED')",Integer.class,id)>0
            ||jdbc.queryForObject("SELECT count(*) FROM case_inspections WHERE case_id=? AND state NOT IN ('COMPLETED','CANCELLED')",Integer.class,id)>0
            ||jdbc.queryForObject("SELECT count(*) FROM case_handoffs WHERE case_id=? AND state NOT IN ('RESPONDED','CANCELLED')",Integer.class,id)>0;
        if(open)throw new BusinessException("INVALID_CASE","Сначала завершите или обоснованно отмените связанные осмотры, передачи и задания. Отчёт исполнителя ещё не означает приёмку.");
    }
}
