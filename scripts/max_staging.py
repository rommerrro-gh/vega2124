#!/usr/bin/env python3
"""Проверка стенда MAX и явная регистрация webhook без вывода секретов."""

import argparse
import json
import os
import pathlib
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request


def ssl_context():
    context = ssl.create_default_context()
    certificate = pathlib.Path(__file__).resolve().parents[1] / "infra/certs/russian_trusted_root_ca.pem"
    context.load_verify_locations(cafile=str(certificate))
    return context


def local_settings():
    values = {}
    path = pathlib.Path(__file__).resolve().parents[1] / ".env"
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    values.update(os.environ)
    return values


def get_json(url, token=None):
    headers = {"Authorization": token} if token else {}
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=10, context=ssl_context()) as response:
        return json.load(response)


def subscription_items(response):
    if isinstance(response, dict):
        response = response.get("subscriptions")
    if not isinstance(response, list):
        raise ValueError("MAX вернул неожиданный формат списка подписок")
    return response


def check(public_url, token, secret, api_url):
    errors = []
    if not token:
        errors.append("MAX_BOT_TOKEN не задан")
    if not re.fullmatch(r"[A-Za-z0-9_-]{5,256}", secret):
        errors.append("MAX_WEBHOOK_SECRET отсутствует или не соответствует формату MAX")
    elif secret in {"replace-with-random-local-secret", "local-dev-secret"}:
        errors.append("MAX_WEBHOOK_SECRET содержит примерное значение; задайте случайный секрет")
    parsed = urllib.parse.urlparse(public_url)
    if (parsed.scheme != "https" or not parsed.hostname
            or parsed.hostname in {"localhost", "127.0.0.1"}
            or parsed.path not in {"", "/"} or parsed.query or parsed.fragment):
        errors.append("нужен публичный MAX_PUBLIC_URL с HTTPS")
    for error in errors:
        print("НЕ ГОТОВО:", error)
    if errors:
        return False

    try:
        health = get_json(public_url + "/actuator/health")
        if health.get("status") != "UP":
            raise ValueError("backend не сообщает UP")
        print("OK: публичный backend доступен по HTTPS")
        request = urllib.request.Request(public_url + "/miniapp/index.html")
        with urllib.request.urlopen(request, timeout=10, context=ssl_context()) as response:
            if response.status != 200 or "text/html" not in response.headers.get("Content-Type", ""):
                raise ValueError("mini app не отдаёт HTML")
        print("OK: mini app доступен по HTTPS")
        get_json(api_url + "/me", token)
        print("OK: токен принят MAX API")
        subscriptions = subscription_items(get_json(api_url + "/subscriptions", token))
        count = len(subscriptions)
        print("OK: подписки webhook доступны, количество:", count)
        return True
    except (urllib.error.URLError, ValueError, json.JSONDecodeError) as error:
        print("НЕ ГОТОВО: проверка сети или API не прошла:", error)
        return False


def subscribe(public_url, token, secret, api_url):
    try:
        existing = subscription_items(get_json(api_url + "/subscriptions", token))
        if any(item.get("url") == public_url + "/webhooks/max"
               for item in existing if isinstance(item, dict)):
            print("OK: webhook MAX уже зарегистрирован для этого адреса")
            return True
    except (urllib.error.URLError, ValueError, json.JSONDecodeError) as error:
        print("Не удалось проверить существующие подписки:", error)
        return False
    payload = json.dumps({
        "url": public_url + "/webhooks/max",
        "update_types": ["message_created", "bot_started"],
        "secret": secret,
    }).encode("utf-8")
    request = urllib.request.Request(api_url + "/subscriptions", data=payload,
                                     headers={"Authorization": token, "Content-Type": "application/json"},
                                     method="POST")
    try:
        with urllib.request.urlopen(request, timeout=15, context=ssl_context()) as response:
            result = json.load(response)
    except (urllib.error.URLError, json.JSONDecodeError) as error:
        print("Подписка не создана:", error)
        return False
    if result.get("success") is True:
        print("OK: webhook MAX зарегистрирован")
        return True
    print("Подписка не создана:", result.get("message", "MAX вернул отказ"))
    return False


def unsubscribe(public_url, token, api_url):
    url = api_url + "/subscriptions?" + urllib.parse.urlencode({
        "url": public_url + "/webhooks/max",
    })
    request = urllib.request.Request(url, headers={"Authorization": token}, method="DELETE")
    try:
        with urllib.request.urlopen(request, timeout=15, context=ssl_context()) as response:
            result = json.load(response)
    except (urllib.error.URLError, json.JSONDecodeError) as error:
        print("Не удалось удалить подписку:", error)
        return False
    if result.get("success") is True:
        print("OK: webhook MAX удалён")
        return True
    print("Подписка не удалена:", result.get("message", "MAX вернул отказ"))
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check", "subscribe", "unsubscribe"])
    parser.add_argument("--public-url", help="HTTPS-адрес стенда; по умолчанию MAX_PUBLIC_URL")
    args = parser.parse_args()
    settings = local_settings()
    public_url = (args.public_url or settings.get("MAX_PUBLIC_URL", "")).rstrip("/")
    token = settings.get("MAX_BOT_TOKEN", "")
    secret = settings.get("MAX_WEBHOOK_SECRET", "")
    api_url = settings.get("MAX_API_BASE_URL", "https://platform-api2.max.ru").rstrip("/")
    if args.command == "unsubscribe":
        if not token or not public_url.startswith("https://"):
            print("НЕ ГОТОВО: нужны MAX_BOT_TOKEN и MAX_PUBLIC_URL с HTTPS")
            return 2
        return 0 if unsubscribe(public_url, token, api_url) else 1
    if not check(public_url, token, secret, api_url):
        return 2
    if args.command == "subscribe" and not subscribe(public_url, token, secret, api_url):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
