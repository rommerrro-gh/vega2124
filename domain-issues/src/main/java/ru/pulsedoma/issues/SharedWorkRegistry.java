package ru.pulsedoma.issues;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.pulsedoma.common.BusinessException;

/** Common work has an independent service card; residents' files and visits never move. */
@Service
public class SharedWorkRegistry {
 public enum Kind { WORK_ORDER, INSPECTION, HANDOFF, EXCHANGE }
 public record Item(Kind kind,String id,String title,int revision){}
 public record Allocation(Kind kind,String id,int revision,boolean move){}
 private final JdbcTemplate db;
 public SharedWorkRegistry(JdbcTemplate db){this.db=db;}
 private String table(Kind kind){return switch(kind){case WORK_ORDER->"case_work_orders";case INSPECTION->"case_inspections";case HANDOFF->"case_handoffs";case EXCHANGE->"exchange_packets";};}
 public List<Item> list(String caseId){List<Item> result=new ArrayList<>();for(Kind kind:Kind.values()){String title=switch(kind){case WORK_ORDER->"title";case INSPECTION->"location";case HANDOFF->"recipient";case EXCHANGE->"subject";};for(var row:db.queryForList("SELECT id,"+title+" AS title,revision FROM "+table(kind)+" WHERE case_id=?",caseId))result.add(new Item(kind,(String)row.get("id"),(String)row.get("title"),((Number)row.get("revision")).intValue()));}return result;}
 public void allocate(String from,String to,List<Allocation> decisions){var current=list(from);if(decisions==null||decisions.size()!=current.size()||decisions.stream().map(d->d.kind()+":"+d.id()).distinct().count()!=current.size())throw invalid("Для каждой общей работы выберите: оставить или перенести");for(var item:current){var decision=decisions.stream().filter(d->d.kind()==item.kind()&&d.id().equals(item.id())).findFirst().orElseThrow(()->invalid("Состав работ изменился. Обновите заявку"));if(decision.revision()!=item.revision())throw invalid("Работа изменилась. Обновите заявку");}
  // An order referring to an inspection must follow its inspection, so the act remains coherent.
  for(var row:db.queryForList("SELECT w.id,i.id AS inspection FROM case_work_orders w JOIN inspection_acts a ON a.id=w.act_id JOIN case_inspections i ON i.id=a.inspection_id WHERE w.case_id=? AND i.case_id=?",from,from)){boolean order=decisions.stream().anyMatch(d->d.kind()==Kind.WORK_ORDER&&d.id().equals(row.get("id"))&&d.move());boolean inspection=decisions.stream().anyMatch(d->d.kind()==Kind.INSPECTION&&d.id().equals(row.get("inspection"))&&d.move());if(order!=inspection)throw invalid("Работа и связанный с ней осмотр должны остаться вместе");}
  for(var d:decisions)if(d.move()){for(String version:evidence(d.kind(),d.id()))db.update("INSERT OR IGNORE INTO shared_work_document_access(case_id,version_id) VALUES (?,?)",to,version);db.update("UPDATE "+table(d.kind())+" SET case_id=?,revision=revision+1 WHERE id=? AND case_id=?",to,d.id(),from);}
  db.update("INSERT OR IGNORE INTO case_collaborators(case_id,user_id,function,assigned_by,created_at,source_organization_id) SELECT ?,user_id,function,assigned_by,created_at,source_organization_id FROM case_collaborators WHERE case_id=?",to,from);
 }
 private List<String> evidence(Kind kind,String id){return switch(kind){case WORK_ORDER->db.query("SELECT evidence_version_id FROM work_order_reports WHERE work_order_id=? AND evidence_version_id IS NOT NULL",(r,n)->r.getString(1),id);case INSPECTION->db.query("SELECT evidence_version_id FROM inspection_acts WHERE inspection_id=? AND evidence_version_id IS NOT NULL",(r,n)->r.getString(1),id);case HANDOFF->db.query("SELECT evidence_version_id FROM case_handoff_events WHERE handoff_id=? AND evidence_version_id IS NOT NULL",(r,n)->r.getString(1),id);case EXCHANGE->List.of();};}
 public void merge(String from,String to){allocate(from,to,list(from).stream().map(i->new Allocation(i.kind(),i.id(),i.revision(),true)).toList());db.update("INSERT OR IGNORE INTO shared_work_document_access(case_id,version_id) SELECT ?,v.id FROM case_documents d JOIN case_document_versions v ON v.document_id=d.id WHERE d.case_id=?",to,from);}
 private BusinessException invalid(String message){return new BusinessException("CASE_CONFLICT",message);}
}
