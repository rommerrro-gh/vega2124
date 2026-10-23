package ru.pulsedoma.issues;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.duplicates.*;
import java.time.*;
import java.util.*;

@Service
public class ProblemIntakeService {
    public enum Source { SHARED, OWN_SHARED }
    public record Input(String houseId, String description, ReportCategory category, String location, LocationFeatures locationFeatures) {}
    public record Match(Source source,String id,String description,String location,double score,List<String> reasons, String caseId, int participants) {}
    private final JdbcTemplate jdbc;
    private final ResidentCaseService cases;
    private final TextNormalizer normalizer;
    private final ObjectMapper mapper;
    private final SharedRequestService shared;
    private final SharedRequestRegistry registry;
    public ProblemIntakeService(JdbcTemplate jdbc,ResidentCaseService cases,TextNormalizer normalizer,ObjectMapper mapper,SharedRequestService shared,SharedRequestRegistry registry) {
        this.jdbc=jdbc;this.cases=cases;this.normalizer=normalizer;this.mapper=mapper;this.shared=shared;this.registry=registry;
    }
    private void check(Input input,String user) {
        Integer n=jdbc.queryForObject("SELECT count(*) FROM house_memberships m JOIN users u ON u.id=m.user_id WHERE m.house_id=? AND m.user_id=? AND m.role='RESIDENT' AND m.verification_status='VERIFIED' AND m.access_status='ACTIVE' AND u.status='ACTIVE'",Integer.class,input.houseId(),user);
        if(n==null||n==0)throw new BusinessException("HOUSE_ACCESS_DENIED","Нет доступа жителя к дому");
        if(input.locationFeatures()!=null){if(input.category()==null)throw new BusinessException("INVALID_LOCATION","Выберите тему или продолжите без уточнений");input.locationFeatures().validate(input.category().name());
            for(var entry:Map.of("entrance_count",Objects.requireNonNullElse(input.locationFeatures().entrance(),0),"floor_count",Objects.requireNonNullElse(input.locationFeatures().floor(),0)).entrySet()) {
                var totals=jdbc.queryForList("SELECT json_extract(value_json,'$') AS total FROM house_fields WHERE house_id=? AND key=?",input.houseId(),entry.getKey());
                if(!totals.isEmpty()&&totals.get(0).get("total")!=null){try{int total=Integer.parseInt(totals.get(0).get("total").toString());if(total>0&&entry.getValue()>total)throw new BusinessException("INVALID_LOCATION","Номер больше указанного в паспорте дома");}catch(NumberFormatException ignored){}}
            }
        }
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private LocationFeatures features(String value){try{return value==null?null:mapper.readValue(value,LocationFeatures.class);}catch(Exception e){throw new IllegalStateException(e);}}
    public List<Match> search(Input input,String user) {
        check(input,user);
        if(input.category()==null)throw new BusinessException("INVALID_CASE","Выберите категорию, если не уверены — Другое");
        String normalized=normalizer.normalize(input.description());
        String category=input.category()==null||input.category()==ReportCategory.OTHER?null:input.category().name();
        Instant since=Instant.now().minus(Duration.ofDays(30));
        List<Match> matches=new ArrayList<>();
        for(var row:jdbc.queryForList("SELECT s.*,strftime('%s',s.created_at) AS created_epoch FROM shared_requests s WHERE s.house_id=? AND s.merged_into IS NULL AND s.status<>'CLOSED' AND EXISTS(SELECT 1 FROM shared_request_members m WHERE m.request_id=s.id AND m.withdrawn_at IS NULL) AND datetime(s.created_at)>=datetime(?) AND datetime(s.created_at)<=datetime('now') ORDER BY s.updated_at DESC LIMIT 500",input.houseId(),since.toString())) {
            String storedCategory=(String)row.get("category");
            if(category!=null&&!"OTHER".equals(storedCategory)&&!category.equals(storedCategory))continue;
            double place=0;List<String> reasons=new ArrayList<>(List.of("same_house"));
            var other=features((String)row.get("location_features"));
            if(input.locationFeatures()!=null&&other!=null&&category!=null&&category.equals(storedCategory)){var m=new StructuredLocationMatcher().compare(category,input.locationFeatures(),other);if(!m.compatible())continue;place=m.score();reasons.addAll(m.reasons());}
            else {String inputPlace=input.locationFeatures()==null?input.location():input.locationFeatures().label();if(inputPlace!=null&&row.get("location")!=null){var m=new LocationMatcher().compare(category,inputPlace,(String)row.get("location"));if(!m.compatible())continue;place=m.score();reasons.addAll(m.reasons());}}
            Set<String> a=terms(normalized),b=terms(normalizer.normalize((String)row.get("normalized_text")));
            var common=new HashSet<>(a);common.retainAll(b);var union=new HashSet<>(a);union.addAll(b);
            double text=union.isEmpty()?0:(double)common.size()/union.size();
            double recency=Math.max(0,1-(double)Duration.between(Instant.ofEpochSecond(Long.parseLong(row.get("created_epoch").toString())),Instant.now()).toHours()/720);
            boolean same=category!=null&&category.equals(storedCategory);
            double score=same&&other!=null&&input.locationFeatures()!=null ? .4+.35*place+.15*recency+.1*text : .7*text+.3*place;
            if(same)reasons.add("same_category");if(!common.isEmpty())reasons.add("shared_terms:"+common.size());
            if(score>=.65){String group=(String)row.get("id");var view=shared.get(group,user);matches.add(new Match(view.myCaseId()==null?Source.SHARED:Source.OWN_SHARED,group,(String)row.get("title"),(String)row.get("location"),score,reasons,view.myCaseId(),view.participants()));}
        }
        return matches.stream().sorted(Comparator.comparingDouble(Match::score).reversed()).limit(10).toList();
    }
    private Set<String> terms(String value){return Arrays.stream(value.split("\\s+")).filter(t->!Set.of("не","в","на","и","у","по","из","за").contains(t)).collect(java.util.stream.Collectors.toSet());}
    @Transactional
    public ResidentCaseService.CaseView create(Input input,CaseKind kind,String requestId,String user) {
        check(input,user);
        if(kind==null)throw new BusinessException("INVALID_CASE","Укажите тип обращения");
        if(kind!=CaseKind.PAYMENT&&input.category()==null)throw new BusinessException("INVALID_CASE","Выберите категорию, если не уверены — Другое");
        if(kind!=CaseKind.PROBLEM&&(input.category()!=null||input.locationFeatures()!=null))throw new BusinessException("INVALID_CASE","Уточнения темы доступны для проблемы дома");
        String location=input.locationFeatures()==null?input.location():input.locationFeatures().label();
        var result=cases.create(input.houseId(),kind,input.description(),location,requestId,user);
        String category=kind==CaseKind.PAYMENT?null:input.category()==null?"OTHER":input.category().name(),features=input.locationFeatures()==null?null:json(input.locationFeatures());
        var existing=jdbc.queryForList("SELECT category,location_features FROM case_problem_details WHERE case_id=?",result.id());
        if(!existing.isEmpty()){if(!Objects.equals(category,existing.get(0).get("category"))||!Objects.equals(features,existing.get(0).get("location_features")))throw new BusinessException("CASE_CONFLICT","Повторный запрос содержит другие уточнения");}
        else jdbc.update("INSERT INTO case_problem_details(case_id,category,location_features) VALUES (?,?,?)",result.id(),category,features);
        if(kind!=CaseKind.PAYMENT)registry.configure(result.id(),input.category()==null?ReportCategory.OTHER:input.category(),features,location);
        return cases.get(result.id(),user);
    }
    @Transactional
    public ResidentCaseService.CaseView joinShared(String id,Input input,String requestId,String user){
        check(input,user);
        if(!jdbc.queryForList("SELECT case_id FROM shared_request_commands WHERE user_id=? AND client_request_id=?",user,requestId).isEmpty())return shared.join(id,input,requestId,user);
        if(search(input,user).stream().noneMatch(m->m.id().equals(id)&&(m.source()==Source.SHARED||m.source()==Source.OWN_SHARED)))throw new BusinessException("ISSUE_NOT_ACTIVE","Совпадение изменилось. Повторите поиск");
        return shared.join(id,input,requestId,user);
    }
}
