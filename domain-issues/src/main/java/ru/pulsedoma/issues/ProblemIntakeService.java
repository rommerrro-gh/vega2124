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
    public enum Source { ISSUE, OWN_ISSUE, OWN_CASE }
    public record Input(String houseId, String description, ReportCategory category, String location, LocationFeatures locationFeatures) {}
    public record Match(Source source,String id,String description,String location,double score,List<String> reasons) {}
    private final JdbcTemplate jdbc;
    private final ResidentCaseService cases;
    private final CandidateRetriever retriever;
    private final TextNormalizer normalizer;
    private final MiniAppService issues;
    private final ReportService reports;
    private final ObjectMapper mapper;
    public ProblemIntakeService(JdbcTemplate jdbc,ResidentCaseService cases,CandidateRetriever retriever,TextNormalizer normalizer,MiniAppService issues,ReportService reports,ObjectMapper mapper) {
        this.jdbc=jdbc;this.cases=cases;this.retriever=retriever;this.normalizer=normalizer;this.issues=issues;this.reports=reports;this.mapper=mapper;
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
        String normalized=normalizer.normalize(input.description());
        String category=input.category()==null?null:input.category().name();
        Instant since=Instant.now().minus(Duration.ofDays(30));
        var found=input.locationFeatures()==null ? retriever.findCandidates(input.houseId(),normalized,category,input.location()==null?null:normalizer.normalize(input.location()),since)
            : retriever.findCandidates(input.houseId(),normalized,category,input.locationFeatures(),since);
        List<Match> matches=new ArrayList<>();
        for(var item:found)if(item.score()>=.65){var summary=issues.candidateSummary(item.issueId(),user);boolean own=jdbc.queryForObject("SELECT count(*) FROM issue_participants WHERE issue_id=? AND user_id=?",Integer.class,item.issueId(),user)>0;matches.add(new Match(own?Source.OWN_ISSUE:Source.ISSUE,item.issueId(),summary.description(),summary.location(),item.score(),item.reasons()));}
        // Private cases are searched only for their author. No neighbour's text or documents enter the response.
        for(var row:jdbc.queryForList("SELECT c.*,strftime('%s',c.created_at) AS created_epoch,d.category,d.location_features FROM resident_cases c LEFT JOIN case_problem_details d ON d.case_id=c.id WHERE c.house_id=? AND c.author_id=? AND c.kind IN ('PROBLEM','WARRANTY') AND c.status<>'CLOSED' AND datetime(c.created_at)>=datetime(?) AND datetime(c.created_at)<=datetime('now') ORDER BY c.updated_at DESC LIMIT 500",input.houseId(),user,since.toString())) {
            String storedCategory=(String)row.get("category");
            if(category!=null&&storedCategory!=null&&!category.equals(storedCategory))continue;
            double place=.0;List<String> reasons=new ArrayList<>(List.of("own_case","same_house"));
            var other=features((String)row.get("location_features"));
            if(input.locationFeatures()!=null&&other!=null&&category!=null){var m=new StructuredLocationMatcher().compare(category,input.locationFeatures(),other);if(!m.compatible())continue;place=m.score();reasons.addAll(m.reasons());}
            else if(input.location()!=null&&row.get("location")!=null){var m=new LocationMatcher().compare(category,input.location(),(String)row.get("location"));if(!m.compatible())continue;place=m.score();reasons.addAll(m.reasons());}
            Set<String> a=new HashSet<>(Arrays.asList(normalized.split("\\s+"))),b=new HashSet<>(Arrays.asList(normalizer.normalize((String)row.get("description")).split("\\s+")));
            var common=new HashSet<>(a);common.retainAll(b);var union=new HashSet<>(a);union.addAll(b);
            double text=union.isEmpty()?0:(double)common.size()/union.size();
            double recency=Math.max(0,1-(double)Duration.between(Instant.ofEpochSecond(Long.parseLong(row.get("created_epoch").toString())),Instant.now()).toHours()/720);
            double score=category!=null&&category.equals(storedCategory)&&other!=null&&input.locationFeatures()!=null ? .4+.35*place+.15*recency+.1*text : .7*text+.3*place;
            if(score>=.65)matches.add(new Match(Source.OWN_CASE,(String)row.get("id"),(String)row.get("description"),(String)row.get("location"),score,reasons));
        }
        return matches.stream().sorted(Comparator.comparingDouble(Match::score).reversed()).limit(10).toList();
    }
    @Transactional
    public ResidentCaseService.CaseView create(Input input,CaseKind kind,String requestId,String user) {
        check(input,user);
        if(kind!=CaseKind.PROBLEM&&(input.category()!=null||input.locationFeatures()!=null))throw new BusinessException("INVALID_CASE","Уточнения темы доступны для проблемы дома");
        String location=input.locationFeatures()==null?input.location():input.locationFeatures().label();
        var result=cases.create(input.houseId(),kind,input.description(),location,requestId,user);
        String category=input.category()==null?null:input.category().name(),features=input.locationFeatures()==null?null:json(input.locationFeatures());
        var existing=jdbc.queryForList("SELECT category,location_features FROM case_problem_details WHERE case_id=?",result.id());
        if(!existing.isEmpty()){if(!Objects.equals(category,existing.get(0).get("category"))||!Objects.equals(features,existing.get(0).get("location_features")))throw new BusinessException("CASE_CONFLICT","Повторный запрос содержит другие уточнения");}
        else jdbc.update("INSERT INTO case_problem_details(case_id,category,location_features) VALUES (?,?,?)",result.id(),category,features);
        return cases.get(result.id(),user);
    }
    public record Joined(String reportId,MiniAppService.IssueView issue) {}
    @Transactional
    public Joined join(Input input,String issueId,String requestId,String user) {
        check(input,user);String body=json(List.of(input,issueId));
        var existing=jdbc.queryForList("SELECT * FROM problem_join_requests WHERE user_id=? AND request_id=?",user,requestId);
        if(!existing.isEmpty()){var row=existing.get(0);if(!body.equals(row.get("request_body")))throw new BusinessException("CASE_CONFLICT","Повторный запрос содержит другое обращение");return new Joined((String)row.get("report_id"),issues.issue((String)row.get("issue_id"),user));}
        if(search(input,user).stream().noneMatch(m->m.source()==Source.ISSUE&&m.id().equals(issueId)))throw new BusinessException("ISSUE_NOT_ACTIVE","Заявка изменилась. Повторите поиск");
        String location=input.locationFeatures()==null?input.location():input.locationFeatures().label();
        var report=reports.createReport(new CreateReportCommand(input.houseId(),user,input.description(),input.category()==null?ReportCategory.OTHER.name():input.category().name(),location,null,input.locationFeatures()));
        var issue=issues.joinIssue(issueId,report.id,user);
        jdbc.update("INSERT INTO problem_join_requests(user_id,request_id,request_body,issue_id,report_id) VALUES (?,?,?,?,?)",user,requestId,body,issueId,report.id);
        return new Joined(report.id,issue);
    }
}
