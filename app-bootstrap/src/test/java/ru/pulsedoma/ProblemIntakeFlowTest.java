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
    @Autowired ProblemIntakeService intake;@Autowired ResidentCaseService cases;
    @Test void categoryRequiredOnServerAndRetiredKindRejected()throws Exception{
      for(String kind:List.of("PROBLEM","GENERAL"))mvc.perform(post("/v1/cases").header("X-Demo-Session","true").contentType("application/json").content(mapper.writeValueAsString(Map.of("houseId","demo-house-1","description","Протечка","kind",kind,"clientRequestId",UUID.randomUUID().toString())))).andExpect(status().isBadRequest());
    }
    @Test void searchDoesNotCreateReportsAndMatchesOwnSharedCase(){String text="Не горит свет "+UUID.randomUUID();var input=new ProblemIntakeService.Input("demo-house-1",text,ReportCategory.LIGHTING,null,null);var c=intake.create(input,CaseKind.PROBLEM,UUID.randomUUID().toString(),"demo-resident-1");int before=jdbc.queryForObject("SELECT count(*) FROM resident_cases",Integer.class);assertTrue(intake.search(input,"demo-resident-1").stream().anyMatch(m->m.id().equals(c.sharedRequestId())&&m.source()==ProblemIntakeService.Source.OWN_SHARED));assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM resident_cases",Integer.class));}
    @Test void privateCasesRemainPrivateAndPublicDescriptionIsShared(){jdbc.update("INSERT OR IGNORE INTO users(id,display_name,status) VALUES ('intake-neighbour','Сосед','ACTIVE')");jdbc.update("INSERT OR IGNORE INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES ('demo-house-1','intake-neighbour','RESIDENT','VERIFIED','ACTIVE')");var input=new ProblemIntakeService.Input("demo-house-1","Протечка в подъезде",ReportCategory.WATER,null,null);var c=intake.create(input,CaseKind.PROBLEM,UUID.randomUUID().toString(),"intake-neighbour");assertThrows(RuntimeException.class,()->cases.get(c.id(),"demo-resident-1"));assertTrue(intake.search(input,"demo-resident-1").stream().anyMatch(m->m.id().equals(c.sharedRequestId())));assertThrows(RuntimeException.class,()->cases.get(c.workflowCaseId(),"intake-neighbour"));}
}
