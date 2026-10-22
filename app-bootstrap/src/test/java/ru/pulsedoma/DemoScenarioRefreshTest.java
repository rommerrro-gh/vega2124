package ru.pulsedoma;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import ru.pulsedoma.issues.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest @ActiveProfiles("demo")
class DemoScenarioRefreshTest {
 static final Path DB=temp();static Path temp(){try{return Files.createTempFile("scenario-refresh-",".db");}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);r.add("operations.scheduler-enabled",()->false);}
 @Autowired DemoScenarioService scenarios;@Autowired JdbcTemplate db;@Autowired ResidentCaseService cases;
 @Test void authorizedLegacyRemovalPreservesModernCasesAndAccounts(){
  var c=cases.create("demo-house-1",CaseKind.PROBLEM,"Новое обращение",null,UUID.randomUUID().toString(),"demo-resident-1");int users=db.queryForObject("SELECT count(*) FROM users",Integer.class);
  db.update("INSERT INTO reports(id,house_id,author_id,raw_text) VALUES ('legacy-only','demo-house-2','demo-resident-1','Старая запись')");
  assertTrue(scenarios.removeLegacyData()>0);assertEquals(0,db.queryForObject("SELECT count(*) FROM reports",Integer.class));assertEquals(c.id(),cases.get(c.id(),"demo-resident-1").id());assertEquals(users,db.queryForObject("SELECT count(*) FROM users",Integer.class));
 }
 @Test void repeatableDemoDataPreservesIdentitiesAndForeignHouseAndCreatesNoDeliveries(){
  int users=db.queryForObject("SELECT count(*) FROM users",Integer.class);scenarios.refresh("demo-house-1","demo-resident-1","demo-dispatcher-1","demo-uk-1");
  int before=db.queryForObject("SELECT count(*) FROM resident_cases WHERE house_id='demo-house-1'",Integer.class);assertTrue(before>=8);
  var foreign=cases.create("demo-house-1",CaseKind.PROBLEM,"Проверка",null,UUID.randomUUID().toString(),"demo-resident-1");
  db.update("INSERT INTO houses(id,address) VALUES ('protected-house','Реальный дом')");
  db.update("INSERT INTO resident_cases(id,house_id,author_id,kind,status,description,client_request_id,request_hash,next_step,created_at,updated_at) VALUES ('protected-case','protected-house','demo-resident-1','PROBLEM','IN_REVIEW','Не удалять','p','p','Рассмотрение',datetime('now'),datetime('now'))");
  scenarios.refresh("demo-house-1","demo-resident-1","demo-dispatcher-1","demo-uk-1");
  assertEquals(before,db.queryForObject("SELECT count(*) FROM resident_cases WHERE house_id='demo-house-1'",Integer.class));assertEquals(1,db.queryForObject("SELECT count(*) FROM resident_cases WHERE id='protected-case'",Integer.class));
  assertEquals(users+2,db.queryForObject("SELECT count(*) FROM users",Integer.class));assertEquals(0,db.queryForObject("SELECT count(*) FROM max_bot_outbox",Integer.class));assertEquals(0,db.queryForObject("SELECT count(*) FROM operations_notifications",Integer.class));
  assertEquals(1,db.queryForObject("SELECT count(*) FROM case_visits WHERE state='OFFERED'",Integer.class));assertEquals(5,db.queryForObject("SELECT count(*) FROM shared_requests WHERE house_id='demo-house-1'",Integer.class)); // five common problems plus separate billing
  assertThrows(RuntimeException.class,()->scenarios.refresh("protected-house","demo-resident-1","demo-dispatcher-1","demo-uk-1"));
 }
}
