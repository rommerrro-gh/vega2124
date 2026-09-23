# Пульс дома: каркас приложения

Каркас по техническому заданию версии 1.1 и продуктовой концепции версии 1.0. Это этап T0: структура, схема SQLite, webhook MAX и контрактные точки расширения. Бизнес-сценарий целиком пока не реализован; маршруты из `infra/openapi.yaml`, для которых нет сервиса, нельзя считать готовыми.

## Модули

`common`, `domain-identity`, `domain-issues`, `domain-duplicates`, `domain-house`, `integration-max`, `integration-external`, `api`, `app-bootstrap`, `infra` — ровно 10 дочерних Maven-модулей. `infra` — модуль ресурсов с миграцией; исполняемый JAR выпускает `app-bootstrap`.

## Локальный запуск

Требуются Java 21, Maven 3.9.x, Docker Compose. Создайте `.env` из `.env.example` и замените значения для локальной среды. Затем:

```sh
mvn clean install
docker compose up --build
curl http://localhost:8080/actuator/health
curl -i -X POST http://localhost:8080/webhooks/max \
  -H 'Content-Type: application/json' \
  -H 'X-Max-Bot-Api-Secret: replace-with-random-local-secret' \
  -d '{"update_type":"bot_started","timestamp":0}'
```

Контейнер поднимает backend, Redis, MinIO и явно маркированный contract adapter. SQLite хранится в volume `sqlite-data`. Для реального webhook нужен внешний HTTPS endpoint на порту 443 с доверенным сертификатом и зарегистрированная подписка MAX; локальный порт 8080 предназначен для разработки.

Демонстрационный набор в `infra/seed/demo.sql` можно вручную применить после миграции к локальному файлу SQLite. Автоматически он не загружается.

## Состояние реализации

- Работают схема SQLite, FTS5 и триггеры, базовый webhook с проверкой секретного заголовка и постановкой сырого JSON в Redis. Получатель очереди и идемпотентность событий ещё не реализованы.
- Доменная модель, FTS5 candidate retriever и scorer являются точками продолжения; полноценный подбор требует фильтров категории, времени, статуса и объяснения результата.
- REST-контракт перечисляет маршруты §10. Реализованные заглушки возвращают 501; остальные маршруты пока только описаны в OpenAPI. Они закрыты настройкой безопасности до добавления JWT и house scope.
- MinIO присутствует в Compose, но загрузка вложений и bucket policy пока не подключены.
- FIAS/GIS/UK contract adapter — демонстрационная граница, он не выдает модельные ответы за официальные данные.
- `RequirementsTrace.java` перечисляет все FR, AC и NFR; наличие ID в коде не означает выполнения требования.

## Важные решения

SQLite открывается с WAL, `busy_timeout=5000`, `foreign_keys=ON`; размер пула Hikari — 1. Кандидаты дублей всегда выбираются в пределах `house_id`. Веса: категория 0,40, место 0,30, время 0,20, текст 0,10. Исходный `report` хранится отдельно от общей `issue`.

## Следующий этап

Реализовать сквозной путь `MAX Update → report → issue → candidates → join/create → статус → уведомление → приемка`, затем пройти AC-01…AC-18. Для локальной проверки POM и Docker нужны установленный JDK/Maven и работающий Docker; в текущем окружении этих команд не было.
