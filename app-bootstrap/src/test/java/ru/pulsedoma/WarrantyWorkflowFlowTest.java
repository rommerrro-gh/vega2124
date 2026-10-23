package ru.pulsedoma;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.issues.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static ru.pulsedoma.issues.WorkflowTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class WarrantyWorkflowFlowTest {
    static final Path DB=temp(),FILES=dir();
    static Path temp(){try{return Files.createTempFile("warranty-flow-", ".db");}catch(Exception e){throw new IllegalStateException(e);}}
    static Path dir(){try{return Files.createTempDirectory("warranty-files-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);r.add("storage.demo.path",FILES::toString);}
    static final String HOUSE="demo-house-1",RESIDENT="demo-resident-1",ADMIN="demo-admin-1",DISPATCHER="demo-dispatcher-1",ENGINEER="workflow-engineer",CONTRACTOR="workflow-contractor",WARRANTY="workflow-warranty";
    @Autowired WarrantyWorkflowService flow;
    @Autowired ResidentCaseService cases;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    String caseId;
    @BeforeEach void setup(){
        for(String u:List.of(ENGINEER,CONTRACTOR,WARRANTY,"workflow-neighbor"))jdbc.update("INSERT OR IGNORE INTO users(id,display_name,status) VALUES (?,?,'ACTIVE')",u,u);
        jdbc.update("UPDATE users SET status='ACTIVE' WHERE id IN (?,?,?,?)",ENGINEER,CONTRACTOR,WARRANTY,RESIDENT);
        jdbc.update("UPDATE house_memberships SET access_status='ACTIVE' WHERE user_id IN (?,?)",RESIDENT,DISPATCHER);
        flow.self(HOUSE,ADMIN);caseId=cases.create(HOUSE,CaseKind.PROBLEM,"После дождя мокнет стена",null,UUID.randomUUID().toString(),RESIDENT).id();
        assign(ADMIN,CaseFunction.COORDINATOR);
        grant(ENGINEER,CaseFunction.ENGINEER);grant(CONTRACTOR,CaseFunction.CONTRACTOR);grant(WARRANTY,CaseFunction.WARRANTY);
        assign(ENGINEER,CaseFunction.ENGINEER);assign(CONTRACTOR,CaseFunction.CONTRACTOR);assign(WARRANTY,CaseFunction.WARRANTY);
    }
    void grant(String user,CaseFunction function){var i=flow.invite(HOUSE,new WorkflowRequests.Invite(function,"Согласованная служба","Подтверждение участия для тестового дома",7),ADMIN);flow.accept(i.token(),user);}
    void assign(String user,CaseFunction function){cases.assign(caseId,user,function,true,cases.get(caseId,DISPATCHER).revision(),ADMIN);}
    int revision(){return cases.get(caseId,DISPATCHER).revision();}
    WarrantyWorkflowService.Workspace view(){return flow.workspace(caseId,ADMIN);}
    WorkflowRequests.Warranty warranty(WarrantyState state,int revision,String reason){return new WorkflowRequests.Warranty("Кровля",WarrantyObject.CONSTRUCTION,state,"Застройщик","Подрядчик","Договор №1, пункт 8","Проверена применимость договора к кровле",LocalDate.of(2020,1,1),LocalDate.of(2020,1,1),LocalDate.of(2021,1,1),revision,reason);}
    WarrantyWorkflowService.Inspection inspection(){flow.planInspection(caseId,null,new WorkflowRequests.Inspection(ENGINEER,"Подъезд 1","Инженер и житель",Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),0,revision(),null),ADMIN);return view().inspections().get(0);}
    WarrantyWorkflowService.Act draft(WarrantyWorkflowService.Inspection i,String reason){
        jdbc.update("UPDATE case_inspections SET starts_at=?,ends_at=? WHERE id=?",Instant.now().minusSeconds(3600).toString(),Instant.now().minusSeconds(60).toString(),i.id());
        flow.draftAct(i.id(),new WorkflowRequests.Act(Instant.now().minusSeconds(120),"Инженер и житель","Следы намокания <script>alert(1)</script>","Вода у примыкания кровли","Причина требует проверки",null,reason,i.revision(),revision()),ENGINEER);
        return flow.workspace(caseId,ENGINEER).inspections().get(0).acts().get(0);
    }
    WarrantyWorkflowService.Act publish(){var i=inspection();var a=draft(i,null);int ir=flow.workspace(caseId,ENGINEER).inspections().get(0).revision();flow.publishAct(a.id(),new WorkflowRequests.Revision(ir,revision()),ENGINEER);return view().inspections().get(0).acts().get(0);}
    WarrantyWorkflowService.WorkOrder work(){flow.createWork(caseId,new WorkflowRequests.Work("Устранить протечку","Проверить и восстановить примыкание",CONTRACTOR,WorkBasis.MAINTENANCE,"Решение по обращению",Instant.now().plusSeconds(86400),"Согласованный рабочий срок",null,revision()),ADMIN);return view().orders().get(0);}
    void workState(String id,WorkState state,String actor){var o=view().orders().stream().filter(w->w.id().equals(id)).findFirst().orElseThrow();flow.updateWork(id,new WorkflowRequests.WorkUpdate(state,"Результат и основание действия",o.assigneeId(),o.dueAt()==null?null:Instant.parse(o.dueAt()),o.dueBasis(),null,o.revision(),revision()),actor);}
    String evidence(){var c=cases.upload(caseId,null,"Подтверждение","%PDF-1.4 test\n%%EOF".getBytes(),"application/pdf",0,RESIDENT);return c.documents().get(0).versions().get(0).id();}
    @Test void sourceConditionsAreVersionedWithoutUniversalFiveYearDefault(){
        var w=flow.saveWarranty(HOUSE,null,warranty(WarrantyState.DRAFT,0,"Внесены сведения"),ADMIN);
        assertEquals("2021-01-01",w.endsOn());assertNull(w.checkedBy());
        var checked=flow.saveWarranty(HOUSE,w.id(),warranty(WarrantyState.VERIFIED,w.revision(),"Проверены договор и объект"),ADMIN);
        assertEquals(ADMIN,checked.checkedBy());assertEquals(2,checked.history().size());
        assertThrows(BusinessException.class,()->flow.saveWarranty(HOUSE,w.id(),warranty(WarrantyState.VERIFIED,w.revision(),"Старая ревизия"),ADMIN));
        assertThrows(BusinessException.class,()->flow.saveWarranty(HOUSE,null,warranty(WarrantyState.DRAFT,0,"Попытка"),RESIDENT));
    }
    @Test void expiredOrUnknownDatesDoNotAutomaticallyRejectResidentsCase(){
        flow.saveWarranty(HOUSE,null,warranty(WarrantyState.VERIFIED,0,"Проверка"),ADMIN);
        assertEquals(CaseStatus.RECEIVED,cases.get(caseId,RESIDENT).status());
        flow.review(caseId,new WorkflowRequests.Review(null,ReviewOutcome.NEED_INFORMATION,null,"Срок уточняется","Нужен осмотр",null,revision()),DISPATCHER);
        assertEquals(CaseStatus.IN_PROGRESS,cases.get(caseId,RESIDENT).status());
        assertTrue(view().inspections().isEmpty());
    }
    @Test void registryDocumentsArePrivateAndScopedToHouse()throws Exception{
        var w=flow.saveWarranty(HOUSE,null,warranty(WarrantyState.DRAFT,0,"Исходные данные"),ADMIN);
        var saved=flow.uploadWarranty(w.id(),"Договор","%PDF-1.4 test\n%%EOF".getBytes(),"application/pdf",w.revision(),ADMIN);
        var id=saved.files().get(0).get("id").toString();assertTrue(flow.warrantyFile(id,WARRANTY).bytes().length>0);
        assertThrows(BusinessException.class,()->flow.warrantyFile(id,RESIDENT));assertThrows(BusinessException.class,()->flow.warrantyFile(id,CONTRACTOR));
        mvc.perform(get("/v1/warranty-documents/"+id).header("X-Demo-Session","true")).andExpect(status().isNotFound());
    }
    @Test void invitationIsOneUseRevocableAndDoesNotCreateResidentMembership(){
        var i=flow.invite(HOUSE,new WorkflowRequests.Invite(CaseFunction.CONTRACTOR,"Подрядчик","Согласованное участие",2),ADMIN);
        flow.accept(i.token(),"workflow-neighbor");flow.accept(i.token(),"workflow-neighbor");
        assertThrows(BusinessException.class,()->flow.accept(i.token(),ENGINEER));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM house_memberships WHERE user_id='workflow-neighbor'",Integer.class));
        var revoked=flow.invite(HOUSE,new WorkflowRequests.Invite(CaseFunction.ENGINEER,"Служба","Осмотр",2),ADMIN);flow.revokeInvitation(revoked.invitation().id(),ADMIN);
        assertThrows(BusinessException.class,()->flow.accept(revoked.token(),"workflow-neighbor"));
    }
    @Test void revokedInvitationCannotRestoreARevokedStaffGrant(){
        var i=flow.invite(HOUSE,new WorkflowRequests.Invite(CaseFunction.CONTRACTOR,"Подрядчик","Работы",2),ADMIN);flow.accept(i.token(),"workflow-neighbor");
        flow.revokeStaff(HOUSE,"workflow-neighbor",CaseFunction.CONTRACTOR,ADMIN);flow.accept(i.token(),"workflow-neighbor");
        assertTrue(flow.houses("workflow-neighbor").isEmpty());
    }
    @Test void specialistSeesOnlyAssignedCasesAndRevocationBlocksOldLinks(){
        var other=cases.create(HOUSE,CaseKind.PROBLEM,"Чужое дело",null,UUID.randomUUID().toString(),RESIDENT);
        assertThrows(BusinessException.class,()->flow.workspace(other.id(),ENGINEER));
        assertFalse(flow.workspace(caseId,ENGINEER).canCoordinate());flow.revokeStaff(HOUSE,ENGINEER,CaseFunction.ENGINEER,ADMIN);
        assertThrows(BusinessException.class,()->flow.workspace(caseId,ENGINEER));assertThrows(BusinessException.class,()->cases.get(caseId,ENGINEER));
    }
    @Test void suspendedOrganizationDoesNotKeepAccessThroughASeparateResidentRole(){
        jdbc.update("INSERT OR IGNORE INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES (?,?,'RESIDENT','VERIFIED','ACTIVE')",HOUSE,ENGINEER);
        jdbc.update("UPDATE organization_houses SET status='SUSPENDED' WHERE house_id=?",HOUSE);
        try{assertThrows(BusinessException.class,()->cases.get(caseId,ENGINEER));assertTrue(cases.get(caseId,RESIDENT).isAuthor());}
        finally{jdbc.update("UPDATE organization_houses SET status='ACTIVE' WHERE house_id=?",HOUSE);}
    }
    @Test void emptyAssignmentQueueIsAvailableWithoutOpeningAllHouseCases(){
        grant("workflow-neighbor",CaseFunction.CONTRACTOR);
        assertTrue(cases.queue(HOUSE,"workflow-neighbor").isEmpty());assertFalse(cases.context(HOUSE,"workflow-neighbor").canReview());
    }
    @Test void warrantyDecisionRequiresAssignedWarrantyFunctionAndEvidence(){
        assertThrows(BusinessException.class,()->flow.review(caseId,new WorkflowRequests.Review(null,ReviewOutcome.NON_WARRANTY,RefusalReason.MISUSE,"Предположение","Позиция",null,revision()),DISPATCHER));
        assertThrows(BusinessException.class,()->flow.review(caseId,new WorkflowRequests.Review(null,ReviewOutcome.NON_WARRANTY,RefusalReason.MISUSE,"Предположение","Позиция",null,revision()),WARRANTY));
        var w=flow.saveWarranty(HOUSE,null,warranty(WarrantyState.VERIFIED,0,"Проверка"),ADMIN);publish();
        flow.review(caseId,new WorkflowRequests.Review(w.id(),ReviewOutcome.WARRANTY_CONFIRMED,null,"Договор и опубликованный акт","Устраним дефект по проверенным условиям",null,revision()),WARRANTY);
        assertEquals(CaseKind.WARRANTY,cases.get(caseId,RESIDENT).kind());assertNotEquals(CaseStatus.CLOSED,cases.get(caseId,RESIDENT).status());
    }
    @Test void ordinaryResidentCannotSeeFinancialCaseOrWarrantyWorkspace()throws Exception{
        var payment=cases.create(HOUSE,CaseKind.PAYMENT,"Личная квитанция",null,UUID.randomUUID().toString(),RESIDENT);
        assertThrows(BusinessException.class,()->flow.workspace(payment.id(),RESIDENT));assertThrows(BusinessException.class,()->cases.get(payment.id(),ENGINEER));
        mvc.perform(get("/v1/cases/"+caseId+"/workflow")).andExpect(status().isUnauthorized());
    }
    @Test void residentCanRequestAnotherTimeWithoutProvidingNewDates(){
        var i=inspection();var response=flow.inspectionResponse(i.id(),new WorkflowRequests.InspectionResponse(ResidentAction.REQUEST_OTHER_TIME,null,null,i.revision(),revision()),RESIDENT);
        assertEquals(Availability.REQUEST_OTHER_TIME,response.inspections().get(0).residentAvailability());
        assertTrue(cases.get(caseId,RESIDENT).nextStep().contains("другое время"));assertEquals(1,response.inspections().get(0).responses().size());
    }
    @Test void reschedulingRequiresReasonAndReconfirmation(){
        var i=inspection();flow.inspectionResponse(i.id(),new WorkflowRequests.InspectionResponse(ResidentAction.CONFIRM_TIME,null,null,i.revision(),revision()),RESIDENT);
        var current=view().inspections().get(0);assertEquals(InspectionState.SCHEDULED,current.state());
        assertThrows(BusinessException.class,()->flow.planInspection(caseId,i.id(),new WorkflowRequests.Inspection(ENGINEER,"Место","Участники",Instant.now().plusSeconds(8000),Instant.now().plusSeconds(9000),current.revision(),revision(),null),ADMIN));
        var moved=flow.planInspection(caseId,i.id(),new WorkflowRequests.Inspection(ENGINEER,"Место","Участники",Instant.now().plusSeconds(8000),Instant.now().plusSeconds(9000),current.revision(),revision(),"Житель просит другой интервал"),ADMIN).inspections().get(0);
        assertEquals(Availability.PENDING,moved.residentAvailability());assertEquals(InspectionState.PROPOSED,moved.state());
    }
    @Test void noAccessCannotBeRecordedBeforeVisitAndDoesNotCloseCase(){
        var i=inspection();assertThrows(BusinessException.class,()->flow.inspectionState(i.id(),new WorkflowRequests.InspectionUpdate(InspectionState.NO_ACCESS,"Не открыли",i.revision(),revision()),ENGINEER));
        jdbc.update("UPDATE case_inspections SET starts_at=? WHERE id=?",Instant.now().minusSeconds(1000).toString(),i.id());
        flow.inspectionState(i.id(),new WorkflowRequests.InspectionUpdate(InspectionState.NO_ACCESS,"Нужно согласовать доступ",i.revision(),revision()),ENGINEER);
        assertEquals(InspectionState.NO_ACCESS,view().inspections().get(0).state());assertNotEquals(CaseStatus.CLOSED,cases.get(caseId,RESIDENT).status());
    }
    @Test void draftActIsPrivatePublishedVersionIsImmutableAndPrintableIsEscaped(){
        var i=inspection();var a=draft(i,null);assertTrue(flow.workspace(caseId,RESIDENT).inspections().get(0).acts().isEmpty());
        var before=view().inspections().get(0);flow.publishAct(a.id(),new WorkflowRequests.Revision(before.revision(),revision()),ENGINEER);
        assertFalse(flow.workspace(caseId,RESIDENT).inspections().get(0).acts().isEmpty());
        String html=flow.printAct(a.id(),RESIDENT);assertTrue(html.contains("&lt;script&gt;"));assertFalse(html.contains("<script>"));assertTrue(html.contains("подписание"));
        assertThrows(BusinessException.class,()->flow.editAct(a.id(),new WorkflowRequests.Act(Instant.now(),"Лица","Изменить","Дефекты",null,null,null,view().inspections().get(0).revision(),revision()),ENGINEER));
    }
    @Test void actCorrectionsKeepPriorVersionAndResidentObjection(){
        var original=publish();var i=view().inspections().get(0);
        flow.inspectionResponse(i.id(),new WorkflowRequests.InspectionResponse(ResidentAction.COMMENT_ACT,original.id(),"Не указали следы у окна",i.revision(),revision()),RESIDENT);
        var current=view().inspections().get(0);var newer=draft(current,"Дополнено по замечанию жителя");
        flow.publishAct(newer.id(),new WorkflowRequests.Revision(view().inspections().get(0).revision(),revision()),ENGINEER);
        var updated=view().inspections().get(0);assertEquals(2,updated.acts().size());assertEquals(1,updated.responses().size());assertTrue(flow.printAct(original.id(),RESIDENT).contains("версия 1"));
    }
    @Test void staleInspectionResponseDoesNotDuplicateHistory(){
        var i=inspection();var request=new WorkflowRequests.InspectionResponse(ResidentAction.CONFIRM_TIME,null,null,i.revision(),revision());flow.inspectionResponse(i.id(),request,RESIDENT);
        assertThrows(BusinessException.class,()->flow.inspectionResponse(i.id(),request,RESIDENT));assertEquals(1,view().inspections().get(0).responses().size());
    }
    @Test void workReportMustBeCheckedAndDoesNotCloseResidentsCase(){
        var o=work();workState(o.id(),WorkState.IN_PROGRESS,CONTRACTOR);workState(o.id(),WorkState.REPORTED,CONTRACTOR);
        assertEquals(1,view().orders().get(0).reports().size());assertNotEquals(CaseStatus.CLOSED,cases.get(caseId,RESIDENT).status());
        assertThrows(BusinessException.class,()->cases.update(caseId,CaseKind.PROBLEM,CaseStatus.RESULT_PROPOSED,"Проверьте",null,null,revision(),DISPATCHER));
        workState(o.id(),WorkState.NEED_REWORK,ADMIN);workState(o.id(),WorkState.IN_PROGRESS,CONTRACTOR);workState(o.id(),WorkState.REPORTED,CONTRACTOR);workState(o.id(),WorkState.ACCEPTED,ADMIN);
        assertEquals(2,view().orders().get(0).reports().size());
        cases.update(caseId,CaseKind.PROBLEM,CaseStatus.RESULT_PROPOSED,"Проверьте результат",null,null,revision(),DISPATCHER);
        cases.respond(caseId,true,"Всё решено",revision(),RESIDENT);assertEquals(CaseStatus.CLOSED,cases.get(caseId,RESIDENT).status());
    }
    @Test void coordinatorEditingReportedTaskCannotForgeAnExecutorsReport(){
        var o=work();workState(o.id(),WorkState.IN_PROGRESS,CONTRACTOR);workState(o.id(),WorkState.REPORTED,CONTRACTOR);
        var current=view().orders().get(0);flow.updateWork(o.id(),new WorkflowRequests.WorkUpdate(WorkState.REPORTED,"Уточнены условия проверки",current.assigneeId(),Instant.parse(current.dueAt()),current.dueBasis(),null,current.revision(),revision()),ADMIN);
        assertEquals(1,view().orders().get(0).reports().size());assertEquals(CONTRACTOR,view().orders().get(0).reports().get(0).get("author"));
    }
    @Test void executorCannotAcceptOwnWorkEvenIfAlsoCoordinator(){
        grant(CONTRACTOR,CaseFunction.COORDINATOR);assign(CONTRACTOR,CaseFunction.COORDINATOR);var o=work();workState(o.id(),WorkState.IN_PROGRESS,CONTRACTOR);workState(o.id(),WorkState.REPORTED,CONTRACTOR);
        assertThrows(BusinessException.class,()->workState(o.id(),WorkState.ACCEPTED,CONTRACTOR));assertEquals(WorkState.REPORTED,view().orders().get(0).state());
    }
    @Test void executorCannotMoveDeadlineOrTakeAnotherEmployeesTask(){
        var o=work();assertThrows(BusinessException.class,()->flow.updateWork(o.id(),new WorkflowRequests.WorkUpdate(WorkState.IN_PROGRESS,"Перенос",o.assigneeId(),Instant.now().plusSeconds(100000),o.dueBasis(),null,o.revision(),revision()),CONTRACTOR));
        assertThrows(BusinessException.class,()->workState(o.id(),WorkState.IN_PROGRESS,ENGINEER));
    }
    @Test void overdueTaskIsVisibleInQueueEvenWithoutACaseDeadline(){
        var o=work();jdbc.update("UPDATE case_work_orders SET due_at=? WHERE id=?",Instant.now().minusSeconds(60).toString(),o.id());
        var c=cases.queue(HOUSE,DISPATCHER).stream().filter(x->x.id().equals(caseId)).findFirst().orElseThrow();
        assertNull(c.dueAt());assertEquals(1,c.openWorkCount());assertEquals(1,c.overdueWorkCount());
        workState(o.id(),WorkState.IN_PROGRESS,CONTRACTOR);workState(o.id(),WorkState.REPORTED,CONTRACTOR);workState(o.id(),WorkState.ACCEPTED,ADMIN);
        assertEquals(0,cases.get(caseId,RESIDENT).overdueWorkCount());
    }
    @Test void reassignmentCannotReuseAnOldExecutorsReport(){
        var o=work();workState(o.id(),WorkState.IN_PROGRESS,CONTRACTOR);workState(o.id(),WorkState.REPORTED,CONTRACTOR);var current=view().orders().get(0);
        assertThrows(BusinessException.class,()->flow.updateWork(o.id(),new WorkflowRequests.WorkUpdate(WorkState.REPORTED,"Смена исполнителя",ENGINEER,Instant.parse(current.dueAt()),current.dueBasis(),null,current.revision(),revision()),ADMIN));
        flow.updateWork(o.id(),new WorkflowRequests.WorkUpdate(WorkState.ASSIGNED,"Назначен инженер для исправления",ENGINEER,Instant.parse(current.dueAt()),current.dueBasis(),null,current.revision(),revision()),ADMIN);
        assertEquals(WorkState.ASSIGNED,view().orders().get(0).state());assertEquals(1,view().orders().get(0).reports().size());
        assertThrows(BusinessException.class,()->workState(o.id(),WorkState.ACCEPTED,ADMIN));
    }
    @Test void activeInspectionAndHandoffPreventPrematureCaseCompletion(){
        inspection();assertThrows(BusinessException.class,()->cases.update(caseId,CaseKind.PROBLEM,CaseStatus.RESULT_PROPOSED,"Готово",null,null,revision(),DISPATCHER));
    }
    @Test void handoffNeedsEvidenceAtEachStepAndPreparationIsNotDelivery(){
        flow.createHandoff(caseId,new WorkflowRequests.Handoff("Застройщик",RecipientType.DEVELOPER,"Проверенный адрес","Договор","Полномочия проверены сотрудником","Просим рассмотреть материалы",null,revision()),ADMIN);
        var h=view().handoffs().get(0);assertEquals(HandoffState.PREPARED,h.state());
        assertThrows(BusinessException.class,()->flow.handoff(h.id(),new WorkflowRequests.HandoffUpdate(HandoffState.RECEIVED,"Получено",null,h.revision(),revision()),ADMIN));
        assertThrows(BusinessException.class,()->flow.handoff(h.id(),new WorkflowRequests.HandoffUpdate(HandoffState.SENT,"Отправлено",null,h.revision(),revision()),ADMIN));
        String doc=evidence();for(HandoffState state:List.of(HandoffState.SENT,HandoffState.RECEIVED,HandoffState.RESPONDED)){var latest=view().handoffs().get(0);flow.handoff(h.id(),new WorkflowRequests.HandoffUpdate(state,"Подтверждено приложенным документом",doc,latest.revision(),revision()),ADMIN);}
        assertEquals(3,view().handoffs().get(0).events().size());assertTrue(flow.printHandoff(h.id(),ADMIN).contains("не означает отправку"));
    }
    @Test void fileFromAnotherCaseCannotProveHandoffOrInspection(){
        var other=cases.create(HOUSE,CaseKind.PROBLEM,"Другое дело",null,UUID.randomUUID().toString(),RESIDENT);var withFile=cases.upload(other.id(),null,"Файл","%PDF-1.4 test\n%%EOF".getBytes(),"application/pdf",0,RESIDENT);String evidence=withFile.documents().get(0).versions().get(0).id();
        assertThrows(BusinessException.class,()->flow.review(caseId,new WorkflowRequests.Review(null,ReviewOutcome.NEED_INFORMATION,null,"Основание","Вопрос",evidence,revision()),DISPATCHER));
    }
    @Test void dispatcherCanAssignExecutorButCannotGrantWarrantyAuthority(){
        cases.assign(caseId,CONTRACTOR,CaseFunction.CONTRACTOR,true,revision(),DISPATCHER);
        assertThrows(BusinessException.class,()->cases.assign(caseId,DISPATCHER,CaseFunction.WARRANTY,true,revision(),DISPATCHER));
    }
    @Test void apiValidatesRequestsAndKeepsPrintsPrivate()throws Exception{
        mvc.perform(post("/v1/cases/"+caseId+"/inspections").header("X-Demo-Session","dispatcher").contentType("application/json").content("{}")) .andExpect(status().isBadRequest());
        var a=publish();mvc.perform(get("/v1/inspection-acts/"+a.id()+"/print").header("X-Demo-Session","true")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get("/v1/inspection-acts/"+a.id()+"/print")).andExpect(status().isUnauthorized());
    }
}
