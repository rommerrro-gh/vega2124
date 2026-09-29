# Публичный маршрут отдельного стенда MAX

На ВМ два независимых Compose-проекта:

| Адрес | Проект | Backend | SQLite |
| --- | --- | --- | --- |
| `https://pulsedoma.ru` | `pulsedoma` | `127.0.0.1:8080` | `pulsedoma_sqlite-data` |
| `https://www.pulsedoma.ru` | `pulsedoma_max` | `127.0.0.2:8080` | `pulsedoma_max_sqlite-data` |

Второй проект находится в `/home/deploy/pulsedoma-max` и использует отдельный `.env` с правами `600`. Его не нужно копировать в Git или в переписку. При обновлении этого стенда используйте `docker-compose -p pulsedoma_max` внутри его каталога. Не выполняйте `down -v`: это удалит тестовую базу и остальные тома.

## Действия владельца ВМ с sudo

DNS-запись `www.pulsedoma.ru` уже ведёт на ту же ВМ. Сначала получите отдельный сертификат; до этого HTTPS для `www` не проходит проверку имени:

```sh
sudo certbot certonly --nginx -d www.pulsedoma.ru
```

После выпуска сертификата скопируйте `nginx-demo.conf` в `/etc/nginx/sites-available/pulsedoma`, а `nginx-max.conf` в `/etc/nginx/sites-available/pulsedoma-max`. Включите второй сайт ссылкой из `/etc/nginx/sites-enabled/`, проверьте конфигурацию командой `sudo nginx -t` и только после успешной проверки выполните `sudo systemctl reload nginx`. Первый файл сохраняет демо на основном домене; второй маршрутизирует `www` в отдельный backend.

После переключения проверьте оба адреса: `/actuator/health` должен возвращать `{"status":"UP"}`. Для выданного организаторами бота передайте `https://www.pulsedoma.ru/miniapp/index.html` через [форму подключения](https://sbor-ssylok-dlya-mini-prilojeniy.testograf.ru/), используя регистрационные данные капитана команды. Затем в `/home/deploy/pulsedoma-max` запустите `python3 scripts/max_staging.py check` и, если проверка успешна, `python3 scripts/max_staging.py subscribe`.

Далее выполните [сценарий проверки](../../docs/max-staging.md) с отдельными аккаунтами MAX для жителя, диспетчера и администратора. Не используйте демопрофиль для проверки подписи MAX и доставки уведомлений.
