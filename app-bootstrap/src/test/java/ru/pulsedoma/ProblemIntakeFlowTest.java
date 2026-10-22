package ru.pulsedoma;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import ru.pulsedoma.issues.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("demo")
class ProblemIntakeFlowTest {
    static final Path DB=temp();
    static Path temp(){try{return Files.createTempFile("intake-",".db");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);r.add("operations.scheduler-enabled",()->false);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;
    @Autowired ReportService reports;@Autowired MiniAppService issues;@Autowired ResidentCaseService cases;
    Map<String,Object> input(){return new HashMap<>(Map.of("houseId","demo-house-1","description","Не горит свет в подъезде","category","LIGHTING","locationFeatures",Map.of("area","ENTRANCE","entrance",1,"floor",2)));}
    JsonNode postJson(String path,Object body,int code)throws Exception{return mapper.readTree(mvc.perform(post(path).header("X-Demo-Session","true").contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().is(code)).andReturn().getResponse().getContentAsByteArray());}
    @BeforeEach void neighbour(){
        jdbc.update("INSERT OR IGNORE INTO users(id,display_name,status) VALUES ('intake-neighbour','Сосед','ACTIVE')");
        jdbc.update("INSERT OR IGNORE INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES ('demo-house-1','intake-neighbour','RESIDENT','VERIFIED','ACTIVE')");
    }
    @Test void searchHasNoWritesFiltersPlacesAndJoinIsIdempotent()throws Exception {
        var place=new ru.pulsedoma.duplicates.LocationFeatures(ru.pulsedoma.duplicates.LocationFeatures.Area.ENTRANCE,1,2,null,null,null,null,null,null);
        var report=reports.createReport(new CreateReportCommand("demo-house-1","intake-neighbour","Не горит свет в подъезде","LIGHTING",null,null,place));
        var issue=issues.createIssue(report.id,"intake-neighbour");
        int before=jdbc.queryForObject("SELECT count(*) FROM reports",Integer.class);
        var found=postJson("/v1/cases/similar-problems",input(),200);
        assertTrue(found.toString().contains(issue.id()));
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM reports",Integer.class));
        var different=input();different.put("locationFeatures",Map.of("area","ENTRANCE","entrance",2,"floor",2));
        assertFalse(postJson("/v1/cases/similar-problems",different,200).toString().contains(issue.id()));
        postJson("/v1/cases/join-problem",Map.of("problem",different,"issueId",issue.id(),"clientRequestId",UUID.randomUUID().toString()),409);
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM reports",Integer.class));
        String key=UUID.randomUUID().toString();var join=new HashMap<String,Object>(Map.of("problem",input(),"issueId",issue.id(),"clientRequestId",key));
        var first=postJson("/v1/cases/join-problem",join,200);var retry=postJson("/v1/cases/join-problem",join,200);
        assertEquals(first.path("reportId"),retry.path("reportId"));assertEquals(before+1,jdbc.queryForObject("SELECT count(*) FROM reports",Integer.class));
        assertTrue(postJson("/v1/cases/similar-problems",input(),200).toString().contains("OWN_ISSUE"));
        join.put("problem",different);postJson("/v1/cases/join-problem",join,409);
    }
    @Test void newCaseRemainsInSearchAndPrivateNeighbourAndPaymentCasesDoNotLeak()throws Exception {
        var create=input();create.put("kind","PROBLEM");create.put("clientRequestId",UUID.randomUUID().toString());
        var first=postJson("/v1/cases",create,201);var second=postJson("/v1/cases",create,201);assertEquals(first.path("id"),second.path("id"));assertEquals("LIGHTING",first.path("category").asText());
        jdbc.update("UPDATE resident_cases SET created_at=datetime('now') WHERE id=?",first.path("id").asText());
        assertTrue(postJson("/v1/cases/similar-problems",input(),200).toString().contains(first.path("id").asText()));
        create.put("category","OTHER");create.remove("locationFeatures");postJson("/v1/cases",create,409);
        jdbc.update("INSERT OR IGNORE INTO users(id,display_name,status) VALUES ('intake-neighbour','Сосед','ACTIVE')");
        jdbc.update("INSERT OR IGNORE INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES ('demo-house-1','intake-neighbour','RESIDENT','VERIFIED','ACTIVE')");
        var neighbour=cases.create("demo-house-1",CaseKind.PROBLEM,"Не горит свет в подъезде",null,UUID.randomUUID().toString(),"intake-neighbour");
        var payment=cases.create("demo-house-1",CaseKind.PAYMENT,"Не горит свет в подъезде",null,UUID.randomUUID().toString(),"demo-resident-1");
        String found=postJson("/v1/cases/similar-problems",input(),200).toString();assertFalse(found.contains(neighbour.id()));assertFalse(found.contains(payment.id()));
    }
    @Test void unknownFieldsAreOptionalAndBadOrForeignRequestsRejected()throws Exception {
        var unknown=new HashMap<String,Object>(Map.of("houseId","demo-house-1","description","Мокнет стена, не знаю почему"));
        postJson("/v1/cases/similar-problems",unknown,200);unknown.put("kind","PROBLEM");unknown.put("clientRequestId",UUID.randomUUID().toString());postJson("/v1/cases",unknown,201);
        var invalid=input();invalid.put("locationFeatures",Map.of("area","ENTRANCE","entrance",0,"floor",2));postJson("/v1/cases/similar-problems",invalid,400);
        var foreign=input();foreign.put("houseId","not-my-house");postJson("/v1/cases/similar-problems",foreign,403);
        mvc.perform(post("/v1/cases/similar-problems").contentType("application/json").content(mapper.writeValueAsString(input()))).andExpect(status().isUnauthorized());
    }
}
