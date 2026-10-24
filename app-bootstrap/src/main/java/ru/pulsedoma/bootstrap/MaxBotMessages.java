package ru.pulsedoma.bootstrap;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.*;

@Component
public class MaxBotMessages {
    private final String username;

    public MaxBotMessages(@Value("${max.bot.username:}") String username) {
        this.username = username;
    }

    public Map<String, Object> body(String text, List<String> buttons, String caseId) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (String label : buttons) rows.add(List.of(Map.of("type", "message", "text", label)));
        if (!username.isBlank()) {
            Map<String, Object> app =
                    new LinkedHashMap<>(
                            Map.of(
                                    "type",
                                    "open_app",
                                    "text",
                                    caseId == null ? "Открыть приложение" : "История и документы",
                                    "web_app",
                                    username));
            if (caseId != null) app.put("payload", "case_" + caseId);
            rows.add(List.of(app));
        }
        return rows.isEmpty()
                ? Map.of("text", limit(text, 3900))
                : Map.of(
                        "text",
                        limit(text, 3900),
                        "attachments",
                        List.of(
                                Map.of(
                                        "type",
                                        "inline_keyboard",
                                        "payload",
                                        Map.of("buttons", rows))));
    }

    public String introduction(boolean connected) {
        return """
            🏡 Пульс дома
            Дом становится лучше с вами.

            В подъезде темно, после дождя мокнет стена или сумма в квитанции вызывает вопросы? Здесь можно сообщить об этом и видеть, что происходит дальше.

            Вы описываете ситуацию своими словами — сотрудники разбираются, кто поможет. Не нужно знать техническую причину или выбирать между УК и застройщиком.

            В Пульсе дома можно:
            • сообщить о проблеме или задать вопрос по квитанции;
            • следить за обращением: кто ведёт, что будет дальше и в какой срок;
            • ответить сотруднику и проверить результат;
            • посмотреть проверенные тарифы и их основания;
            • согласовать время визита и следить за общей проблемой дома в приложении;
            • посмотреть плановые работы, отключения, контакты и опросы дома.

            """
                + (connected
                        ? "Начните с того, что вас беспокоит: выберите действие ниже или просто напишите сообщение."
                        : "Впервые здесь? Откройте приложение и подключите дом по приглашению. Если дом уже подключён, выберите его в разделе «Мой дом».");
    }

    public String help() {
        return """
            Как пользоваться Пульсом дома

            Сообщить о проблеме
            Нажмите «Сообщить о проблеме» и напишите, что случилось. Можно не знать причину или точное место. Выберите категорию; если не уверены — «Другое». Получателя определят сотрудники.

            Задать вопрос по квитанции
            Нажмите «Вопрос по квитанции» и объясните, что непонятно. Знать тариф или формулу расчёта не нужно.

            Посмотреть ставки
            Нажмите «Тарифы»: увидите опубликованные ставки выбранного дома. Документы и история доступны в разделе «Платежи и тарифы» приложения.

            Добавить фото или документ
            Во время подготовки обращения пришлите JPEG, PNG или PDF до 10 МБ на файл. Вложения необязательны. Чтобы добавить их к уже поданному обращению, откройте его и нажмите «Ответить» или используйте приложение.

            Отправить или продолжить позже
            Перед отправкой проверьте адрес, описание и число файлов, затем нажмите «Отправить обращение». До этого обращение остаётся черновиком. Он хранится 24 часа после последнего изменения; кнопка «Продолжить» вернёт вас к нему. Команда /cancel удаляет черновик.

            Узнать статус и ответить
            В «Моих обращениях» выберите нужное. Там видны следующий шаг, ответственный и срок. Нажмите «Ответить», чтобы передать сведения сотруднику. Если ответа не знаете, так и напишите.

            Ответить на финансовое уточнение
            В приложении откройте вопрос по квитанции и нажмите «Проверка начисления». Ответьте на вопрос сотрудника своими словами. Кнопки «Не знаю» и «Документа нет» тоже передадут ответ.

            Проверить результат
            Когда сотрудник предложит решение, проверьте его. Если всё решено — подтвердите. Если проблема осталась — нажмите «Проверить повторно». Молчание не считается подтверждением.

            Визиты, общие проблемы и отключения
            В карточке обращения откройте «Время визита»: можно подтвердить предложенное время или попросить другое. Общие проблемы доступны в разделе «Обращения → Общие проблемы»; нажмите «У меня такая же проблема» и подтвердите присоединение. В «Событиях» видны работы, отключения и опросы. Уведомления настраиваются в «Профиль → Настройки». В «Платежах и тарифах» можно сравнить ставки на две даты и задать вопрос об изменении.

            Нет нужного дома?
            Подключите его в «Профиль → Мои дома» по приглашению администратора. На главной можно переключаться между подключёнными домами.

            При непосредственной опасности звоните 112.
            """
                .strip();
    }

    public List<String> menu() {
        return List.of(
                "Сообщить о проблеме",
                "Вопрос по квитанции",
                "Мои обращения",
                "Тарифы",
                "Мой дом",
                "Помощь");
    }

    public Map<String, Object> card(ResidentCaseService.CaseView c, boolean notification) {
        List<String> buttons = new ArrayList<>();
        if (c.status() == CaseStatus.RESULT_PROPOSED) buttons.add("Подтвердить №" + c.number());
        if (c.status() == CaseStatus.RESULT_PROPOSED || c.status() == CaseStatus.CLOSED)
            buttons.add("Проверить повторно №" + c.number());
        if (c.status() != CaseStatus.CLOSED) buttons.add("Ответить №" + c.number());
        if (c.kind() == CaseKind.PAYMENT && c.status() != CaseStatus.CLOSED)
            buttons.add("Уточнения №" + c.number());
        buttons.add("Мои обращения");
        buttons.add("Меню");
        String last =
                c.events().stream()
                        .filter(e -> e.type() == CaseEventType.MESSAGE)
                        .reduce((a, b) -> b)
                        .map(
                                e ->
                                        "\nПоследнее сообщение — "
                                                + e.actor()
                                                + ": "
                                                + limit(e.text(), 700))
                        .orElse("");
        return body(
                (notification ? "Обновление по вашему обращению.\n" : "")
                        + "Обращение №"
                        + c.number()
                        + " · "
                        + status(c.status())
                        + "\nДом: "
                        + c.address()
                        + "\n"
                        + limit(c.description(), 500)
                        + "\n\nЧто дальше: "
                        + c.nextStep()
                        + "\nКто ведёт: "
                        + c.responsibleName()
                        + "\n"
                        + due(c.dueAt())
                        + last
                        + (c.status() == CaseStatus.RESULT_PROPOSED
                                ? "\n\nПроверьте результат. Молчание не означает согласия."
                                : "")
                        + (c.documents().isEmpty()
                                ? ""
                                : "\nДокументы: "
                                        + c.documents().size()
                                        + ". Доступны в карточке приложения."),
                buttons,
                c.id());
    }

    public static String status(CaseStatus s) {
        return switch (s) {
            case RECEIVED -> "Принято к рассмотрению";
            case IN_REVIEW -> "Разбираемся";
            case WAITING_RESIDENT -> "Нужен ваш ответ";
            case IN_PROGRESS -> "В работе";
            case RESULT_PROPOSED -> "Посмотрите результат";
            case CLOSED -> "Результат подтверждён";
            case REOPENED -> "Проверяем повторно";
        };
    }

    private static String due(String value) {
        return value == null
                ? "Срок уточняется"
                : "Срок: "
                        + DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                                .withZone(ZoneId.of("Europe/Moscow"))
                                .format(Instant.parse(value))
                        + " МСК";
    }

    public static String limit(String text, int length) {
        return text.length() > length ? text.substring(0, length - 1) + "…" : text;
    }
}
