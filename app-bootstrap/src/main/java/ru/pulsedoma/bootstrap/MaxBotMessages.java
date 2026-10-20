package ru.pulsedoma.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class MaxBotMessages {
    private final String username;
    public MaxBotMessages(@Value("${max.bot.username:}") String username){this.username=username;}
    public Map<String,Object> body(String text,List<String> buttons,String caseId) {
        List<List<Map<String,Object>>> rows=new ArrayList<>();
        for(String label:buttons)rows.add(List.of(Map.of("type","message","text",label)));
        if(!username.isBlank()) {
            Map<String,Object> app=new LinkedHashMap<>(Map.of("type","open_app","text",caseId==null?"Открыть приложение":"История и документы","web_app",username));
            if(caseId!=null)app.put("payload","case_"+caseId);
            rows.add(List.of(app));
        }
        return rows.isEmpty()?Map.of("text",limit(text,3900)):Map.of("text",limit(text,3900),"attachments",List.of(Map.of("type","inline_keyboard","payload",Map.of("buttons",rows))));
    }
    public List<String> menu(){return List.of("Сообщить о проблеме","Вопрос по квитанции","Мои обращения","Мой дом","Помощь");}
    public Map<String,Object> card(ResidentCaseService.CaseView c,boolean notification) {
        List<String> buttons=new ArrayList<>();
        if(c.status()==CaseStatus.RESULT_PROPOSED)buttons.add("Подтвердить №"+c.number());
        if(c.status()==CaseStatus.RESULT_PROPOSED||c.status()==CaseStatus.CLOSED)buttons.add("Проверить повторно №"+c.number());
        if(c.status()!=CaseStatus.CLOSED)buttons.add("Ответить №"+c.number());
        buttons.add("Мои обращения");buttons.add("Меню");
        String last=c.events().stream().filter(e->e.type()==CaseEventType.MESSAGE).reduce((a,b)->b).map(e->"\nПоследнее сообщение — "+e.actor()+": "+limit(e.text(),700)).orElse("");
        return body((notification?"Обновление по вашему обращению.\n":"")+"Обращение №"+c.number()+" · "+status(c.status())+"\nДом: "+c.address()+"\n"+limit(c.description(),500)
            +"\n\nЧто дальше: "+c.nextStep()+"\nКто ведёт: "+c.responsibleName()+"\n"+due(c.dueAt())+last
            +(c.status()==CaseStatus.RESULT_PROPOSED?"\n\nПроверьте результат. Молчание не означает согласия.":"")
            +(c.documents().isEmpty()?"":"\nДокументы: "+c.documents().size()+". Доступны в карточке приложения."),buttons,c.id());
    }
    public static String status(CaseStatus s){return switch(s){
        case RECEIVED->"Принято к рассмотрению";case IN_REVIEW->"Разбираемся";case WAITING_RESIDENT->"Нужен ваш ответ";
        case IN_PROGRESS->"В работе";case RESULT_PROPOSED->"Посмотрите результат";case CLOSED->"Результат подтверждён";case REOPENED->"Проверяем повторно";};}
    private static String due(String value){return value==null?"Срок уточняется":"Срок: "+DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Moscow")).format(Instant.parse(value))+" МСК";}
    public static String limit(String text,int length){return text.length()>length?text.substring(0,length-1)+"…":text;}
}
