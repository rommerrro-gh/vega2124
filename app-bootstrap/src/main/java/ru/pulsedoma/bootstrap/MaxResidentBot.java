package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.issues.*;
import java.time.Instant;
import java.util.*;

/** Private-dialog state machine. Domain changes, inbox deduplication and reply are one transaction. */
@Service
public class MaxResidentBot {
    enum State { IDLE, COMPOSE, REVIEW, REPLY, CONFIRM, REOPEN }
    record Session(State state,String house,CaseKind kind,String draft,String text,String caseId,Integer revision) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResidentCaseService cases;
    private final MiniAppService app;
    private final MaxBotMessages messages;
    private final MaxBotMedia media;
    private final TransactionTemplate tx;
    public MaxResidentBot(JdbcTemplate jdbc,ObjectMapper mapper,ResidentCaseService cases,MiniAppService app,
                          MaxBotMessages messages,MaxBotMedia media,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.mapper=mapper;this.cases=cases;this.app=app;this.messages=messages;this.media=media;this.tx=new TransactionTemplate(manager);
    }
    public void handle(JsonNode update) {
        String type=update.path("update_type").asText();
        boolean start=type.equals("bot_started");
        if(!start&&!type.equals("message_created"))return;
        JsonNode sender=start?update.path("user"):update.path("message").path("sender");
        if(sender.path("is_bot").asBoolean())return;
        String maxId=sender.path("user_id").asText("");
        if(!maxId.matches("[0-9]{1,20}"))return;
        JsonNode message=update.path("message");
        // Incoming dialog Recipient has the bot user_id; groups/channels have null user_id.
        if(!start&&(message.path("recipient").path("user_id").isNull()
            ||message.path("recipient").path("user_id").isMissingNode()
            ||message.path("recipient").path("chat_type").asText().equals("channel")))return;
        String key=start?"start:"+maxId+":"+update.path("timestamp").asText():"message:"+message.path("body").path("mid").asText();
        if(key.equals("message:"))throw new IllegalArgumentException("Missing MAX message id");
        if(jdbc.queryForObject("SELECT count(*) FROM max_bot_updates WHERE update_id=?",Integer.class,key)>0)return;
        try {
            // Fetch bounded media before taking SQLite's write transaction.
            Map<String,MaxBotMedia.File> loaded=new HashMap<>();
            JsonNode incoming=message.path("body").path("attachments");
            if(incoming.isArray()) {
                if(incoming.size()>5)throw invalid("За одно сообщение можно прислать не более 5 файлов.");
                for(JsonNode attachment:incoming) {
                    String url=attachment.path("payload").path("url").asText();
                    if(!loaded.containsKey(url))loaded.put(url,file(attachment));
                }
            }
            tx.executeWithoutResult(status->process(update,maxId,sender,key,start,loaded));
        }
        catch(BusinessException e) {
            tx.executeWithoutResult(status->{
                String actor=actor(maxId,sender);
                if(actor!=null&&mark(key))reply(key,actor,null,null,messages.body(e.getMessage(),List.of("Продолжить","Мои обращения","Меню"),null));
            });
        }
    }
    private boolean mark(String key){return jdbc.update("INSERT OR IGNORE INTO max_bot_updates(update_id,processed_at) VALUES (?,?)",key,now())==1;}
    private String actor(String maxId,JsonNode sender) {
        String name=sender.path("name").asText(sender.path("first_name").asText("Житель MAX"));
        jdbc.update("INSERT OR IGNORE INTO users(id,max_user_id,display_name,status) VALUES (?,?,?,'ACTIVE')",UUID.randomUUID().toString(),maxId,MaxBotMessages.limit(name,150));
        var users=jdbc.query("SELECT id FROM users WHERE max_user_id=? AND status='ACTIVE'",(rs,n)->rs.getString(1),maxId);
        return users.isEmpty()?null:users.get(0);
    }
    private void process(JsonNode update,String maxId,JsonNode sender,String key,boolean start,Map<String,MaxBotMedia.File> loaded) {
        if(!mark(key))return;
        // Do not replay old messages already consumed by the legacy worker.
        if(!start&&jdbc.queryForObject("SELECT count(*) FROM max_processed_messages WHERE message_id=?",Integer.class,update.path("message").path("body").path("mid").asText())>0)return;
        String user=actor(maxId,sender);if(user==null)return;
        expire(user);
        Session s=session(user);
        String sessionHouse=s.house();
        if(sessionHouse!=null && residentHouses(user).stream().noneMatch(h->h.id().equals(sessionHouse))) {
            jdbc.update("DELETE FROM max_bot_draft_files WHERE user_id=?",user);idle(user);
            reply(key,user,null,null,messages.body("Доступ к выбранному дому изменился. Черновик удалён. Выберите доступный дом.",List.of("Мой дом","Меню"),null));return;
        }
        String text=start?"/start":update.path("message").path("body").path("text").asText("").strip();
        JsonNode attachments=update.path("message").path("body").path("attachments");
        if(start||Set.of("/start","/help","Помощь","Меню","/menu").contains(text)) {
            if(s.state()==State.REPLY||s.state()==State.CONFIRM||s.state()==State.REOPEN)idle(user);
            reply(key,user,null,null,messages.body("Пульс дома. Опишите проблему или вопрос по квитанции — выбирать организацию не нужно.\n\nЗдесь можно подать обращение, узнать следующий шаг и срок, ответить сотруднику и проверить результат. Фото и документы необязательны. При непосредственной опасности звоните 112."
                +(hasDraft(s)?"\n\nУ вас есть черновик. Нажмите «Продолжить».":""),hasDraft(s)?with("Продолжить",messages.menu()):messages.menu(),null));return;
        }
        if(text.equals("Мои обращения")||text.equals("/cases")||text.matches("Ещё обращения [0-9]{1,9}")) {
            if(!hasDraft(s))idle(user);
            int page=text.startsWith("Ещё обращения ")?Integer.parseInt(text.substring(text.lastIndexOf(' ')+1)):0;
            String house=activeHouse(user);
            if(house==null){reply(key,user,null,null,messages.body("Выберите дом, чтобы посмотреть обращения.",List.of("Мой дом","Меню"),null));return;}
            var all=cases.mine(user).stream().filter(c->c.houseId().equals(house)).toList();int from=Math.min(all.size(),Math.min(page,10000)*6),to=Math.min(all.size(),from+6);
            StringBuilder content=new StringBuilder("Ваши обращения\nДом: "+address(house));List<String> buttons=new ArrayList<>();
            for(var c:all.subList(from,to)){content.append("\n\n№").append(c.number()).append(" · ").append(MaxBotMessages.status(c.status())).append("\n").append(c.address()).append("\n").append(MaxBotMessages.limit(c.description(),100));buttons.add("Открыть №"+c.number());}
            if(to<all.size())buttons.add("Ещё обращения "+(page+1));
            if(all.isEmpty())content.append("\nНовых обращений пока нет. Прежние заявки доступны в приложении.");
            else content.append("\n\nПрежние заявки также доступны в приложении.");
            buttons.add("Мой дом");buttons.add("Меню");reply(key,user,house,null,messages.body(content.toString(),buttons,null));return;
        }
        if(text.equals("Мой дом")||text.equals("/house")||text.equals("Сменить дом")||text.matches("Выбрать дом №[0-9]{1,9}")) {
            if(!hasDraft(s))idle(user);
            var houses=residentHouses(user);
            if(text.startsWith("Выбрать дом №")) {
                int index=Integer.parseInt(text.substring(text.indexOf('№')+1))-1;
                if(index<0||index>=houses.size())throw invalid("Список домов изменился. Откройте «Мой дом» заново.");
                if(hasDraft(s))throw invalid("Сначала отправьте или удалите черновик: его дом уже выбран.");
                app.setActiveHouse(houses.get(index).id(),user);idle(user);
            }
            String house=activeHouse(user);
            List<String> buttons=new ArrayList<>();StringBuilder content=new StringBuilder("Мой дом\n");
            for(int i=0;i<houses.size();i++){var h=houses.get(i);content.append("\n").append(i+1).append(". ").append(h.address()).append(h.id().equals(house)?" — выбран":"");buttons.add("Выбрать дом №"+(i+1));}
            if(house!=null) {
                jdbc.query("SELECT title,phone,details FROM house_contacts WHERE house_id=? ORDER BY title LIMIT 6",rs->{content.append("\n\n").append(rs.getString(1)).append(": ").append(Objects.toString(rs.getString(2),""));if(rs.getString(3)!=null)content.append("\n").append(rs.getString(3));},house);
                content.append("\n\nПаспорт дома и полный список контактов — в приложении.");
            } else content.append("\nПодключите дом в приложении по приглашению. Адрес сам по себе не даёт доступ к дому.");
            buttons.add("Меню");reply(key,user,house,null,messages.body(content.toString(),buttons,null));return;
        }
        if(Set.of("Сообщить о проблеме","Вопрос по квитанции","/new","/payment").contains(text)) {
            if(hasDraft(s)){reply(key,user,s.house(),null,messages.body("У вас уже есть черновик. Можно продолжить или удалить его, чтобы начать новый.",List.of("Продолжить","Удалить черновик","Меню"),null));return;}
            String house=activeHouse(user);if(house==null){reply(key,user,null,null,messages.body("Сначала выберите или подключите дом. Вам не нужно определять организацию, которая будет решать вопрос.",List.of("Мой дом","Меню"),null));return;}
            if(!cases.context(house,user).intakeAvailable())throw invalid("Приём в этом доме пока не подключён: сотрудник ещё не назначен. Контакты дома доступны в разделе «Мой дом».");
            s=new Session(State.COMPOSE,house,text.equals("/payment")||text.equals("Вопрос по квитанции")?CaseKind.PAYMENT:CaseKind.PROBLEM,UUID.randomUUID().toString(),"",null,null);save(user,s);
            reply(key,user,house,null,messages.body("Дом: "+address(house)+"\n\n"+(s.kind()==CaseKind.PAYMENT?"Напишите, что непонятно в квитанции. Знать тариф, формулу или причину ошибки не требуется.":"Напишите своими словами, что произошло. Если место или причина неизвестны, так и напишите.")+"\nФото или PDF можно прислать следующим сообщением. До подтверждения это черновик; он хранится 24 часа после последнего изменения.",List.of("Удалить черновик","Меню"),null));return;
        }
        if(text.equals("Удалить черновик")||text.equals("/cancel")) {
            jdbc.update("DELETE FROM max_bot_draft_files WHERE user_id=?",user);idle(user);
            reply(key,user,null,null,messages.body("Черновик удалён. Обращение не отправлено.",messages.menu(),null));return;
        }
        if(text.matches("(Открыть|Ответить|Подтвердить|Проверить повторно) №[0-9]{1,9}")) {
            long number=Long.parseLong(text.substring(text.indexOf('№')+1));
            var c=mine(number,user);
            if(text.startsWith("Открыть")){if(!hasDraft(s))idle(user);reply(key,user,c.houseId(),c.id(),messages.card(c,false));return;}
            if(hasDraft(s))throw invalid("Сначала отправьте или удалите черновик нового обращения.");
            State state=text.startsWith("Ответить")?State.REPLY:text.startsWith("Подтвердить")?State.CONFIRM:State.REOPEN;
            if(state==State.REPLY&&c.status()==CaseStatus.CLOSED)throw invalid("Обращение закрыто. Нажмите «Проверить повторно».");
            if(state==State.CONFIRM&&c.status()!=CaseStatus.RESULT_PROPOSED)throw invalid("Результат пока не предложен или обращение изменилось. Откройте его заново.");
            if(state==State.REOPEN&&!List.of(CaseStatus.RESULT_PROPOSED,CaseStatus.CLOSED).contains(c.status()))throw invalid("Повторное рассмотрение уже идёт или результат ещё не предложен.");
            save(user,new Session(state,c.houseId(),CaseKind.GENERAL,UUID.randomUUID().toString(),"",c.id(),c.revision()));
            String prompt=state==State.REPLY?"Напишите ответ сотруднику или пришлите фото/PDF. Если не знаете ответ, так и напишите.":state==State.CONFIRM?"Вы проверили результат и подтверждаете, что вопрос решён?": "Напишите, что осталось нерешённым. Можно продолжить без пояснения.";
            reply(key,user,c.houseId(),c.id(),messages.body("Обращение №"+c.number()+"\nДом: "+c.address()+"\n\n"+prompt,state==State.CONFIRM?List.of("Результат №"+c.number()+" подтверждаю","Меню"):state==State.REOPEN?List.of("Без пояснения","Меню"):List.of("Меню"),c.id()));return;
        }
        if(s.state()==State.CONFIRM&&text.matches("Результат №[0-9]{1,9} подтверждаю")) {
            var c=own(s.caseId(),user);if(!text.equals("Результат №"+c.number()+" подтверждаю"))throw invalid("Кнопка относится к другому обращению. Откройте нужное обращение заново.");var result=cases.respond(c.id(),true,"",s.revision(),user);idle(user);reply(key,user,c.houseId(),c.id(),messages.card(result,false));return;
        }
        if(text.matches("Результат №[0-9]{1,9} подтверждаю") || s.state()==State.CONFIRM) {
            throw invalid("Подтверждение устарело. Откройте нужное обращение и проверьте результат заново.");
        }
        if(s.state()==State.REOPEN&&!text.isBlank()) {
            var c=own(s.caseId(),user);var result=cases.respond(c.id(),false,text.equals("Без пояснения")?"":text,s.revision(),user);idle(user);reply(key,user,c.houseId(),c.id(),messages.card(result,false));return;
        }
        if(s.state()==State.REPLY) {
            var c=own(s.caseId(),user);
            if(!text.isBlank())c=cases.message(c.id(),text,key,user);
            if(attachments.isArray()&&!attachments.isEmpty())c=uploadToCase(c,attachments,user,loaded);
            if(text.isBlank()&&(!attachments.isArray()||attachments.isEmpty()))throw invalid("Пришлите текст, фото или PDF.");
            idle(user);reply(key,user,c.houseId(),c.id(),messages.card(c,false));return;
        }
        if(text.equals("Продолжить")||text.equals("Проверить перед отправкой")) {
            if(!hasDraft(s)){reply(key,user,null,null,messages.body("Активного черновика нет. Выберите действие.",messages.menu(),null));return;}
            if(s.text().isBlank()){reply(key,user,s.house(),null,messages.body("Напишите, что произошло или что непонятно. Одного предложения достаточно.",List.of("Удалить черновик","Меню"),null));return;}
            s=new Session(State.REVIEW,s.house(),s.kind(),s.draft(),s.text(),null,null);save(user,s);preview(key,user,s);return;
        }
        if(text.equals("Отправить обращение")) {
            if(s.state()!=State.REVIEW)throw invalid("Сначала проверьте черновик перед отправкой.");
            var c=cases.create(s.house(),s.kind(),s.text(),null,"max:"+s.draft(),user);
            for(var f:jdbc.queryForList("SELECT * FROM max_bot_draft_files WHERE user_id=? AND draft_id=? ORDER BY created_at,id",user,s.draft()))
                c=cases.upload(c.id(),null,f.get("title").toString(),(byte[])f.get("content"),f.get("mime").toString(),0,user);
            jdbc.update("DELETE FROM max_bot_draft_files WHERE user_id=?",user);idle(user);
            reply(key,user,c.houseId(),c.id(),messages.card(c,false));return;
        }
        if(hasDraft(s)) {
            String combined=text.isBlank()?s.text():s.text().isBlank()?text:s.text()+"\n"+text;
            if(combined.length()>4000)throw invalid("Описание слишком длинное. Максимум — 4000 символов; подробности можно добавить после регистрации.");
            if(attachments.isArray())for(JsonNode file:attachments)saveFile(user,s,file,loaded);
            s=new Session(State.COMPOSE,s.house(),s.kind(),s.draft(),combined,null,null);save(user,s);
            if(combined.isBlank())reply(key,user,s.house(),null,messages.body("Файл добавлен. Теперь напишите хотя бы одно предложение о вашем вопросе.",List.of("Удалить черновик","Меню"),null));
            else preview(key,user,s);
            return;
        }
        // A free message starts a general draft; no forced category selection.
        String house=activeHouse(user);
        if(house==null){reply(key,user,null,null,messages.body("Чтобы принять вопрос, сначала выберите или подключите дом в разделе «Мой дом».",List.of("Мой дом","Меню"),null));return;}
        if(text.isBlank()){reply(key,user,null,null,messages.body("Сначала напишите, о чём хотите сообщить. Затем можно приложить файл.",messages.menu(),null));return;}
        if(text.startsWith("/")||text.length()>4000){reply(key,user,null,null,messages.body("Выберите действие в меню. Описание обращения — до 4000 символов.",messages.menu(),null));return;}
        if(!cases.context(house,user).intakeAvailable())throw invalid("Приём в этом доме пока не подключён. Контакты доступны в разделе «Мой дом».");
        s=new Session(State.COMPOSE,house,CaseKind.GENERAL,UUID.randomUUID().toString(),text,null,null);save(user,s);
        if(attachments.isArray())for(JsonNode file:attachments)saveFile(user,s,file,loaded);
        preview(key,user,s);
    }
    private void preview(String key,String user,Session s) {
        int files=jdbc.queryForObject("SELECT count(*) FROM max_bot_draft_files WHERE user_id=? AND draft_id=?",Integer.class,user,s.draft());
        // Adding text/files always returns to review; submit button is valid only for this stored draft.
        save(user,new Session(State.REVIEW,s.house(),s.kind(),s.draft(),s.text(),null,null));
        reply(key,user,s.house(),null,messages.body("Проверьте перед отправкой\nДом: "+address(s.house())+"\n\n"+MaxBotMessages.limit(s.text(),2800)+"\n\nФайлов: "+files+"\nМожно дописать подробности или прислать фото/PDF. Получателя определит сотрудник. Пока обращение не отправлено.",List.of("Отправить обращение","Удалить черновик","Меню"),null));
    }
    private void saveFile(String user,Session s,JsonNode attachment,Map<String,MaxBotMedia.File> loaded) {
        var f=loaded.get(attachment.path("payload").path("url").asText());
        long size=jdbc.queryForObject("SELECT coalesce(sum(length(content)),0) FROM max_bot_draft_files WHERE user_id=?",Long.class,user);
        int count=jdbc.queryForObject("SELECT count(*) FROM max_bot_draft_files WHERE user_id=?",Integer.class,user);
        if(count>=5||size+f.bytes().length>20*1024*1024)throw invalid("В черновике можно сохранить до 5 файлов общим размером до 20 МБ. Остальные добавьте после регистрации в приложении.");
        jdbc.update("INSERT INTO max_bot_draft_files(id,user_id,draft_id,title,mime,content,created_at) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID().toString(),user,s.draft(),title(attachment,f),f.mime(),f.bytes(),now());
    }
    private ResidentCaseService.CaseView uploadToCase(ResidentCaseService.CaseView c,JsonNode attachments,String user,Map<String,MaxBotMedia.File> loaded) {
        if(attachments.size()>5)throw invalid("За один ответ можно добавить не более 5 файлов.");
        for(JsonNode a:attachments){var f=loaded.get(a.path("payload").path("url").asText());c=cases.upload(c.id(),null,title(a,f),f.bytes(),f.mime(),0,user);}return c;
    }
    private MaxBotMedia.File file(JsonNode a) {
        if(!Set.of("image","file").contains(a.path("type").asText()))throw invalid("Поддерживаются фото JPEG/PNG и документы PDF. Видео и голосовые сообщения пока не принимаются.");
        if(a.path("size").asLong(0)>10*1024*1024)throw invalid("Размер одного файла — до 10 МБ.");
        return media.download(a.path("payload").path("url").asText());
    }
    private String title(JsonNode a,MaxBotMedia.File f){return MaxBotMessages.limit(a.path("filename").asText(f.mime().startsWith("image/")?"Фото из MAX":"Документ из MAX"),160);}
    private ResidentCaseService.CaseView mine(long number,String user) {
        var ids=jdbc.query("SELECT id FROM resident_cases WHERE number=? AND author_id=?",(rs,n)->rs.getString(1),number,user);
        if(ids.isEmpty())throw invalid("Обращение недоступно. Откройте «Мои обращения».");return own(ids.get(0),user);
    }
    private ResidentCaseService.CaseView own(String id,String user){var c=cases.get(id,user);if(!c.isAuthor())throw invalid("Обращение недоступно.");return c;}
    private List<MiniAppService.HouseView> residentHouses(String user){return app.houses(user).stream().filter(h->h.memberships().stream().anyMatch(m->m.role()==ru.pulsedoma.identity.MembershipRole.RESIDENT)).limit(25).toList();}
    private String activeHouse(String user){var houses=residentHouses(user);String active=app.activeHouse(user);if(houses.stream().anyMatch(h->h.id().equals(active)))return active;if(houses.size()==1){app.setActiveHouse(houses.get(0).id(),user);return houses.get(0).id();}return null;}
    private String address(String id){return jdbc.queryForObject("SELECT address FROM houses WHERE id=?",String.class,id);}
    private boolean hasDraft(Session s){return s.state()==State.COMPOSE||s.state()==State.REVIEW;}
    private Session session(String user){var list=jdbc.query("SELECT * FROM max_bot_sessions WHERE user_id=?",(rs,n)->new Session(State.valueOf(rs.getString("state")),rs.getString("house_id"),CaseKind.valueOf(rs.getString("kind")),rs.getString("draft_id"),rs.getString("description"),rs.getString("case_id"),(Integer)rs.getObject("revision")),user);return list.isEmpty()?empty():list.get(0);}
    private Session empty(){return new Session(State.IDLE,null,CaseKind.GENERAL,UUID.randomUUID().toString(),"",null,null);}
    private void idle(String user){save(user,empty());}
    private void save(String user,Session s){jdbc.update("""
        INSERT INTO max_bot_sessions(user_id,state,house_id,kind,draft_id,description,case_id,revision,updated_at) VALUES (?,?,?,?,?,?,?,?,?)
        ON CONFLICT(user_id) DO UPDATE SET state=excluded.state,house_id=excluded.house_id,kind=excluded.kind,draft_id=excluded.draft_id,
        description=excluded.description,case_id=excluded.case_id,revision=excluded.revision,updated_at=excluded.updated_at
        """,user,s.state().name(),s.house(),s.kind().name(),s.draft(),s.text(),s.caseId(),s.revision(),now());}
    private void expire(String user){String cutoff=Instant.now().minusSeconds(86400).toString();jdbc.update("DELETE FROM max_bot_draft_files WHERE user_id=? AND EXISTS (SELECT 1 FROM max_bot_sessions s WHERE s.user_id=? AND s.updated_at<?)",user,user,cutoff);jdbc.update("DELETE FROM max_bot_sessions WHERE user_id=? AND updated_at<?",user,cutoff);}
    private void reply(String key,String user,String house,String id,Map<String,Object> body){
        try {jdbc.update("INSERT INTO max_bot_outbox(id,user_id,house_id,case_id,kind,body_json,next_attempt_at,created_at) VALUES (?,?,?,?,'REPLY',?,?,?)",key,user,house,id,mapper.writeValueAsString(body),now(),now());}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private static List<String> with(String first,List<String> rest){var list=new ArrayList<String>();list.add(first);list.addAll(rest);return list;}
    private static String now(){return Instant.now().toString();}
    private static BusinessException invalid(String text){return new BusinessException("BOT_INPUT",text);}
}
