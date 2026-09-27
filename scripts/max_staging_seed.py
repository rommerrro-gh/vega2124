#!/usr/bin/env python3
"""Создать тестовый дом/приглашение и назначить диспетчера в SQLite стенда."""

import argparse
import datetime
import hashlib
import pathlib
import secrets
import sqlite3
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, type=pathlib.Path, help="Файл SQLite стенда после миграций")
    commands = parser.add_subparsers(dest="command", required=True)
    house = commands.add_parser("house", help="Создать тестовый дом")
    house.add_argument("--address", required=True)
    invitation = commands.add_parser("invite", help="Создать приглашение жителя на 7 дней")
    invitation.add_argument("--house-id", required=True)
    commands.add_parser("users", help="Показать вошедших пользователей MAX")
    dispatcher = commands.add_parser("dispatcher", help="Выдать роль диспетчера вошедшему пользователю")
    dispatcher.add_argument("--house-id", required=True)
    dispatcher.add_argument("--max-user-id", required=True)
    args = parser.parse_args()

    if not args.db.is_file():
        parser.error("файл БД не найден; сначала запустите backend и миграции")
    with sqlite3.connect(args.db) as db:
        db.execute("PRAGMA foreign_keys=ON")
        if args.command == "house":
            house_id = str(uuid.uuid4())
            db.execute("INSERT INTO houses(id, address) VALUES (?, ?)", (house_id, args.address))
            print("house_id:", house_id)
        elif args.command == "invite":
            token = secrets.token_urlsafe(24)
            expires_at = (datetime.datetime.now(datetime.timezone.utc)
                          + datetime.timedelta(days=7)).isoformat()
            db.execute("""
                INSERT INTO house_invitations(id, house_id, token_hash, expires_at, activation_limit)
                VALUES (?, ?, ?, ?, 10)
                """, (str(uuid.uuid4()), args.house_id,
                      hashlib.sha256(token.encode()).hexdigest(), expires_at))
            print("Токен приглашения (передайте тестовым участникам приватно):", token)
            print("Откройте mini app с параметром ?invite=<токен> либо startapp=<токен>.")
        elif args.command == "users":
            for max_id, name in db.execute("""
                    SELECT max_user_id, display_name FROM users
                    WHERE max_user_id IS NOT NULL ORDER BY created_at DESC
                    """):
                print(max_id, name)
        elif args.command == "dispatcher":
            row = db.execute("SELECT id FROM users WHERE max_user_id = ? AND status = 'ACTIVE'",
                             (args.max_user_id,)).fetchone()
            if not row:
                parser.error("пользователь ещё не входил в mini app или неактивен")
            db.execute("""
                INSERT INTO house_memberships(house_id, user_id, role, verification_status)
                VALUES (?, ?, 'DISPATCHER', 'VERIFIED')
                ON CONFLICT(house_id, user_id, role) DO UPDATE SET verification_status = 'VERIFIED'
                """, (args.house_id, row[0]))
            print("Роль диспетчера выдана")


if __name__ == "__main__":
    main()
