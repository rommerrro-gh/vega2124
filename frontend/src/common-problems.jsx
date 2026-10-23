import { Users } from "@phosphor-icons/react";
import React, { useEffect, useState } from "react";
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
    [notice, setNotice] = useState("");
  useEffect(() => {
    let active = true;
    setItems(null);
    setNotice("");
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
        <button onClick={() => go(`/miniapp/cases/${g.myCaseId}`)}>Моё обращение</button>
      ) : (
        g.status !== "CLOSED" && (
          <button onClick={() => go(`/miniapp/new?shared=${encodeURIComponent(g.id)}`)}>
            У меня такая же проблема
          </button>
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
