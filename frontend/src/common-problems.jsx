import { Users } from "@phosphor-icons/react";
import React, { useEffect, useRef, useState } from "react";
import { PageRow, PageTitle } from "./account-pages.jsx";
import { go } from "./navigation.jsx";
const states = {
  RECEIVED: "Принято",
  IN_REVIEW: "Разбираемся",
  IN_PROGRESS: "В работе",
  WAITING_RESIDENT: "Уточняем сведения",
  RESULT_PROPOSED: "Работа выполнена",
  CLOSED: "Решено",
  REOPENED: "Повторная проверка",
};
export function CommonProblems({ houseId, role, api, id, onHouse }) {
  const [items, setItems] = useState(null),
    [notice, setNotice] = useState(""),
    [confirmJoin, setConfirmJoin] = useState(false),
    [joining, setJoining] = useState(false),
    [joinNotice, setJoinNotice] = useState("");
  const joinInFlight = useRef(false),
    pendingJoin = useRef(null);
  useEffect(() => {
    let active = true;
    setItems(null);
    setNotice("");
    setConfirmJoin(false);
    setJoinNotice("");
    api(
      role,
      id
        ? `/v1/shared-problems/${encodeURIComponent(id)}`
        : `/v1/houses/${houseId}/shared-problems`,
    )
      .then(async (data) => {
        if (active) {
          if (id && data.houseId !== houseId) await onHouse(data.houseId);
          setItems(id ? [data] : data);
        }
      })
      .catch((e) => active && setNotice(e.message));
    return () => {
      active = false;
    };
  }, [houseId, id, role]);
  async function join(g) {
    if (joinInFlight.current) return;
    joinInFlight.current = true;
    setJoining(true);
    setJoinNotice("");
    try {
      const context = await api(role, `/v1/houses/${g.houseId}/case-context`);
      const storageKey = `pulse-common-join:${context.actorId}:${g.houseId}:${g.id}`;
      if (pendingJoin.current?.storageKey !== storageKey) {
        let saved;
        try {
          saved = JSON.parse(sessionStorage.getItem(storageKey));
        } catch {}
        pendingJoin.current = {
          storageKey,
          clientRequestId:
            saved &&
            typeof saved.clientRequestId === "string" &&
            saved.clientRequestId.length > 0 &&
            Date.now() - saved.savedAt < 86400000
              ? saved.clientRequestId
              : crypto.randomUUID(),
        };
      }
      const { clientRequestId } = pendingJoin.current;
      try {
        sessionStorage.setItem(
          storageKey,
          JSON.stringify({ clientRequestId, savedAt: Date.now() }),
        );
      } catch {}
      const created = await api(role, `/v1/shared-requests/${g.id}/join`, {
        method: "POST",
        body: JSON.stringify({
          problem: {
            houseId: g.houseId,
            description: g.title,
            category: g.category,
            location: g.location || null,
          },
          clientRequestId,
        }),
      });
      try {
        sessionStorage.removeItem(storageKey);
      } catch {}
      pendingJoin.current = null;
      go(`/miniapp/cases/${created.id}`);
    } catch (e) {
      setJoinNotice(`${e.message}. Можно повторить присоединение — копия не создастся.`);
    } finally {
      joinInFlight.current = false;
      setJoining(false);
    }
  }
  if (notice)
    return (
      <p className="notice" role="alert">
        {notice}
      </p>
    );
  if (!items) return <p role="status">Загружаем общие проблемы…</p>;
  if (!id)
    return (
      <section>
        <h2>Общие проблемы дома</h2>
        {items.map((g) => (
          <PageRow
            key={g.id}
            icon={Users}
            title={g.title}
            subtitle={`${states[g.status]} · Участников: ${g.participants} · ${g.location || "Место уточняется"}`}
            onClick={() => go(`/miniapp/shared/${g.id}`)}
          />
        ))}
        {!items.length && (
          <p className="muted">
            Пока нет общих проблем. Сообщите о неисправности — проверим, не сталкивались ли с ней
            соседи.
          </p>
        )}
      </section>
    );
  const g = items[0];
  return (
    <section className="common-page">
      <PageTitle>Общая проблема</PageTitle>
      <h2>{g.title}</h2>
      <p className="muted">{g.location || "Место уточняется"}</p>
      <p className="status-pill">
        {states[g.status]} · Участников: {g.participants}
      </p>
      <div className="info-note">
        <strong>{g.nextStep}</strong>
        <p>{g.responsibleName}</p>
        {g.dueAt && <p>Срок: {new Date(g.dueAt).toLocaleString("ru-RU")}</p>}
      </div>
      <p className="muted">
        Работа и сроки общие. Личные сообщения, файлы и время визита соседям не показываются.
      </p>
      {g.myCaseId ? (
        <PageRow
          icon={Users}
          title="Моё обращение"
          onClick={() => go(`/miniapp/cases/${g.myCaseId}`)}
        />
      ) : (
        g.status !== "CLOSED" && (
          <div className="common-join">
            {!confirmJoin ? (
              <button type="button" onClick={() => setConfirmJoin(true)}>
                У меня такая же проблема
              </button>
            ) : (
              <section aria-label="Присоединиться к общей проблеме">
                <h3>Это та же проблема?</h3>
                <p>
                  {g.title} · {g.location || "Место уточняется"}
                </p>
                <p>
                  Создадим ваше обращение и присоединим к общей работе. Повторять описание не нужно.
                  Личные сообщения и документы останутся закрытыми от соседей.
                </p>
                {joinNotice && <p role="alert">{joinNotice}</p>}
                <button type="button" disabled={joining} onClick={() => join(g)}>
                  {joining ? "Присоединяем…" : "Да, присоединиться"}
                </button>
                <button
                  className="common-join-cancel"
                  type="button"
                  disabled={joining}
                  onClick={() => setConfirmJoin(false)}
                >
                  Отмена
                </button>
              </section>
            )}
          </div>
        )
      )}
      <h3>Ход общей работы</h3>
      <ol className="case-timeline">
        {g.history.map((e, i) => (
          <li key={i}>
            <small>{new Date(e.createdAt).toLocaleString("ru-RU")}</small>
            <p>{e.note}</p>
          </li>
        ))}
      </ol>
      {g.status === "RESULT_PROPOSED" && (
        <p>
          Подтвердили результат: {g.confirmedCount} из {g.confirmationParticipants} действующих
          участников.
        </p>
      )}
    </section>
  );
}
