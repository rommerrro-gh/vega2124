package ru.pulsedoma.issues;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import static ru.pulsedoma.issues.TariffTypes.*;

@Service
public class HouseTariffService {
    private final JdbcTemplate jdbc;
    private final CaseWorkAccess access;
    private final AttachmentStore store;
    private final ObjectMapper mapper;
    public HouseTariffService(JdbcTemplate jdbc,CaseWorkAccess access,AttachmentStore store,ObjectMapper mapper) {
        this.jdbc=jdbc;this.access=access;this.store=store;this.mapper=mapper;
    }
    public record Rights(boolean canManage,boolean canEdit,boolean canPublish) {}
    public record Staff(String userId,String name,boolean active,List<Permission> permissions) {}
    public record SourceFile(String id,String title,String mime,long sizeBytes) {}
    public record History(int revision,Action action,String reason,String actor,String createdAt,String snapshot) {}
    public record Rate(String id,String seriesId,String replacesId,State state,String title,Category category,
        String provider,String applicability,Unit unit,String unitLabel,BigDecimal amount,String validFrom,String validUntil,
        String effectiveUntil,String sourceReference,String sourceUrl,String explanation,String editedBy,String publishedBy,
        String publishedAt,int revision,List<SourceFile> files,List<History> history) {}
    public record Change(String seriesId,String title,BigDecimal previousAmount,BigDecimal amount,String unitLabel,String validFrom,String explanation) {}
    public record Catalogue(String houseId,String date,List<Rate> rates,List<Rate> history,List<Change> upcoming) {}
    private static String id(){return UUID.randomUUID().toString();}
    private static String now(){return Instant.now().toString();}
    private static String str(Map<String,Object> r,String key){return Objects.toString(r.get(key),null);}
    private static int revision(Map<String,Object> r){return ((Number)r.get("revision")).intValue();}
    private static BusinessException missing(){return new BusinessException("CASE_NOT_FOUND","Сведения недоступны");}
    private static BusinessException invalid(String text){return new BusinessException("INVALID_TARIFF",text);}
    private static BusinessException conflict(){return new BusinessException("CASE_CONFLICT","Сведения изменились. Обновите раздел тарифов");}
    private Map<String,Object> row(String id){var rows=jdbc.queryForList("SELECT * FROM house_tariffs WHERE id=?",id);if(rows.isEmpty())throw missing();return rows.get(0);}
    private boolean eligibleStaff(String house,String user){
        return access.manager(house,user)||access.staff(house,user)||access.exists("SELECT count(*) FROM house_memberships m JOIN users u ON u.id=m.user_id WHERE m.house_id=? AND m.user_id=? AND m.role IN ('DISPATCHER','HOUSE_ADMIN') AND m.verification_status='VERIFIED' AND m.access_status='ACTIVE' AND u.status='ACTIVE'",house,user);
    }
    private boolean granted(String house,String user,Permission role){
        return access.exists("""
            SELECT count(*) FROM tariff_permissions p JOIN users u ON u.id=p.user_id
            JOIN organization_houses h ON h.organization_id=p.organization_id AND h.house_id=p.house_id
            WHERE p.house_id=? AND p.user_id=? AND p.role=? AND h.status='ACTIVE' AND u.status='ACTIVE'
            """,house,user,role.name()) && eligibleStaff(house,user);
    }
    public Rights rights(String house,String user){
        if(!access.member(house,user)&&!access.manager(house,user)&&!granted(house,user,Permission.EDITOR)&&!granted(house,user,Permission.PUBLISHER))throw missing();
        return new Rights(access.manager(house,user),granted(house,user,Permission.EDITOR),granted(house,user,Permission.PUBLISHER));
    }
    private void registry(String house,String user){var r=rights(house,user);if(!r.canManage()&&!r.canEdit()&&!r.canPublish())throw missing();}
    private void require(String house,String user,Permission role){if(!granted(house,user,role))throw missing();}
    private void manager(String house,String user){if(!access.manager(house,user))throw missing();}
    public List<Staff> staff(String house,String user){manager(house,user);
        return jdbc.query("""
            SELECT DISTINCT u.id,u.display_name FROM users u WHERE u.status='ACTIVE' AND (
            EXISTS(SELECT 1 FROM house_memberships m WHERE m.user_id=u.id AND m.house_id=? AND m.access_status='ACTIVE' AND m.verification_status='VERIFIED' AND m.role IN ('DISPATCHER','HOUSE_ADMIN'))
            OR EXISTS(SELECT 1 FROM uk_admin_houses a WHERE a.user_id=u.id AND a.house_id=? AND a.access_status='ACTIVE')
            OR EXISTS(SELECT 1 FROM workflow_staff s JOIN organization_houses h ON s.house_id=h.house_id AND s.organization_id=h.organization_id WHERE s.user_id=u.id AND s.house_id=? AND s.active=1 AND h.status='ACTIVE') OR EXISTS(SELECT 1 FROM tariff_permissions p WHERE p.house_id=? AND p.user_id=u.id)) ORDER BY u.display_name
            """,(rs,n)->new Staff(rs.getString(1),rs.getString(2),eligibleStaff(house,rs.getString(1)),Arrays.stream(Permission.values()).filter(p->access.exists("SELECT count(*) FROM tariff_permissions WHERE house_id=? AND user_id=? AND role=?",house,rsString(rs,1),p.name())).toList()),house,house,house,house);
    }
    private static String rsString(java.sql.ResultSet rs,int column){try{return rs.getString(column);}catch(Exception e){throw new IllegalStateException(e);}}
    @Transactional public void grant(String house,String target,TariffRequests.Grant r,String user){manager(house,user);
        if(r.enabled()&&staff(house,user).stream().noneMatch(s->s.userId().equals(target)&&s.active()))throw invalid("Выберите действующего сотрудника дома");
        if(r.enabled()){
            String org=jdbc.queryForObject("SELECT organization_id FROM uk_admin_houses WHERE house_id=? AND user_id=? AND access_status='ACTIVE' LIMIT 1",String.class,house,user);
            jdbc.update("INSERT INTO tariff_permissions(house_id,user_id,role,organization_id,granted_by,granted_at) VALUES (?,?,?,?,?,?) ON CONFLICT(house_id,user_id,role) DO UPDATE SET organization_id=excluded.organization_id,granted_by=excluded.granted_by,granted_at=excluded.granted_at",house,target,r.role().name(),org,user,now());
        }else jdbc.update("DELETE FROM tariff_permissions WHERE house_id=? AND user_id=? AND role=?",house,target,r.role().name());
        jdbc.update("INSERT INTO audit_events(id,actor_id,action,entity,entity_id,after_json,created_at) VALUES (?,?,?,'tariff_permission',?,?,?)",id(),user,r.enabled()?"TARIFF_ACCESS_GRANTED":"TARIFF_ACCESS_REVOKED",house+":"+target,json(Map.of("role",r.role(),"reason",r.reason())),now());
    }
    public List<Rate> listRegistry(String house,String user){registry(house,user);return jdbc.queryForList("SELECT id FROM house_tariffs WHERE house_id=? ORDER BY updated_at DESC,id",house).stream().map(r->view(str(r,"id"),true)).toList();}
    private void checkDraft(Map<String,Object> r,int expected){if(!"DRAFT".equals(str(r,"state")))throw invalid("Опубликованную запись нельзя изменять. Создайте новую версию");if(revision(r)!=expected)throw conflict();}
    private void checkData(TariffRequests.Draft r){
        if(r.validUntil()!=null&&r.validUntil().isBefore(r.validFrom()))throw invalid("Конец периода не может быть раньше начала");
        if(r.sourceUrl()!=null&&!r.sourceUrl().isBlank())try{var u=URI.create(r.sourceUrl());if(!List.of("https","http").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw invalid("Укажите обычную ссылку HTTP или HTTPS");}catch(IllegalArgumentException e){throw invalid("Проверьте ссылку на источник");}
    }
    @Transactional public Rate save(String house,String tariffId,TariffRequests.Draft r,String user){require(house,user,Permission.EDITOR);checkData(r);
        String target=tariffId==null?id():tariffId,time=now();
        if(tariffId==null){if(r.revision()!=0)throw conflict();jdbc.update("""
            INSERT INTO house_tariffs(id,house_id,series_id,title,category,provider,applicability,unit,unit_label,amount,valid_from,valid_until,source_reference,source_url,explanation,edited_by,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,target,house,target,r.title().strip(),r.category().name(),r.provider().strip(),r.applicability().strip(),r.unit().name(),r.unitLabel().strip(),r.amount().toPlainString(),r.validFrom(),r.validUntil(),r.sourceReference().strip(),r.sourceUrl(),r.explanation().strip(),user,time,time);
        }else{var old=row(target);if(!house.equals(str(old,"house_id")))throw missing();checkDraft(old,r.revision());
            if(old.get("replaces_id")!=null&&!r.validFrom().toString().equals(str(row(str(old,"replaces_id")),"valid_from")))throw invalid("Исправление сохраняет начало исходного периода. Для нового периода создайте отдельную версию");
            int changed=jdbc.update("""
                UPDATE house_tariffs SET title=?,category=?,provider=?,applicability=?,unit=?,unit_label=?,amount=?,valid_from=?,valid_until=?,source_reference=?,source_url=?,explanation=?,edited_by=?,updated_at=?,revision=revision+1 WHERE id=? AND revision=? AND state='DRAFT'
                """,r.title().strip(),r.category().name(),r.provider().strip(),r.applicability().strip(),r.unit().name(),r.unitLabel().strip(),r.amount().toPlainString(),r.validFrom(),r.validUntil(),r.sourceReference().strip(),r.sourceUrl(),r.explanation().strip(),user,time,target,r.revision());if(changed!=1)throw conflict();}
        history(target,tariffId==null?Action.CREATED:Action.EDITED,r.reason(),user);return view(target,true);
    }
    @Transactional public Rate copy(String target,TariffRequests.Copy r,String user){var old=row(target);require(str(old,"house_id"),user,Permission.EDITOR);
        if(!"PUBLISHED".equals(str(old,"state"))&&!("WITHDRAWN".equals(str(old,"state"))&&r.correction()))throw invalid("Новую версию создают из опубликованной записи; отозванную можно исправить");if(revision(old)!=r.revision())throw conflict();
        if(r.correction()&&!r.validFrom().toString().equals(str(old,"valid_from")))throw invalid("Исправление должно относиться к исходному периоду");
        if(!r.correction()&&!r.validFrom().isAfter(LocalDate.parse(str(old,"valid_from"))))throw invalid("Новый период должен начинаться позже исходного");
        String next=id(),time=now();jdbc.update("""
            INSERT INTO house_tariffs(id,house_id,series_id,replaces_id,title,category,provider,applicability,unit,unit_label,amount,valid_from,valid_until,source_reference,source_url,explanation,edited_by,created_at,updated_at)
            SELECT ?,house_id,series_id,?,title,category,provider,applicability,unit,unit_label,amount,?,?,source_reference,source_url,explanation,?,?,? FROM house_tariffs WHERE id=?
            """,next,r.correction()?target:null,r.validFrom(),r.correction()?str(old,"valid_until"):null,user,time,time,target);
        jdbc.queryForList("SELECT * FROM tariff_files WHERE tariff_id=?",target).forEach(f->jdbc.update("INSERT INTO tariff_files(id,tariff_id,title,storage_key,mime,size_bytes,uploaded_by,created_at) VALUES (?,?,?,?,?,?,?,?)",id(),next,f.get("title"),f.get("storage_key"),f.get("mime"),f.get("size_bytes"),f.get("uploaded_by"),time));
        history(next,Action.CREATED,r.reason(),user);return view(next,true);
    }
    @Transactional public Rate upload(String target,String title,byte[] bytes,String mime,int expected,String user){var old=row(target);require(str(old,"house_id"),user,Permission.EDITOR);checkDraft(old,expected);
        if(bytes.length==0||bytes.length>10*1024*1024||!ResidentCaseService.validDocument(bytes,mime))throw invalid("Допустимы PDF, JPEG или PNG до 10 МБ");
        if(jdbc.queryForObject("SELECT count(*) FROM tariff_files WHERE tariff_id=?",Integer.class,target)>=10)throw invalid("Можно приложить не более 10 документов");
        String file=id(),key="tariffs/"+target+"/"+file;store.put(key,bytes,mime);
        jdbc.update("INSERT INTO tariff_files(id,tariff_id,title,storage_key,mime,size_bytes,uploaded_by,created_at) VALUES (?,?,?,?,?,?,?,?)",file,target,title,key,mime,bytes.length,user,now());
        touch(target,expected,user);history(target,Action.DOCUMENT_ADDED,"Добавлен источник: "+title,user);return view(target,true);
    }
    @Transactional public Rate removeFile(String target,String file,int expected,String user){var old=row(target);require(str(old,"house_id"),user,Permission.EDITOR);checkDraft(old,expected);
        if(jdbc.update("DELETE FROM tariff_files WHERE id=? AND tariff_id=?",file,target)!=1)throw missing();
        touch(target,expected,user);history(target,Action.DOCUMENT_REMOVED,"Документ исключён из черновика",user);return view(target,true);
    }
    private void touch(String target,int expected,String user){if(jdbc.update("UPDATE house_tariffs SET revision=revision+1,edited_by=?,updated_at=? WHERE id=? AND revision=? AND state='DRAFT'",user,now(),target,expected)!=1)throw conflict();}
    @Transactional public Rate publish(String target,TariffRequests.Decision r,String user){var old=row(target);require(str(old,"house_id"),user,Permission.PUBLISHER);checkDraft(old,r.revision());
        if(user.equals(str(old,"edited_by")))throw invalid("Публикацию должен проверить другой уполномоченный сотрудник");
        if(!access.exists("SELECT count(*) FROM tariff_files WHERE tariff_id=?",target))throw invalid("Приложите общедомовой документ-основание без персональных данных");
        String replaced=str(old,"replaces_id");
        if(replaced!=null){var previous=row(replaced);if(!List.of("PUBLISHED","WITHDRAWN").contains(str(previous,"state"))||!str(old,"series_id").equals(str(previous,"series_id"))||!str(old,"valid_from").equals(str(previous,"valid_from")))throw conflict();
            jdbc.update("UPDATE house_tariffs SET state='SUPERSEDED',revision=revision+1,updated_at=? WHERE id=? AND state IN ('PUBLISHED','WITHDRAWN')",now(),replaced);history(replaced,Action.SUPERSEDED,r.reason(),user);
        }else if(access.exists("SELECT count(*) FROM house_tariffs WHERE series_id=? AND valid_from=? AND published_at IS NOT NULL AND state IN ('PUBLISHED','WITHDRAWN')",old.get("series_id"),old.get("valid_from")))throw invalid("На эту дату уже есть публикация. Для исправления создайте её новую версию");
        if(replaced==null&&access.exists("SELECT count(*) FROM house_tariffs WHERE house_id=? AND series_id<>? AND state='PUBLISHED' AND title=? COLLATE NOCASE AND provider=? COLLATE NOCASE AND applicability=? COLLATE NOCASE AND unit=? AND unit_label=? COLLATE NOCASE",old.get("house_id"),old.get("series_id"),old.get("title"),old.get("provider"),old.get("applicability"),old.get("unit"),old.get("unit_label")))throw invalid("Эта услуга с такой применимостью уже опубликована. Создайте новый период из её карточки");
        if(jdbc.update("UPDATE house_tariffs SET state='PUBLISHED',published_by=?,published_at=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND state='DRAFT'",user,now(),now(),target,r.revision())!=1)throw conflict();
        history(target,Action.PUBLISHED,r.reason(),user);return view(target,true);
    }
    @Transactional public Rate withdraw(String target,TariffRequests.Decision r,String user){var old=row(target);require(str(old,"house_id"),user,Permission.PUBLISHER);
        if(!"PUBLISHED".equals(str(old,"state")))throw invalid("Отозвать можно опубликованную запись");if(revision(old)!=r.revision())throw conflict();
        if(jdbc.update("UPDATE house_tariffs SET state='WITHDRAWN',revision=revision+1,updated_at=? WHERE id=? AND revision=? AND state='PUBLISHED'",now(),target,r.revision())!=1)throw conflict();
        history(target,Action.WITHDRAWN,r.reason(),user);return view(target,true);
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private void history(String target,Action action,String reason,String user){try{var row=row(target);jdbc.update("INSERT INTO tariff_history(id,tariff_id,revision,actor_id,action,reason,snapshot_json,created_at) VALUES (?,?,?,?,?,?,?,?)",id(),target,revision(row),user,action.name(),reason,mapper.writeValueAsString(row),now());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
    private Rate view(String target,boolean internal){var r=row(target);
        String end=str(r,"valid_until");
        // Withdrawn periods remain boundaries: withdrawing a rate must never revive an older rate.
        var next=jdbc.queryForList("SELECT min(valid_from) AS next FROM house_tariffs WHERE series_id=? AND published_at IS NOT NULL AND state IN ('PUBLISHED','WITHDRAWN') AND valid_from>?",r.get("series_id"),r.get("valid_from"));
        String boundary=str(next.get(0),"next");if(boundary!=null){String cut=LocalDate.parse(boundary).minusDays(1).toString();if(end==null||cut.compareTo(end)<0)end=cut;}
        var files=jdbc.query("SELECT id,title,mime,size_bytes FROM tariff_files WHERE tariff_id=? ORDER BY created_at,id",(rs,n)->new SourceFile(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4)),target);
        var events=jdbc.query("SELECT h.revision,h.action,h.reason,u.display_name,h.created_at,h.snapshot_json FROM tariff_history h JOIN users u ON h.actor_id=u.id WHERE h.tariff_id=? "+(internal?"":"AND h.action IN ('PUBLISHED','SUPERSEDED','WITHDRAWN') ")+"ORDER BY h.revision",(rs,n)->new History(rs.getInt(1),Action.valueOf(rs.getString(2)),rs.getString(3),internal?rs.getString(4):null,rs.getString(5),internal?rs.getString(6):null),target);
        return new Rate(target,str(r,"series_id"),str(r,"replaces_id"),State.valueOf(str(r,"state")),str(r,"title"),Category.valueOf(str(r,"category")),str(r,"provider"),str(r,"applicability"),Unit.valueOf(str(r,"unit")),str(r,"unit_label"),new BigDecimal(str(r,"amount")),str(r,"valid_from"),str(r,"valid_until"),end,str(r,"source_reference"),str(r,"source_url"),str(r,"explanation"),internal?str(r,"edited_by"):null,internal?str(r,"published_by"):null,str(r,"published_at"),revision(r),files,events);
    }
    public Catalogue catalogue(String house,LocalDate date,String user){rights(house,user);
        var all=jdbc.queryForList("SELECT id FROM house_tariffs WHERE house_id=? AND published_at IS NOT NULL ORDER BY valid_from DESC,published_at DESC,id",house).stream().map(r->view(str(r,"id"),false)).toList();
        String day=date.toString();var current=all.stream().filter(r->r.state()==State.PUBLISHED&&r.validFrom().compareTo(day)<=0&&(r.effectiveUntil()==null||r.effectiveUntil().compareTo(day)>=0)).toList();
        var upcoming=all.stream().filter(r->r.state()==State.PUBLISHED&&r.validFrom().compareTo(day)>0).sorted(Comparator.comparing(Rate::validFrom)).map(r->{
            var previous=all.stream().filter(p->p.state()==State.PUBLISHED&&p.seriesId().equals(r.seriesId())&&p.validFrom().compareTo(r.validFrom())<0).findFirst().orElse(null);
            return new Change(r.seriesId(),r.title(),previous!=null&&previous.unit()==r.unit()&&previous.unitLabel().equals(r.unitLabel())&&previous.provider().equals(r.provider())&&previous.applicability().equals(r.applicability())?previous.amount():null,r.amount(),r.unitLabel(),r.validFrom(),r.explanation());
        }).toList();
        return new Catalogue(house,day,current,all,upcoming);
    }
    public ResidentCaseService.FileData file(String file,String user){var found=jdbc.queryForList("SELECT * FROM tariff_files WHERE id=?",file);if(found.isEmpty())throw missing();var f=found.get(0);var rate=row(str(f,"tariff_id"));String house=str(rate,"house_id");rights(house,user);
        if(rate.get("published_at")==null)registry(house,user);
        return new ResidentCaseService.FileData(store.get(str(f,"storage_key")),str(f,"mime"));
    }
}
