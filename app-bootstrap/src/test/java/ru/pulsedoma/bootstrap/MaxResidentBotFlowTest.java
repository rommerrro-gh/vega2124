package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import reactor.core.publisher.Mono;
import ru.pulsedoma.issues.*;
import ru.pulsedoma.max.MaxApiClient;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("demo")
class MaxResidentBotFlowTest {
    static final Path DB=temp(),FILES=dir();
    static Path temp(){try{return Files.createTempFile("bot-flow-", ".db");}catch(Exception e){throw new IllegalStateException(e);}}
    static Path dir(){try{return Files.createTempDirectory("bot-files-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);r.add("storage.demo.path",FILES::toString);r.add("max.bot.username",()->"test_bot");}
    @Autowired MaxResidentBot bot;
    @Autowired MaxBotMessages messages;
    @Autowired ResidentCaseService cases;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean MaxBotMedia media;
    @MockBean MaxApiClient max;
    static final String USER="demo-resident-1",HOUSE="demo-house-1";
    @BeforeEach void reset(){
        jdbc.update("DELETE FROM max_bot_outbox");jdbc.update("DELETE FROM max_bot_draft_files");jdbc.update("DELETE FROM max_bot_sessions");jdbc.update("DELETE FROM max_bot_updates");
        jdbc.update("UPDATE users SET max_user_id='123456',active_house_id=?,status='ACTIVE' WHERE id=?",HOUSE,USER);
        jdbc.update("UPDATE house_memberships SET access_status='ACTIVE' WHERE user_id=?",USER);
        jdbc.update("DELETE FROM case_billing_permissions");
        org.mockito.Mockito.reset(max,media);when(max.sendMessage(anyString(),anyMap())).thenReturn(Mono.just("{}"));
    }
    JsonNode update(String mid,String text,JsonNode files)throws Exception{
        var body=mapper.createObjectNode().put("mid",mid).put("text",text);if(files!=null)body.set("attachments",files);
        var message=mapper.createObjectNode();message.set("body",body);message.set("sender",mapper.createObjectNode().put("user_id",123456).put("name","Житель"));
        message.set("recipient",mapper.createObjectNode().put("chat_id",777).put("chat_type","dialog").put("user_id",999));
        var result=mapper.createObjectNode().put("update_type","message_created");result.set("message",message);return result;
    }
    void say(String text)throws Exception{bot.handle(update(UUID.randomUUID().toString(),text,null));}
    String last(){return jdbc.queryForObject("SELECT body_json FROM max_bot_outbox WHERE kind='REPLY' ORDER BY rowid DESC LIMIT 1",String.class);}
    ResidentCaseService.CaseView create(){return cases.create(HOUSE,CaseKind.GENERAL,"Вопрос "+UUID.randomUUID(),null,UUID.randomUUID().toString(),USER);}
    void review(ResidentCaseService.CaseView c,CaseStatus status){cases.update(c.id(),c.kind(),status,"Уточните, когда заметили проблему",Instant.now().plusSeconds(3600),null,c.revision(),"Обновлён следующий шаг","demo-dispatcher-1");}
    MaxBotDelivery delivery(){return new MaxBotDelivery(jdbc,mapper,cases,messages,max,"test-token");}
    @Test void tariffMenuPreservesDraftAndShowsNoInventedAmounts()throws Exception{
        say("Сообщить о проблеме");say("Протечка в подъезде");
        say("Тарифы");assertTrue(last().contains("пока не опубликованы"));assertTrue(last().contains("Это не означает"));
        say("Продолжить");assertTrue(last().contains("Протечка в подъезде"));
    }
    @Test void tariffMenuChecksCurrentHouseAccess()throws Exception{
        jdbc.update("UPDATE house_memberships SET access_status='REVOKED' WHERE user_id=?",USER);
        say("/tariffs");assertTrue(last().contains("Выберите подключённый дом"));
    }
    @Test void menuWorksForNewUserWithoutGrantingHouseAccess()throws Exception{
        var u=update("new-user","/start",null);((com.fasterxml.jackson.databind.node.ObjectNode)u.path("message").path("sender")).put("user_id",88888);
        bot.handle(u);assertTrue(last().contains("Вопрос по квитанции"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM house_memberships m JOIN users u ON u.id=m.user_id WHERE u.max_user_id='88888'",Integer.class));
    }
    @Test void freeTextRequiresConfirmationAndDuplicateMessagesNeverCreateAnotherCase()throws Exception{
        int before=cases.mine(USER).size();var text=update("draft-mid","Мокнет стена, не знаю почему",null);bot.handle(text);bot.handle(text);
        assertEquals(before,cases.mine(USER).size());assertTrue(last().contains("Проверьте перед отправкой"));
        var submit=update("submit-mid","Отправить обращение",null);bot.handle(submit);bot.handle(submit);say("Отправить обращение");
        assertEquals(before+1,cases.mine(USER).size());var c=cases.mine(USER).get(0);assertEquals(CaseKind.GENERAL,c.kind());assertNull(c.location());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM max_bot_outbox WHERE id='message:submit-mid'",Integer.class));
    }
    @Test void paymentNeedsNoRecipientAndIsHiddenFromOrdinaryDispatcher()throws Exception{
        say("Вопрос по квитанции");say("Почему сумма выросла?");say("Отправить обращение");
        var c=cases.mine(USER).get(0);assertEquals(CaseKind.PAYMENT,c.kind());assertEquals(HOUSE,c.houseId());
        assertFalse(cases.queue(HOUSE,"demo-dispatcher-1").stream().filter(x->x.id().equals(c.id())).findFirst().orElseThrow().fullAccess());
    }
    @Test void fileIsStoredOnceAndSharedWithMiniApp()throws Exception{
        byte[] bytes="%PDF-1.4 test\n%%EOF".getBytes();when(media.download("https://fu.oneme.ru/test")).thenReturn(new MaxBotMedia.File(bytes,"application/pdf"));
        say("Сообщить о проблеме");say("Течёт потолок");
        var files=mapper.readTree("[{\"type\":\"file\",\"filename\":\"Фото.pdf\",\"payload\":{\"url\":\"https://fu.oneme.ru/test\"}}]");
        var u=update("file-mid","",files);bot.handle(u);bot.handle(u);assertTrue(last().contains("Файлов: 1"));say("Отправить обращение");
        var c=cases.mine(USER).get(0);assertEquals(1,c.documents().size());assertArrayEquals(bytes,cases.download(c.documents().get(0).versions().get(0).id(),USER).bytes());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM max_bot_draft_files WHERE user_id=?",Integer.class,USER));
    }
    @Test void unsupportedMediaPreservesExistingDraft()throws Exception{
        say("Есть вопрос");bot.handle(update("unsupported","",mapper.readTree("[{\"type\":\"audio\"}]")));
        assertTrue(last().contains("голосовые"));assertEquals("Есть вопрос",jdbc.queryForObject("SELECT description FROM max_bot_sessions WHERE user_id=?",String.class,USER));
        say("Продолжить");assertTrue(last().contains("Есть вопрос"));
    }
    @Test void residentCanReplyConfirmAndReopenSameCase()throws Exception{
        var c=create();review(c,CaseStatus.WAITING_RESIDENT);say("Ответить №"+c.number());
        var reply=update("resident-reply","Сейчас не знаю, буду дома вечером",null);bot.handle(reply);bot.handle(reply);
        var result=cases.get(c.id(),USER);assertEquals(CaseStatus.IN_REVIEW,result.status());assertEquals(1,result.events().stream().filter(e->e.text().contains("буду дома")).count());
        review(result,CaseStatus.RESULT_PROPOSED);say("Подтвердить №"+c.number());say("Результат №"+c.number()+" подтверждаю");assertEquals(CaseStatus.CLOSED,cases.get(c.id(),USER).status());
        say("Проверить повторно №"+c.number());say("Проблема осталась");assertEquals(CaseStatus.REOPENED,cases.get(c.id(),USER).status());
    }
    @Test void staleConfirmationDoesNotCloseChangedCase()throws Exception{
        var c=create();review(c,CaseStatus.RESULT_PROPOSED);say("Подтвердить №"+c.number());cases.message(c.id(),"Новые сведения",UUID.randomUUID().toString(),"demo-dispatcher-1");
        say("Результат №"+c.number()+" подтверждаю");assertEquals(CaseStatus.RESULT_PROPOSED,cases.get(c.id(),USER).status());assertTrue(last().contains("изменились"));
    }
    @Test void confirmationFromAnotherCaseCannotConfirmCurrentOne()throws Exception{
        var first=create();review(first,CaseStatus.RESULT_PROPOSED);var second=create();review(second,CaseStatus.RESULT_PROPOSED);
        say("Подтвердить №"+second.number());say("Результат №"+first.number()+" подтверждаю");
        assertEquals(CaseStatus.RESULT_PROPOSED,cases.get(first.id(),USER).status());assertEquals(CaseStatus.RESULT_PROPOSED,cases.get(second.id(),USER).status());
        assertTrue(last().contains("другому обращению"));
    }
    @Test void groupsCannotCreateDraftsOrLeakPaymentDetails()throws Exception{
        var u=update("group","Вопрос по квитанции",null);((com.fasterxml.jackson.databind.node.ObjectNode)u.path("message").path("recipient")).putNull("user_id").put("chat_type","chat");
        bot.handle(u);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM max_bot_outbox",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM max_bot_sessions",Integer.class));
    }
    @Test void revokedResidentAccessInvalidatesDraft()throws Exception{
        say("Вопрос о доме");jdbc.update("UPDATE house_memberships SET access_status='REVOKED' WHERE user_id=? AND role='RESIDENT'",USER);
        say("Отправить обращение");assertTrue(last().contains("Доступ к выбранному дому изменился"));
    }
    @Test void cannotOpenNeighborsPaymentCase()throws Exception{
        jdbc.update("INSERT OR IGNORE INTO users(id,display_name,status) VALUES ('bot-neighbor','Сосед','ACTIVE')");jdbc.update("INSERT OR IGNORE INTO house_memberships(house_id,user_id,role,verification_status,access_status) VALUES (?,'bot-neighbor','RESIDENT','VERIFIED','ACTIVE')",HOUSE);
        var c=cases.create(HOUSE,CaseKind.PAYMENT,"Чужая тайная квитанция",null,UUID.randomUUID().toString(),"bot-neighbor");say("Открыть №"+c.number());assertFalse(last().contains("тайная"));assertTrue(last().contains("недоступно"));
    }
    @Test void expirationAndCancelDoNotSubmit()throws Exception{
        say("Черновик истёк");jdbc.update("UPDATE max_bot_sessions SET updated_at='2020-01-01T00:00:00Z' WHERE user_id=?",USER);say("Продолжить");assertTrue(last().contains("Активного черновика нет"));say("Сообщить о проблеме");say("/cancel");assertTrue(last().contains("не отправлено"));
    }
    @Test void notificationsCoalesceAndRecheckAccess()throws Exception{
        var c=create();review(c,CaseStatus.WAITING_RESIDENT);cases.message(c.id(),"Когда заметили?",UUID.randomUUID().toString(),"demo-dispatcher-1");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM max_bot_outbox WHERE case_id=? AND kind='CASE_EVENT'",Integer.class,c.id()));delivery().sendPending();verify(max).sendMessage(eq("123456"),argThat(body->body.get("text").toString().contains("Нужен ваш ответ")));
        cases.message(c.id(),"Ещё вопрос",UUID.randomUUID().toString(),"demo-dispatcher-1");jdbc.update("UPDATE house_memberships SET access_status='REVOKED' WHERE user_id=? AND role='RESIDENT'",USER);delivery().sendPending();verifyNoMoreInteractions(max);
    }
    @Test void queuedCaseListIsCancelledIfAccessIsRevokedBeforeSending()throws Exception{
        create();say("Мои обращения");jdbc.update("UPDATE house_memberships SET access_status='REVOKED' WHERE user_id=? AND role='RESIDENT'",USER);
        delivery().sendPending();verifyNoInteractions(max);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM max_bot_outbox WHERE cancelled_at IS NOT NULL",Integer.class));
    }
    @Test void deliveryRetriesWithoutRepeatingIntake()throws Exception{
        say("Вопрос с проверкой доставки");say("Отправить обращение");int count=cases.mine(USER).size();when(max.sendMessage(anyString(),anyMap())).thenReturn(Mono.error(new RuntimeException("network")));
        delivery().sendPending();assertTrue(jdbc.queryForObject("SELECT sum(attempts) FROM max_bot_outbox",Integer.class)>0);assertEquals(count,cases.mine(USER).size());
        when(max.sendMessage(anyString(),anyMap())).thenReturn(Mono.just("{}"));jdbc.update("UPDATE max_bot_outbox SET next_attempt_at='2020-01-01T00:00:00Z'");delivery().sendPending();assertEquals(count,cases.mine(USER).size());
    }
    @Test void severalHousesRequireSelectionAndDraftHouseCannotChangeSilently()throws Exception{
        jdbc.update("UPDATE users SET active_house_id=NULL WHERE id=?",USER);say("Сообщить о проблеме");assertTrue(last().contains("Сначала выберите"));say("Мой дом");assertTrue(last().contains("Выбрать дом №1"));say("Выбрать дом №1");say("Вопрос по квитанции");say("Выбрать дом №2");assertTrue(last().contains("Сначала отправьте или удалите черновик"));
    }
    @Test void mediaUrlsAreRestricted(){
        for(String url:List.of("http://fu.oneme.ru/test","https://localhost/x","https://127.0.0.1/x","https://fu.oneme.ru.evil.test/x","https://fu.oneme.ru:8443/x","https://user:pass@fu.oneme.ru/x"))assertFalse(MaxBotMedia.allowed(java.net.URI.create(url)),url);assertTrue(MaxBotMedia.allowed(java.net.URI.create("https://fu.oneme.ru/test")));
    }
}
