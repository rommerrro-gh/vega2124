package ru.pulsedoma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.pulsedoma.issues.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class ResidentCasesFlowTest {
    static final Path DB=temp("cases-test-", ".db"), FILES=directory();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);
        r.add("storage.demo.path",FILES::toString);
    }
    static Path temp(String prefix,String suffix){try{return Files.createTempFile(prefix,suffix);}catch(Exception e){throw new IllegalStateException(e);}}
    static Path directory(){try{return Files.createTempDirectory("case-files-");}catch(Exception e){throw new IllegalStateException(e);}}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ResidentCaseService cases;
    @Autowired ru.pulsedoma.house.AccessActivationService access;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM case_billing_permissions");
        for(String user:List.of("other-resident","contractor","outside-user")) {
            jdbc.update("INSERT OR IGNORE INTO users(id,display_name,status) VALUES (?,?,'ACTIVE')",user,user);
        }
        for(String user:List.of("other-resident","contractor"))jdbc.update("""
            INSERT INTO house_memberships(house_id,user_id,role,verification_status,access_status)
            VALUES ('demo-house-1',?,'RESIDENT','VERIFIED','ACTIVE')
            ON CONFLICT(house_id,user_id,role) DO UPDATE SET access_status='ACTIVE'
            """,user);
        jdbc.update("UPDATE house_memberships SET access_status='ACTIVE' WHERE user_id IN ('demo-resident-1','demo-dispatcher-1')");
    }
    ResidentCaseService.CaseView create(CaseKind kind){return cases.create("demo-house-1",kind,"Не знаю, почему мокнет стена",null,UUID.randomUUID().toString(),"demo-resident-1");}
    JsonNode json(byte[] bytes)throws Exception{return mapper.readTree(bytes);}

    @Test void freeTextWithOtherNeedsNoLocationRecipientOrDocumentAndRetriesDoNotDuplicate() throws Exception {
        String key=UUID.randomUUID().toString();
        String body=mapper.writeValueAsString(Map.of("houseId","demo-house-1","description","Мокнет стена, не знаю почему","clientRequestId",key,"kind","PROBLEM","category","OTHER"));
        var first=json(mvc.perform(post("/v1/cases").header("X-Demo-Session","true").contentType("application/json").content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        var second=json(mvc.perform(post("/v1/cases").header("X-Demo-Session","true").contentType("application/json").content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(first.path("id"),second.path("id"));
        assertEquals("PROBLEM",first.path("kind").asText());
        assertTrue(first.path("location").isNull());
        assertEquals(1,first.path("events").size());
        mvc.perform(post("/v1/cases").header("X-Demo-Session","true").contentType("application/json")
            .content(body.replace("Мокнет стена, не знаю почему","Другой вопрос"))).andExpect(status().isConflict());
    }
    @Test void rejectsUnknownHomeUnauthenticatedAndEmptyRequests()throws Exception {
        mvc.perform(get("/v1/cases")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/cases").header("X-Demo-Session","true").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        assertThrows(RuntimeException.class,()->cases.create("unrelated-house",CaseKind.PROBLEM,"Проблема",null,"x","demo-resident-1"));
        assertThrows(RuntimeException.class,()->cases.create("demo-house-1",CaseKind.WARRANTY,"Дефект",null,"x","demo-resident-1"));
        mvc.perform(get("/v1/houses/demo-house-2/case-context").header("X-Demo-Session","true"))
            .andExpect(jsonPath("$.intakeAvailable").value(false));
    }
    @Test void billingContentsAndDocumentsRequireSeparateRevocablePermission()throws Exception {
        var c=create(CaseKind.PAYMENT);
        cases.upload(c.id(),null,"Квитанция",pdf("private"),"application/pdf",0,"demo-resident-1");
        var hidden=cases.queue("demo-house-1","demo-dispatcher-1").stream().filter(i->i.id().equals(c.id())).findFirst().orElseThrow();
        assertFalse(hidden.fullAccess());assertFalse(hidden.description().contains("мокнет"));assertTrue(hidden.documents().isEmpty());assertTrue(hidden.events().isEmpty());
        mvc.perform(get("/v1/cases/"+c.id()).header("X-Demo-Session","dispatcher")).andExpect(status().isNotFound());
        mvc.perform(put("/v1/houses/demo-house-1/case-billing-permissions/demo-dispatcher-1").header("X-Demo-Session","dispatcher")
            .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isForbidden());
        cases.permission("demo-house-1","demo-dispatcher-1",true,"demo-admin-1");
        var visible=cases.get(c.id(),"demo-dispatcher-1");assertTrue(visible.fullAccess());assertEquals(1,visible.documents().size());
        String version=visible.documents().get(0).versions().get(0).id();
        mvc.perform(get("/v1/case-document-versions/"+version).header("X-Demo-Session","dispatcher"))
            .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(header().string("Content-Disposition","attachment; filename=\"document.pdf\""));
        cases.permission("demo-house-1","demo-dispatcher-1",false,"demo-admin-1");
        mvc.perform(get("/v1/case-document-versions/"+version).header("X-Demo-Session","dispatcher")).andExpect(status().isNotFound());
        assertThrows(RuntimeException.class,()->cases.get(c.id(),"other-resident"));
        assertThrows(RuntimeException.class,()->cases.get(c.id(),"demo-admin-1"));
    }
    @Test void onlyManagerWithinHouseMayGrantAccess() {
        assertThrows(RuntimeException.class,()->cases.permission("demo-house-2","demo-resident-1",true,"demo-admin-1"));
        assertThrows(RuntimeException.class,()->cases.permission("demo-house-1","outside-user",true,"demo-admin-1"));
        assertThrows(RuntimeException.class,()->cases.permission("demo-house-1","demo-resident-1",true,"demo-resident-1"));
    }
    @Test void staffRequestsClarificationResidentRepliesAndConfirmsOrReopensResult() {
        var c=create(CaseKind.PROBLEM);
        c=cases.update(c.id(),CaseKind.PROBLEM,CaseStatus.WAITING_RESIDENT,"Уточните, где заметили. Можно ответить, что не знаете",null,"demo-dispatcher-1",c.revision(),"demo-dispatcher-1");
        String id=c.id(),reply=UUID.randomUUID().toString();
        c=cases.message(id,"Не могу определить",reply,"demo-resident-1");
        assertEquals(CaseStatus.IN_REVIEW,c.status());
        assertEquals(c.revision(),cases.message(id,"Не могу определить",reply,"demo-resident-1").revision());
        c=cases.update(id,CaseKind.PROBLEM,CaseStatus.RESULT_PROPOSED,"Осмотр выполнен, проверьте результат",null,"demo-dispatcher-1",c.revision(),"demo-dispatcher-1");
        int revision=c.revision();
        assertThrows(RuntimeException.class,()->cases.respond(id,true,null,revision,"demo-dispatcher-1"));
        c=cases.respond(id,true,null,revision,"demo-resident-1");assertEquals(CaseStatus.CLOSED,c.status());
        c=cases.respond(id,false,"Снова мокнет",c.revision(),"demo-resident-1");assertEquals(CaseStatus.REOPENED,c.status());
    }
    @Test void rejectsStaleRevisionAndNoStaffSelfApproval() {
        var c=create(CaseKind.PROBLEM);String id=c.id();
        cases.update(id,CaseKind.PROBLEM,CaseStatus.IN_REVIEW,"Готовим осмотр",null,null,c.revision(),"demo-dispatcher-1");
        assertThrows(RuntimeException.class,()->cases.update(id,CaseKind.PROBLEM,CaseStatus.IN_PROGRESS,"Другой шаг",null,null,c.revision(),"demo-dispatcher-1"));
        assertThrows(RuntimeException.class,()->cases.update(id,CaseKind.PROBLEM,CaseStatus.CLOSED,"Закрыто",null,null,1,"demo-dispatcher-1"));
    }
    @Test void documentsKeepVersionsAndCheckPermissionsOnEveryDownload() throws Exception {
        var c=create(CaseKind.PROBLEM);
        var first=cases.upload(c.id(),null,"Акт",pdf("v1"),"application/pdf",0,"demo-resident-1");
        String doc=first.documents().get(0).id(),version=first.documents().get(0).versions().get(0).id();
        var second=cases.upload(c.id(),doc,"Акт",pdf("v2"),"application/pdf",1,"demo-dispatcher-1");
        assertEquals(2,second.documents().get(0).versions().size());
        assertArrayEquals(pdf("v1"),cases.download(version,"demo-resident-1").bytes());
        assertThrows(RuntimeException.class,()->cases.download(version,"other-resident"));
        assertThrows(RuntimeException.class,()->cases.upload(c.id(),doc,"Акт",pdf("v3"),"application/pdf",0,"demo-resident-1"));
        assertThrows(RuntimeException.class,()->cases.upload(c.id(),null,"Подмена","<script>".getBytes(),"application/pdf",0,"demo-resident-1"));
        assertThrows(RuntimeException.class,()->cases.upload(c.id(),null,"Большой файл",new byte[10*1024*1024+1],"image/png",0,"demo-resident-1"));
        jdbc.update("UPDATE house_memberships SET access_status='REVOKED' WHERE user_id='demo-resident-1'");
        mvc.perform(get("/v1/case-document-versions/"+version).header("X-Demo-Session","true")).andExpect(status().isNotFound());
    }
    @Test void contributorOnlyReadsAssignedCasesAndCannotApproveWorkOrReadBills() {
        var first=create(CaseKind.PROBLEM);var second=create(CaseKind.PROBLEM);var bill=create(CaseKind.PAYMENT);
        assertThrows(RuntimeException.class,()->cases.get(first.id(),"contractor"));
        var assigned=cases.assign(first.id(),"contractor",CaseFunction.CONTRACTOR,true,first.revision(),"demo-admin-1");
        assertTrue(cases.get(first.id(),"contractor").fullAccess());
        assertFalse(cases.get(first.id(),"contractor").canManage());
        assertThrows(RuntimeException.class,()->cases.get(second.id(),"contractor"));
        assertThrows(RuntimeException.class,()->cases.get(bill.id(),"contractor"));
        assertEquals(1,cases.queue("demo-house-1","contractor").size());
        assertThrows(RuntimeException.class,()->cases.update(first.id(),CaseKind.PROBLEM,CaseStatus.RESULT_PROPOSED,"Готово",null,null,assigned.revision(),"contractor"));
        cases.assign(first.id(),"contractor",CaseFunction.CONTRACTOR,false,assigned.revision(),"demo-admin-1");
        assertThrows(RuntimeException.class,()->cases.get(first.id(),"contractor"));
    }
    @Test void financialCaseCannotBeDowngradedToExposeContents() {
        var c=create(CaseKind.PAYMENT);
        cases.permission("demo-house-1","demo-dispatcher-1",true,"demo-admin-1");
        assertThrows(RuntimeException.class,()->cases.update(c.id(),CaseKind.PROBLEM,CaseStatus.IN_REVIEW,"Общий вопрос",null,null,c.revision(),"demo-dispatcher-1"));
    }
    @Test void revokingStaffRoleClearsExplicitPrivilegesDespiteRemainingResidentRole() {
        jdbc.update("INSERT OR IGNORE INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES ('demo-house-1','demo-dispatcher-1','RESIDENT','VERIFIED','ACTIVE')");
        var c=create(CaseKind.PAYMENT);
        cases.permission("demo-house-1","demo-dispatcher-1",true,"demo-admin-1");
        assertTrue(cases.get(c.id(),"demo-dispatcher-1").fullAccess());
        access.revokeAccess(ru.pulsedoma.identity.AccessRole.DISPATCHER,"demo-dispatcher-1","demo-uk-1","demo-house-1","demo-admin-1");
        assertThrows(RuntimeException.class,()->cases.get(c.id(),"demo-dispatcher-1"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM case_billing_permissions WHERE user_id='demo-dispatcher-1'",Integer.class));
    }
    @Test void uploadThroughApiAndRefreshPreservesExistingCase()throws Exception {
        var c=create(CaseKind.PROBLEM);
        var part=new MockMultipartFile("file","receipt.pdf","application/pdf",pdf("api"));
        mvc.perform(multipart("/v1/cases/"+c.id()+"/documents").file(part).param("title","Документ").header("X-Demo-Session","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.documents[0].versions[0].version").value(1));
        mvc.perform(get("/v1/cases/"+c.id()).header("X-Demo-Session","true")).andExpect(status().isOk());
    }
    @Test void movingKnownDeadlineRequiresReasonAndPreservesHistory() {
        var c=create(CaseKind.PROBLEM);
        var first=java.time.Instant.parse("2026-11-01T10:00:00Z");
        var next=java.time.Instant.parse("2026-11-02T10:00:00Z");
        c=cases.update(c.id(),CaseKind.PROBLEM,CaseStatus.IN_REVIEW,"Готовим осмотр",first,"demo-dispatcher-1",c.revision(),"demo-dispatcher-1");
        String id=c.id();int revision=c.revision();
        assertThrows(RuntimeException.class,()->cases.update(id,CaseKind.PROBLEM,CaseStatus.IN_REVIEW,"Согласуем другое время",next,"demo-dispatcher-1",revision,"demo-dispatcher-1"));
        var changed=cases.update(id,CaseKind.PROBLEM,CaseStatus.IN_REVIEW,"Согласуем другое время",next,"demo-dispatcher-1",revision,"Житель попросил другой день","demo-dispatcher-1");
        assertTrue(changed.events().stream().anyMatch(e->e.text().contains("01.11.2026")));
        assertTrue(changed.events().stream().anyMatch(e->e.text().contains("Житель попросил другой день")));
    }
    static byte[] pdf(String value){return ("%PDF-1.4\n"+value+"\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);}
}
