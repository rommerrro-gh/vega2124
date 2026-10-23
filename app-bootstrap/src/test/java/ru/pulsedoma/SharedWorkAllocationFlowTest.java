package ru.pulsedoma;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import ru.pulsedoma.issues.*;
import java.nio.file.*;import java.util.*;import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest @ActiveProfiles("demo")
class SharedWorkAllocationFlowTest {
 static final Path DB=temp();static Path temp(){try{return Files.createTempFile("independent-work-",".db");}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);r.add("operations.scheduler-enabled",()->false);}
 @Autowired ResidentCaseService cases;@Autowired SharedRequestService shared;@Autowired ProblemIntakeService intake;@Autowired JdbcTemplate db;
 static final String A="demo-resident-1",B="work-neighbour",D="demo-dispatcher-1",H="demo-house-1";
 @BeforeEach void setup(){db.update("INSERT OR IGNORE INTO users(id,display_name,status) VALUES (?,'Сосед','ACTIVE')",B);db.update("INSERT OR IGNORE INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES (?,?,'RESIDENT','VERIFIED','ACTIVE')",H,B);}
 String key(){return UUID.randomUUID().toString();}
 ResidentCaseService.CaseView create(){return intake.create(new ProblemIntakeService.Input(H,"Протечка "+key(),ReportCategory.WATER,null,null),CaseKind.PROBLEM,key(),A);}
 ResidentCaseService.CaseView join(ResidentCaseService.CaseView a){return shared.join(a.sharedRequestId(),new ProblemIntakeService.Input(H,a.description(),ReportCategory.WATER,null,null),key(),B);}
 String work(String owner){String id=key();db.update("INSERT INTO case_work_orders(id,case_id,title,scope,assignee_id,basis_type,basis,due_basis,state,created_by,created_at,updated_at) VALUES (?,?,'Проверка','Общая работа',?,'MAINTENANCE','Основание','План','ASSIGNED',?,?,?)",id,owner,D,D,Instant.now().toString(),Instant.now().toString());return id;}
 @Test void firstReportCanSplitWhileCommonWorkStays(){var a=create();var b=join(a);assertNotEquals(a.id(),a.workflowCaseId());assertNotEquals(a.id(),a.sharedRequestId());String w=work(a.workflowCaseId());var g=shared.get(a.sharedRequestId(),D);var next=shared.split(g.id(),a.id(),g.revision(),"Разные источники",List.of(new SharedWorkRegistry.Allocation(SharedWorkRegistry.Kind.WORK_ORDER,w,0,false)),D);assertEquals(1,next.participants());assertEquals(1,shared.get(g.id(),D).participants());assertEquals(g.id(),cases.get(b.id(),B).sharedRequestId());assertEquals(a.workflowCaseId(),db.queryForObject("SELECT case_id FROM case_work_orders WHERE id=?",String.class,w));assertNotEquals(a.workflowCaseId(),cases.get(a.id(),A).workflowCaseId());}
 @Test void explicitWorkTransferDoesNotMoveResidentsDocuments(){var a=create();join(a);String w=work(a.workflowCaseId());byte[] pdf="%PDF-1.4 secret\n%%EOF".getBytes();var own=cases.upload(a.id(),null,"Личный документ",pdf,"application/pdf",0,A);String version=own.documents().getFirst().versions().getFirst().id();var g=shared.get(a.sharedRequestId(),D);var split=shared.split(g.id(),a.id(),g.revision(),"Работа относится к этому дефекту",List.of(new SharedWorkRegistry.Allocation(SharedWorkRegistry.Kind.WORK_ORDER,w,0,true)),D);assertEquals(cases.get(a.id(),A).workflowCaseId(),db.queryForObject("SELECT case_id FROM case_work_orders WHERE id=?",String.class,w));assertThrows(RuntimeException.class,()->cases.download(version,B));assertEquals(a.id(),split.myCaseId()==null?a.id():split.myCaseId());}
 @Test void omissionOrStaleWorkDecisionRollsBackSplit(){var a=create();join(a);String w=work(a.workflowCaseId());var g=shared.get(a.sharedRequestId(),D);assertThrows(RuntimeException.class,()->shared.split(g.id(),a.id(),g.revision(),"Нет распределения",List.of(),D));assertEquals(2,shared.get(g.id(),D).participants());assertThrows(RuntimeException.class,()->shared.split(g.id(),a.id(),g.revision(),"Устарело",List.of(new SharedWorkRegistry.Allocation(SharedWorkRegistry.Kind.WORK_ORDER,w,99,true)),D));assertEquals(g.id(),cases.get(a.id(),A).sharedRequestId());}
 @Test void mergeRetainsBothCommonWorkOrders(){var a=create();var b=create();String first=work(a.workflowCaseId()),second=work(b.workflowCaseId());var merged=shared.merge(b.sharedRequestId(),a.sharedRequestId(),shared.get(b.sharedRequestId(),D).revision(),shared.get(a.sharedRequestId(),D).revision(),"Одна протечка",D);assertEquals(2,merged.work().size());for(String id:List.of(first,second))assertEquals(a.workflowCaseId(),db.queryForObject("SELECT case_id FROM case_work_orders WHERE id=?",String.class,id));}
}
