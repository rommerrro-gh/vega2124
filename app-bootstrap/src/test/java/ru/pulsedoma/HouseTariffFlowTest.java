package ru.pulsedoma;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.issues.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static ru.pulsedoma.issues.TariffTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Transactional
class HouseTariffFlowTest {
    static final Path DB=temp(),FILES=dir();
    static Path temp(){try{return Files.createTempFile("tariff-flow-", ".db");}catch(Exception e){throw new IllegalStateException(e);}}
    static Path dir(){try{return Files.createTempDirectory("tariff-files-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);r.add("storage.demo.path",FILES::toString);}
    static final String HOUSE="demo-house-1",RESIDENT="demo-resident-1",ADMIN="demo-admin-1",DISPATCHER="demo-dispatcher-1";
    @Autowired HouseTariffService tariffs;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @BeforeEach void setup(){
        tariffs.grant(HOUSE,ADMIN,new TariffRequests.Grant(Permission.EDITOR,true,"Подготовка справочника"),ADMIN);
        tariffs.grant(HOUSE,DISPATCHER,new TariffRequests.Grant(Permission.PUBLISHER,true,"Проверка источников"),ADMIN);
    }
    TariffRequests.Draft data(int revision,String amount,String from,String until){return new TariffRequests.Draft("Холодная вода",Category.UTILITY,"Учебный поставщик","Все жилые помещения",Unit.M3,"м³",new BigDecimal(amount),LocalDate.parse(from),until==null?null:LocalDate.parse(until),"Учебное решение №1 от 01.01.2026","https://example.org/source","Учебные сведения для проверки интерфейса",revision,"Внесение сведений");}
    HouseTariffService.Rate draft(){return tariffs.save(HOUSE,null,data(0,"65.125000","2026-01-01",null),ADMIN);}
    HouseTariffService.Rate document(HouseTariffService.Rate r){return tariffs.upload(r.id(),"Общедомовое основание","%PDF-1.4 test\n%%EOF".getBytes(),"application/pdf",r.revision(),ADMIN);}
    HouseTariffService.Rate publish(HouseTariffService.Rate r){return tariffs.publish(r.id(),new TariffRequests.Decision(r.revision(),"Проверены документ, ставка и применимость"),DISPATCHER);}
    HouseTariffService.Catalogue catalogue(String date){return tariffs.catalogue(HOUSE,LocalDate.parse(date),RESIDENT);}
    @Test void residentsSeeOnlyPublishedRatesAndNeverDraftDocuments() throws Exception {
        var r=document(draft());assertTrue(catalogue("2026-06-01").history().isEmpty());
        assertThrows(BusinessException.class,()->tariffs.file(r.files().get(0).id(),RESIDENT));
        mvc.perform(get("/v1/houses/"+HOUSE+"/tariffs/registry").header("X-Demo-Session","true")).andExpect(status().isNotFound());
        publish(r);var c=catalogue("2026-06-01");assertEquals(1,c.rates().size());assertEquals(new BigDecimal("65.125000"),c.rates().get(0).amount());
        assertNull(c.rates().get(0).editedBy());assertTrue(c.rates().get(0).history().stream().allMatch(h->h.snapshot()==null&&h.actor()==null));
        assertTrue(tariffs.file(r.files().get(0).id(),RESIDENT).bytes().length>0);
    }
    @Test void administratorHasNoImplicitPublicationPermission(){
        assertTrue(tariffs.rights(HOUSE,ADMIN).canEdit());assertFalse(tariffs.rights(HOUSE,ADMIN).canPublish());
        var r=document(draft());assertThrows(BusinessException.class,()->tariffs.publish(r.id(),new TariffRequests.Decision(r.revision(),"Попытка"),ADMIN));
        tariffs.grant(HOUSE,ADMIN,new TariffRequests.Grant(Permission.PUBLISHER,true,"Отдельное полномочие"),ADMIN);
        assertThrows(BusinessException.class,()->tariffs.publish(r.id(),new TariffRequests.Decision(r.revision(),"Проверил сам"),ADMIN));
    }
    @Test void documentRequiredAndPublishedContentCannotBeEditedOrReplaced(){
        var r=draft();assertThrows(BusinessException.class,()->publish(r));var p=publish(document(r));
        assertThrows(BusinessException.class,()->tariffs.save(HOUSE,p.id(),data(p.revision(),"70","2026-01-01",null),ADMIN));
        assertThrows(BusinessException.class,()->document(p));assertThrows(BusinessException.class,()->tariffs.removeFile(p.id(),p.files().get(0).id(),p.revision(),ADMIN));
    }
    @Test void newPeriodClosesPreviousWithoutOverwritingItAndFutureIsSeparate(){
        var p=publish(document(draft()));var copied=tariffs.copy(p.id(),new TariffRequests.Copy(p.revision(),LocalDate.parse("2026-11-01"),false,"Новый период"),ADMIN);
        var next=tariffs.save(HOUSE,copied.id(),data(copied.revision(),"70.5","2026-11-01",null),ADMIN);publish(next);
        assertEquals("65.125000",catalogue("2026-10-21").rates().get(0).amount().toPlainString());assertEquals("2026-10-31",catalogue("2026-10-21").rates().get(0).effectiveUntil());
        assertEquals(new BigDecimal("70.5"),catalogue("2026-11-01").rates().get(0).amount());assertEquals(1,catalogue("2026-10-21").upcoming().size());
        assertEquals(new BigDecimal("65.125000"),catalogue("2026-10-21").upcoming().get(0).previousAmount());
        assertTrue(tariffs.listRegistry(HOUSE,ADMIN).stream().anyMatch(r->r.id().equals(p.id())&&r.validUntil()==null));
    }
    @Test void correctionKeepsOriginalAndDocumentsAndSupersedesOnlyAfterPublication(){
        var p=publish(document(draft()));var copied=tariffs.copy(p.id(),new TariffRequests.Copy(p.revision(),LocalDate.parse(p.validFrom()),true,"Исправление опечатки"),ADMIN);
        var fixed=tariffs.save(HOUSE,copied.id(),data(copied.revision(),"64","2026-01-01",null),ADMIN);
        assertEquals(p.id(),catalogue("2026-06-01").rates().get(0).id());publish(fixed);
        var c=catalogue("2026-06-01");assertEquals(1,c.rates().size());assertEquals(new BigDecimal("64"),c.rates().get(0).amount());
        assertEquals(2,c.history().size());assertEquals(State.SUPERSEDED,c.history().stream().filter(r->r.id().equals(p.id())).findFirst().orElseThrow().state());
        assertTrue(tariffs.file(p.files().get(0).id(),RESIDENT).bytes().length>0);
    }
    @Test void withdrawalNeverRevivesAnEarlierRateAndReasonRemainsVisible(){
        var p=publish(document(draft()));var copied=tariffs.copy(p.id(),new TariffRequests.Copy(p.revision(),LocalDate.parse("2026-07-01"),false,"Новый период"),ADMIN);var next=publish(copied);
        tariffs.withdraw(next.id(),new TariffRequests.Decision(next.revision(),"Требуется уточнить применимость"),DISPATCHER);
        assertTrue(catalogue("2026-10-01").rates().isEmpty());assertEquals(1,catalogue("2026-06-30").rates().size());
        assertTrue(catalogue("2026-10-01").history().stream().flatMap(r->r.history().stream()).anyMatch(h->h.action()==Action.WITHDRAWN&&h.reason().contains("применимость")));
    }
    @Test void withdrawnPublicationCanBeCorrectedWithoutRevivingOldData(){
        var p=publish(document(draft()));var withdrawn=tariffs.withdraw(p.id(),new TariffRequests.Decision(p.revision(),"Неверная ставка"),DISPATCHER);
        var copy=tariffs.copy(p.id(),new TariffRequests.Copy(withdrawn.revision(),LocalDate.parse(p.validFrom()),true,"Исправляем"),ADMIN);
        var fixed=tariffs.save(HOUSE,copy.id(),data(copy.revision(),"62","2026-01-01",null),ADMIN);publish(fixed);
        assertEquals(new BigDecimal("62"),catalogue("2026-06-01").rates().get(0).amount());assertEquals(2,catalogue("2026-06-01").history().size());
    }
    @Test void duplicateServiceRequiresNewPeriodButDifferentApplicabilityIsAllowed(){
        publish(document(draft()));var duplicate=document(draft());assertThrows(BusinessException.class,()->publish(duplicate));
        var request=data(duplicate.revision(),"10","2026-01-01",null);
        var distinct=new TariffRequests.Draft(request.title(),request.category(),request.provider(),"Только нежилые помещения",request.unit(),request.unitLabel(),request.amount(),request.validFrom(),request.validUntil(),request.sourceReference(),request.sourceUrl(),request.explanation(),request.revision(),request.reason());
        publish(tariffs.save(HOUSE,duplicate.id(),distinct,ADMIN));assertEquals(2,catalogue("2026-06-01").rates().size());
    }
    @Test void periodGapsAndZeroRatesAreNotInvented(){
        var d=draft();var finite=tariffs.save(HOUSE,d.id(),data(d.revision(),"0","2026-01-01","2026-01-31"),ADMIN);publish(document(finite));
        assertEquals(BigDecimal.ZERO,catalogue("2026-01-20").rates().get(0).amount());assertTrue(catalogue("2026-02-01").rates().isEmpty());assertTrue(catalogue("2025-12-31").rates().isEmpty());
    }
    @Test void revokedPermissionsAndHouseAccessTakeEffectImmediately(){
        var d=draft();tariffs.grant(HOUSE,ADMIN,new TariffRequests.Grant(Permission.EDITOR,false,"Отзыв"),ADMIN);
        assertThrows(BusinessException.class,()->tariffs.save(HOUSE,d.id(),data(d.revision(),"1","2026-01-01",null),ADMIN));

        assertThrows(BusinessException.class,()->tariffs.upload(d.id(),"Нет прав",new byte[]{1},"application/pdf",d.revision(),DISPATCHER));
        tariffs.grant(HOUSE,ADMIN,new TariffRequests.Grant(Permission.EDITOR,true,"Восстановлено"),ADMIN);var p=publish(document(d));
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?",DISPATCHER);
        assertThrows(BusinessException.class,()->tariffs.withdraw(p.id(),new TariffRequests.Decision(p.revision(),"Попытка"),DISPATCHER));
    }
    @Test void suspendedOrganizationDoesNotKeepTariffPermissionsViaResidentMembership(){
        var p=publish(document(draft()));jdbc.update("UPDATE organization_houses SET status='SUSPENDED' WHERE house_id=?",HOUSE);
        assertFalse(tariffs.rights(HOUSE,DISPATCHER).canPublish());assertThrows(BusinessException.class,()->tariffs.withdraw(p.id(),new TariffRequests.Decision(p.revision(),"Попытка"),DISPATCHER));
        assertEquals(1,catalogue("2026-06-01").rates().size());
    }
    @Test void residentAccessRevocationClosesCatalogueAndOldDocumentLinks(){
        var p=publish(document(draft()));jdbc.update("UPDATE house_memberships SET access_status='REVOKED' WHERE house_id=? AND user_id=?",HOUSE,RESIDENT);
        assertThrows(BusinessException.class,()->catalogue("2026-06-01"));assertThrows(BusinessException.class,()->tariffs.file(p.files().get(0).id(),RESIDENT));
    }
    @Test void registryAndDocumentAccessNeverCrossHouseBoundaries(){
        var p=publish(document(draft()));jdbc.update("INSERT INTO users(id,display_name,status) VALUES ('tariff-outsider','Сосед','ACTIVE')");
        assertThrows(BusinessException.class,()->tariffs.catalogue(HOUSE,LocalDate.now(),"tariff-outsider"));assertThrows(BusinessException.class,()->tariffs.file(p.files().get(0).id(),"tariff-outsider"));
        assertThrows(BusinessException.class,()->tariffs.save("unrelated-house",p.id(),data(p.revision(),"1","2026-01-01",null),ADMIN));
    }
    @Test void staleRevisionsFailAndInvalidDateOrSourceIsRejected(){
        var d=draft();var saved=document(d);assertThrows(BusinessException.class,()->tariffs.save(HOUSE,d.id(),data(d.revision(),"1","2026-01-01",null),ADMIN));
        assertThrows(BusinessException.class,()->tariffs.save(HOUSE,saved.id(),data(saved.revision(),"1","2026-02-01","2026-01-01"),ADMIN));
        var invalid=new TariffRequests.Draft("Услуга",Category.OTHER,"Поставщик","Дом",Unit.MONTH,"месяц",BigDecimal.ONE,LocalDate.now(),null,"Документ","javascript:alert(1)","Пояснение",saved.revision(),"Причина");assertThrows(BusinessException.class,()->tariffs.save(HOUSE,saved.id(),invalid,ADMIN));
    }
    @Test void cannotPublishTwoVersionsForSameDateOrMoveCorrectionToAnotherPeriod(){
        var p=publish(document(draft()));var copy=tariffs.copy(p.id(),new TariffRequests.Copy(p.revision(),LocalDate.parse(p.validFrom()),true,"Коррекция"),ADMIN);
        assertThrows(BusinessException.class,()->tariffs.save(HOUSE,copy.id(),data(copy.revision(),"1","2026-02-01",null),ADMIN));
        assertThrows(BusinessException.class,()->tariffs.copy(p.id(),new TariffRequests.Copy(p.revision(),LocalDate.parse("2025-01-01"),false,"Назад"),ADMIN));
        var n=publish(tariffs.copy(p.id(),new TariffRequests.Copy(p.revision(),LocalDate.parse("2026-07-01"),false,"Первый новый"),ADMIN));
        var duplicate=tariffs.copy(p.id(),new TariffRequests.Copy(p.revision(),LocalDate.parse(n.validFrom()),false,"Повтор"),ADMIN);assertThrows(BusinessException.class,()->publish(duplicate));
    }
    @Test void permissionsDoNotGrantPrivateFinancialAccess(){
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM case_billing_permissions WHERE house_id=? AND user_id=?",Integer.class,HOUSE,ADMIN));
        assertThrows(BusinessException.class,()->tariffs.grant(HOUSE,RESIDENT,new TariffRequests.Grant(Permission.EDITOR,true,"Попытка"),RESIDENT));
        assertThrows(BusinessException.class,()->tariffs.grant(HOUSE,RESIDENT,new TariffRequests.Grant(Permission.EDITOR,true,"Житель не сотрудник"),ADMIN));
    }
    @Test void apiValidatesAmountsAndDoesNotExposeDraftSnapshotsToResidents()throws Exception{
        var body=mapper.readTree(mapper.writeValueAsString(data(0,"1","2026-01-01",null)));((com.fasterxml.jackson.databind.node.ObjectNode)body).put("amount",-1);
        mvc.perform(post("/v1/houses/"+HOUSE+"/tariffs/registry").header("X-Demo-Session","admin").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/houses/"+HOUSE+"/tariffs?date=wrong").header("X-Demo-Session","true")).andExpect(status().isBadRequest());
        var p=publish(document(draft()));mvc.perform(get("/v1/tariff-documents/"+p.files().get(0).id()).header("X-Demo-Session","true")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get("/v1/houses/"+HOUSE+"/tariffs").header("X-Demo-Session","true")).andExpect(status().isOk()).andExpect(jsonPath("$.history[0].history[0].snapshot").isEmpty());
    }
}
