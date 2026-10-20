package ru.pulsedoma.issues;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.pulsedoma.common.BusinessException;

@Service
public class CaseCompletionGuard {
    private final JdbcTemplate jdbc;
    public CaseCompletionGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void requireComplete(String id){
        boolean open=jdbc.queryForObject("SELECT count(*) FROM inspection_acts a JOIN case_inspections i ON i.id=a.inspection_id WHERE i.case_id=? AND a.state='DRAFT'",Integer.class,id)>0
            ||jdbc.queryForObject("SELECT count(*) FROM case_work_orders WHERE case_id=? AND state NOT IN ('ACCEPTED','CANCELLED')",Integer.class,id)>0
            ||jdbc.queryForObject("SELECT count(*) FROM case_inspections WHERE case_id=? AND state NOT IN ('COMPLETED','CANCELLED')",Integer.class,id)>0
            ||jdbc.queryForObject("SELECT count(*) FROM case_handoffs WHERE case_id=? AND state NOT IN ('RESPONDED','CANCELLED')",Integer.class,id)>0;
        if(open)throw new BusinessException("INVALID_CASE","Сначала завершите или обоснованно отмените связанные осмотры, передачи и задания. Отчёт исполнителя ещё не означает приёмку.");
    }
}
