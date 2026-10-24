import { ChatCircleDots, Files, ClockCounterClockwise, Users } from "@phosphor-icons/react";
import { go, useRoute } from "./navigation.jsx";
import { PageRow, PageTitle } from "./account-pages.jsx";
import { SharedRequestPanel } from "./shared-requests.jsx";
import {
  PlaceFields,
  defaultPlace,
  problemCategories,
  placeDescription,
} from "./problem-fields.jsx";
import {
  NotePencil,
  Receipt,
  ArrowRight,
  Question,
  CheckCircle,
  Clock,
  Circle,
  CalendarBlank,
} from "./design.jsx";
import {
  CaseVisits,
  CaseMassProblem,
  HouseOperations,
  OperationsManagement,
} from "./operations.jsx";
import React, { useEffect, useState } from "react";
import { BillingCase, BillingQueue } from "./billing-cases.jsx";
import { CaseExchange, ControlPanel, ExchangeRegistry } from "./case-exchange.jsx";
import { CaseWorkflow } from "./warranty-workflow.jsx";

const statuses = {
  RECEIVED: "Принято к рассмотрению",
  IN_REVIEW: "Разбираемся",
  WAITING_RESIDENT: "Нужен ваш ответ",
  IN_PROGRESS: "В работе",
  RESULT_PROPOSED: "Посмотрите результат",
  CLOSED: "Результат подтверждён",
  REOPENED: "Проверяем повторно",
};
const kinds = {
  PROBLEM: "Проблема дома",
  PAYMENT: "Вопрос по квитанции",
  WARRANTY: "Возможный строительный дефект",
};
const date = (value) =>
  value
    ? new Date(value).toLocaleString("ru-RU", {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "Срок уточняется";
const key = () => crypto.randomUUID();
const text = (item) =>
  item.description.length > 110 ? `${item.description.slice(0, 110)}…` : item.description;

function Notice({ children }) {
  return children ? (
    <div className="case-notice" role="status">
      {children}
    </div>
  ) : null;
}

export function CaseDetail({
  item,
  api,
  role,
  headers,
  context,
  onChange,
  onClose,
  residentOnly = false,
  section = "overview",
}) {
  const [message, setMessage] = useState("");
  const [messageKey, setMessageKey] = useState(key);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [edit, setEdit] = useState({});
  const [documentId, setDocumentId] = useState("");
  const [file, setFile] = useState(null);
  const [title, setTitle] = useState("");
  const [target, setTarget] = useState("");
  const [functionName, setFunctionName] = useState("ENGINEER");
  useEffect(
    () =>
      setEdit({
        kind: item.kind,
        status: ["RECEIVED", "CLOSED", "REOPENED"].includes(item.status)
          ? "IN_REVIEW"
          : item.status,
        nextStep: item.nextStep,
        dueAt: item.dueAt
          ? new Date(
              new Date(item.dueAt).getTime() - new Date(item.dueAt).getTimezoneOffset() * 60000,
            )
              .toISOString()
              .slice(0, 16)
          : "",
        responsibleUserId: item.responsibleUserId || "",
        reason: "",
      }),
    [item.id, item.revision],
  );
  async function act(path, body, method = "POST") {
    setBusy(true);
    setNotice("");
    try {
      const updated = await api(
        role,
        path,
        method === "GET" ? { method } : { method, body: JSON.stringify(body) },
      );
      onChange(updated);
      return true;
    } catch (error) {
      setNotice(error.message);
      return false;
    } finally {
      setBusy(false);
    }
  }
  async function upload(event) {
    event.preventDefault();
    if (!file) return;
    setBusy(true);
    setNotice("");
    try {
      const doc = item.documents.find((d) => d.id === documentId);
      const query = new URLSearchParams({
        title: title.trim() || file.name,
        expectedVersion: String(doc?.versions[0]?.version || 0),
      });
      if (documentId) query.set("documentId", documentId);
      const body = new FormData();
      body.append("file", file);
      onChange(
        await api(role, `/v1/cases/${item.id}/documents?${query}`, { method: "POST", body }),
      );
      setFile(null);
      setTitle("");
      setDocumentId("");
      event.target.reset();
    } catch (error) {
      setNotice(error.message);
    } finally {
      setBusy(false);
    }
  }
  async function download(v) {
    setNotice("");
    try {
      const response = await fetch(`/v1/case-document-versions/${v.id}`, {
        headers: headers(role),
      });
      if (!response.ok)
        throw new Error("Документ недоступен. Обновите обращение и проверьте доступ.");
      const url = URL.createObjectURL(await response.blob()),
        a = document.createElement("a");
      a.href = url;
      a.download = `document-v${v.version}.${v.mime === "application/pdf" ? "pdf" : v.mime === "image/png" ? "png" : "jpg"}`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 30000);
    } catch (error) {
      setNotice(error.message);
    }
  }
  return (
    <section
      className="case-panel"
      data-case-section={residentOnly ? section : undefined}
      aria-label={`Обращение ${item.number}`}
    >
      {residentOnly && (
        <PageTitle>
          {
            {
              overview: `Обращение № ${item.number}`,
              visits: "Выбор времени",
              messages: "Переписка",
              documents: "Документы",
              history: "История обращения",
              billing: "Проверка начисления",
            }[section]
          }
        </PageTitle>
      )}
      <div className="case-heading">
        <div>
          <small>
            ОБРАЩЕНИЕ № {item.number} · {kinds[item.kind]}
          </small>
          <h2>
            {residentOnly
              ? section === "visits"
                ? "Когда вам удобно?"
                : item.description
              : statuses[item.status]}
          </h2>
        </div>
        {!residentOnly && (
          <button type="button" className="case-link" onClick={onClose}>
            К списку
          </button>
        )}
      </div>
      <p className="muted">{item.address}</p>
      {!residentOnly && <p className="case-description">{item.description}</p>}
      {residentOnly && section === "overview" && (
        <p className="status-pill">{statuses[item.status]}</p>
      )}
      {(!residentOnly || section === "overview") && item.category && (
        <p>Категория: {problemCategories[item.category]}</p>
      )}
      {(!residentOnly || section === "overview") && item.location && (
        <p>Где заметили: {item.location}</p>
      )}
      <div className="case-next" hidden={residentOnly && section !== "overview"}>
        <strong>{item.nextStep}</strong>
        <p>Кто ведёт: {item.responsibleName}</p>
        <p>{item.dueAt ? `Срок: ${date(item.dueAt)}` : "Срок уточняется"}</p>
        {item.status !== "WAITING_RESIDENT" &&
          item.status !== "RESULT_PROPOSED" &&
          item.status !== "CLOSED" && (
            <small>Следующий шаг указан выше. При необходимости можно добавить сведения.</small>
          )}
      </div>
      <Notice>{notice}</Notice>
      {!residentOnly && (
        <SharedRequestPanel item={item} role={role} api={api} onChange={onChange} />
      )}
      {residentOnly && section === "overview" && (
        <div className="page-list">
          {item.sharedRequestId && (
            <PageRow
              icon={Users}
              title="Общая проблема дома"
              subtitle={`Участников: ${item.participants || 1} · Общий план и сроки`}
              onClick={() => go(`/miniapp/shared/${item.sharedRequestId}`)}
            />
          )}
          <PageRow
            icon={CalendarBlank}
            title="Время визита"
            subtitle={item.visitOffered ? "Выберите удобное время" : "Предложения и согласование"}
            onClick={() => go(`/miniapp/cases/${item.id}/visits`)}
          />
          <PageRow
            icon={ChatCircleDots}
            title="Переписка"
            subtitle="Ваши сообщения и ответы сотрудников"
            onClick={() => go(`/miniapp/cases/${item.id}/messages`)}
          />
          <PageRow
            icon={Files}
            title="Документы"
            subtitle={`Файлов: ${item.documents.length}`}
            onClick={() => go(`/miniapp/cases/${item.id}/documents`)}
          />
          <PageRow
            icon={ClockCounterClockwise}
            title="История обращения"
            onClick={() => go(`/miniapp/cases/${item.id}/history`)}
          />
          {item.kind === "PAYMENT" && (
            <PageRow
              icon={Receipt}
              title="Проверка начисления"
              onClick={() => go(`/miniapp/cases/${item.id}/billing`)}
            />
          )}
        </div>
      )}
      {residentOnly &&
        section === "overview" &&
        item.isAuthor &&
        item.sharedRequestId &&
        !item.withdrawn &&
        item.status !== "CLOSED" && (
          <details className="faq-row">
            <summary>Отозвать моё обращение</summary>
            <p className="muted">Общая работа и обращения соседей сохранятся.</p>
            <label>
              Причина
              <textarea
                maxLength={1000}
                value={message}
                onChange={(e) => setMessage(e.target.value)}
              />
            </label>
            <button
              type="button"
              className="case-secondary"
              disabled={busy || !message.trim()}
              onClick={() =>
                act(`/v1/cases/${item.id}/withdraw`, { revision: item.revision, reason: message })
              }
            >
              Подтвердить отзыв
            </button>
          </details>
        )}
      {residentOnly && item.withdrawn && (
        <p className="info-note">Вы отозвали обращение. История сохранена.</p>
      )}
      {item.isAuthor && !item.withdrawn && ["RESULT_PROPOSED", "CLOSED"].includes(item.status) && (
        <div className="case-actions">
          {item.status === "RESULT_PROPOSED" && (
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                act(`/v1/cases/${item.id}/response`, { confirmed: true, revision: item.revision })
              }
            >
              Да, вопрос решён
            </button>
          )}
          <button
            type="button"
            className="case-secondary"
            disabled={busy}
            onClick={() =>
              act(`/v1/cases/${item.id}/response`, {
                confirmed: false,
                revision: item.revision,
                comment: message,
              })
            }
          >
            Вопрос остался — прошу проверить
          </button>
        </div>
      )}
      {item.fullAccess && !item.withdrawn && (
        <>
          {(!residentOnly || section === "visits") &&
            (item.id !== item.workflowCaseId ? (
              <CaseVisits
                item={item}
                role={role}
                api={api}
                onChange={onChange}
                residentOnly={residentOnly}
              />
            ) : (
              <p className="muted">
                Чтобы предложить жителю время визита, откройте его материалы в участниках общей
                заявки.
              </p>
            ))}
          {!residentOnly && (
            <CaseMassProblem item={item} role={role} api={api} onChange={onChange} />
          )}
          {(!residentOnly || section === "documents") && (
            <CaseExchange
              item={residentOnly ? { ...item, canManage: false } : item}
              role={role}
              api={api}
              headers={headers}
              onChange={onChange}
              residentOnly={residentOnly}
            />
          )}
          {item.kind === "PAYMENT" && (!residentOnly || section === "billing") && (
            <BillingCase
              item={residentOnly ? { ...item, canManage: false } : item}
              role={role}
              api={api}
              headers={headers}
              onChange={onChange}
              residentOnly={residentOnly}
            />
          )}
          {(!residentOnly || ["visits", "documents", "history"].includes(section)) &&
            item.kind !== "PAYMENT" && (
              <CaseWorkflow
                item={item}
                role={role}
                api={api}
                headers={headers}
                onChange={onChange}
                residentOnly={residentOnly}
                section={section}
              />
            )}
          {(!residentOnly || ["messages", "history"].includes(section)) && (
            <>
              <h3>{section === "messages" ? "Переписка" : "История и ответы"}</h3>
              <ol className="case-timeline">
                {item.events.map((event) => (
                  <li key={event.id}>
                    <small>
                      {date(event.createdAt)} · {event.actor}
                    </small>
                    <p>{event.text}</p>
                  </li>
                ))}
              </ol>
            </>
          )}
          {item.status !== "CLOSED" && (!residentOnly || section === "messages") && (
            <form
              onSubmit={async (event) => {
                event.preventDefault();
                if (
                  await act(`/v1/cases/${item.id}/messages`, {
                    text: message,
                    clientRequestId: messageKey,
                  })
                ) {
                  setMessage("");
                  setMessageKey(key());
                }
              }}
            >
              <label>
                Добавить сведения или ответить
                <textarea
                  maxLength={4000}
                  required
                  value={message}
                  onChange={(event) => setMessage(event.target.value)}
                  placeholder="Напишите своими словами. Можно ответить, что нужных сведений у вас нет."
                />
              </label>
              <button disabled={busy || !message.trim()}>Отправить ответ</button>
            </form>
          )}
          <details
            className="case-documents"
            open={residentOnly && section === "documents" ? true : undefined}
            hidden={residentOnly && section !== "documents"}
          >
            <summary>Документы · {item.documents.length}</summary>
            <p className="muted">
              Документы доступны участникам обработки с необходимыми полномочиями. Соседям они не
              показываются.
            </p>
            {item.documents.map((doc) => (
              <div className="case-document" key={doc.id}>
                <strong>{doc.title}</strong>
                {doc.versions.map((v) => (
                  <button
                    type="button"
                    className="case-link"
                    key={v.id}
                    onClick={() => download(v)}
                  >
                    Скачать · версия {v.version} · {date(v.createdAt)} · {v.uploadedBy}
                  </button>
                ))}
              </div>
            ))}
            {item.status !== "CLOSED" && (
              <form onSubmit={upload}>
                <label>
                  Добавить документ
                  <select value={documentId} onChange={(e) => setDocumentId(e.target.value)}>
                    <option value="">Новый документ</option>
                    {item.documents.map((d) => (
                      <option key={d.id} value={d.id}>
                        Новая версия: {d.title}
                      </option>
                    ))}
                  </select>
                </label>
                {!documentId && (
                  <label>
                    Название (по желанию)
                    <input
                      maxLength={160}
                      value={title}
                      onChange={(e) => setTitle(e.target.value)}
                    />
                  </label>
                )}
                <label>
                  Файл PDF, JPEG или PNG до 10 МБ
                  <input
                    type="file"
                    required
                    accept="application/pdf,image/jpeg,image/png"
                    onChange={(e) => setFile(e.target.files[0])}
                  />
                </label>
                <button disabled={busy || !file}>Добавить файл</button>
              </form>
            )}
          </details>
        </>
      )}
      {!residentOnly && item.canManage && item.status !== "CLOSED" && (
        <details className="case-editor">
          <summary>Рассмотрение сотрудником</summary>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              act(
                `/v1/cases/${item.id}`,
                {
                  ...edit,
                  dueAt: edit.dueAt ? new Date(edit.dueAt).toISOString() : null,
                  responsibleUserId: edit.responsibleUserId || null,
                  revision: item.revision,
                },
                "PATCH",
              );
            }}
          >
            <label>
              Направление
              <select
                value={edit.kind || item.kind}
                onChange={(e) => setEdit({ ...edit, kind: e.target.value })}
              >
                {Object.entries(kinds)
                  .filter(([k]) => item.kind !== "PAYMENT" || k === "PAYMENT")
                  .map(([k, v]) => (
                    <option key={k} value={k}>
                      {v}
                    </option>
                  ))}
              </select>
            </label>
            <label>
              Состояние
              <select
                value={edit.status || "IN_REVIEW"}
                onChange={(e) => setEdit({ ...edit, status: e.target.value })}
              >
                {["IN_REVIEW", "WAITING_RESIDENT", "IN_PROGRESS", "RESULT_PROPOSED"].map((s) => (
                  <option key={s} value={s}>
                    {statuses[s]}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Общий следующий шаг — виден участникам
              <textarea
                required
                maxLength={600}
                value={edit.nextStep || ""}
                onChange={(e) => setEdit({ ...edit, nextStep: e.target.value })}
                placeholder="Понятный жителю следующий шаг; при уточнении объясните, что нужно и зачем"
              />
            </label>
            <label>
              Ответственный
              <select
                value={edit.responsibleUserId || ""}
                onChange={(e) => setEdit({ ...edit, responsibleUserId: e.target.value })}
              >
                <option value="">Очередь первичного рассмотрения</option>
                {context?.staff
                  .filter((s) => item.kind !== "PAYMENT" || s.billingAccess)
                  .map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
              </select>
            </label>
            <label>
              Срок следующего шага (если известен)
              <input
                type="datetime-local"
                value={edit.dueAt || ""}
                onChange={(e) => setEdit({ ...edit, dueAt: e.target.value })}
              />
            </label>
            <p className="muted">
              Это срок указанного шага. Отправка внешней организации и гарантийный ремонт здесь не
              подтверждаются.
            </p>
            <label>
              Причина изменения срока или ответственного
              <input
                maxLength={1000}
                value={edit.reason || ""}
                onChange={(e) => setEdit({ ...edit, reason: e.target.value })}
                placeholder="Обязательна при переносе известного срока или смене сотрудника"
              />
            </label>
            <button disabled={busy}>Сохранить рассмотрение</button>
          </form>
        </details>
      )}
      {!residentOnly && context?.canManagePermissions && (
        <details className="case-editor">
          <summary>Участники конкретного обращения</summary>
          <p className="muted">
            Назначение в общей заявке открывает сотруднику материалы обращений её участников.
            Финансовые дела требуют отдельного полномочия. Право инженера или исполнителя не даёт
            права подтвердить результат за жителя.
          </p>
          <label>
            Участник
            <select value={target} onChange={(e) => setTarget(e.target.value)}>
              <option value="">Выберите сотрудника</option>
              {context.staff.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Функция
            <select value={functionName} onChange={(e) => setFunctionName(e.target.value)}>
              <option value="ENGINEER">Инженер</option>
              <option value="CONTRACTOR">Исполнитель</option>
              <option value="COORDINATOR">Координатор</option>
              <option value="WARRANTY">Рассмотрение строительного дефекта</option>
              <option value="BILLING">Рассмотрение начислений</option>
            </select>
          </label>
          <div className="case-actions">
            <button
              type="button"
              disabled={busy || !target}
              onClick={() =>
                act(
                  `/v1/cases/${item.id}/collaborators/${target}`,
                  { function: functionName, enabled: true, revision: item.revision },
                  "PUT",
                )
              }
            >
              Назначить участника
            </button>
            <button
              type="button"
              className="case-secondary"
              disabled={busy || !target}
              onClick={() =>
                act(
                  `/v1/cases/${item.id}/collaborators/${target}`,
                  { function: functionName, enabled: false, revision: item.revision },
                  "PUT",
                )
              }
            >
              Отозвать назначение
            </button>
          </div>
        </details>
      )}
      <button
        type="button"
        className="case-link"
        disabled={busy}
        onClick={() => act(`/v1/cases/${item.id}`, null, "GET")}
      >
        Обновить обращение
      </button>
    </section>
  );
}

function FeaturedCase({ item, api, role, onOpen, onShowCases, total }) {
  const [visit, setVisit] = useState(null);
  useEffect(() => {
    let active = true;
    setVisit(null);
    if (item.isAuthor && item.fullAccess)
      api(role, `/v1/cases/${item.id}/visits`)
        .then((data) => {
          if (active)
            setVisit(
              data.visits.find((v) =>
                ["OFFERED", "CONFIRMED", "CHANGE_REQUESTED"].includes(v.state),
              ) || null,
            );
        })
        .catch(() => {});
    return () => {
      active = false;
    };
  }, [item.id, item.revision, role]);
  const offered = visit?.state === "OFFERED",
    confirmed = visit?.state === "CONFIRMED",
    finished = item.status === "CLOSED",
    result = item.status === "RESULT_PROPOSED";
  const slot = confirmed
    ? visit.slots.find((s) => s.id === visit.selected_slot_id)
    : offered && visit.slots.length === 1
      ? visit.slots[0]
      : null;
  return (
    <section className="case-panel case-featured">
      <div className="case-heading">
        <h2>Ваше обращение</h2>
      </div>
      <h3>{text(item)}</h3>
      <ol className="case-progress-summary">
        <li className="is-done">
          <CheckCircle size={24} weight="fill" />
          <div>
            <strong>Принято</strong>
            <small>{date(item.createdAt)}</small>
            <small>
              Ваше обращение зарегистрировано.
              {item.participants > 1 ? ` Участников: ${item.participants}` : ""}
            </small>
          </div>
        </li>
        <li className="is-current">
          <Circle size={24} weight="bold" />
          <div>
            <strong>
              {offered ? "Осмотр" : confirmed ? "Визит согласован" : statuses[item.status]}
            </strong>
            <p>
              {offered
                ? "Выберите удобное время визита"
                : confirmed
                  ? visit.purpose
                  : item.nextStep}
            </p>
            {slot && (
              <p className="case-summary-date">
                <CalendarBlank size={23} aria-hidden="true" />
                {date(slot.starts_at)} —{" "}
                {new Date(slot.ends_at).toLocaleTimeString("ru-RU", {
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </p>
            )}
            {!slot && item.dueAt && <p className="case-summary-date">{date(item.dueAt)}</p>}
            <button
              type="button"
              className="case-secondary"
              onClick={() =>
                onOpen(
                  offered ? "visits" : item.status === "WAITING_RESIDENT" ? "messages" : "overview",
                )
              }
            >
              {offered
                ? "Выбрать время"
                : result
                  ? "Проверить результат"
                  : item.status === "WAITING_RESIDENT"
                    ? "Ответить на обращение"
                    : "Открыть обращение"}{" "}
              <ArrowRight size={18} />
            </button>
          </div>
        </li>
        {!finished && !result && (
          <li className="is-future">
            <Circle size={24} />
            <div>
              <strong>Результат</strong>
              <small>
                {offered
                  ? "После осмотра сообщим о решении."
                  : "Сообщим, когда работа будет выполнена."}
              </small>
            </div>
          </li>
        )}
      </ol>
    </section>
  );
}

export function ResidentCases({
  house,
  role,
  api,
  headers,
  canSubmit,
  initialCaseId,
  view = "home",
  onShowCases,
  passport,
  candidateReason,
  route,
}) {
  const routePage = route?.page || view;
  const [items, setItems] = useState([]),
    [context, setContext] = useState(null),
    [detail, setDetail] = useState(null);
  const [compose, setCompose] = useState(false),
    [draft, setDraft] = useState(null),
    [files, setFiles] = useState([]);
  const [filter, setFilter] = useState("ALL"),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [review, setReview] = useState(false);
  const [matches, setMatches] = useState(null),
    [searchFailed, setSearchFailed] = useState(false);
  useEffect(() => {
    if (route?.page === "cases") setFilter(route.query.get("filter") || "ALL");
  }, [route?.key]);
  const houseId = house.id;
  const storageKey = context ? `pulse-case-v2:${context.actorId}:${houseId}` : null;
  useEffect(() => {
    let active = true;
    setDetail(null);
    setCompose(false);
    setDraft(null);
    setContext(null);
    setNotice("");
    setItems([]);
    setFiles([]);
    setReview(false);
    Promise.all([api(role, "/v1/cases"), api(role, `/v1/houses/${houseId}/case-context`)])
      .then(([list, ctx]) => {
        if (!active) return;
        setItems(list.filter((c) => c.houseId === houseId));
        setContext(ctx);
        if (initialCaseId) {
          const target = list.find((c) => c.id === initialCaseId && c.houseId === houseId);
          if (target) setDetail(target);
          else setNotice("Обращение недоступно. Проверьте выбранный дом и доступ.");
        }
        try {
          const saved = JSON.parse(
            sessionStorage.getItem(`pulse-case-v2:${ctx.actorId}:${houseId}`),
          );
          if (saved && Date.now() - saved.savedAt < 86400000) setDraft(saved);
        } catch {}
      })
      .catch((e) => {
        if (active) setNotice(e.message);
      });
    return () => {
      active = false;
    };
  }, [houseId, role]);
  useEffect(() => {
    function question(e) {
      if (e.detail?.houseId !== houseId || !context) return;
      if (draft) {
        setNotice(
          "Сохранённый черновик не заменён. Завершите его или удалите перед новым вопросом.",
        );
        setCompose(true);
        return;
      }
      save({
        kind: "PAYMENT",
        description: e.detail.text || "",
        location: "",
        clientRequestId: key(),
      });
      setCompose(true);
      setReview(false);
      setDetail(null);
    }
    window.addEventListener("pulse-payment-question", question);
    return () => window.removeEventListener("pulse-payment-question", question);
  }, [houseId, context, draft]);
  useEffect(() => {
    if (!context || !route) return;
    if (["new", "payment", "similar"].includes(route.page)) {
      if (!canSubmit) {
        setNotice("Для создания обращения нужен доступ жителя к этому дому.");
        return;
      }
      if (!draft)
        save({
          kind: route.page === "payment" ? "PAYMENT" : "PROBLEM",
          description: "",
          location: "",
          category: route.page === "payment" ? null : "",
          clientRequestId: key(),
        });
      else if (
        (route.page === "payment") !== (draft.kind === "PAYMENT") &&
        route.page !== "similar"
      ) {
        setNotice("Открыт сохранённый черновик. Завершите его или удалите перед новым обращением.");
        go(draft.kind === "PAYMENT" ? "/miniapp/payment" : "/miniapp/new", { replace: true });
      }
      setCompose(true);
      setDetail(null);
      setReview(route.page === "similar" || route.query.get("review") === "1" || !!draft?.pending);
      if (
        route.page === "similar" &&
        !matches &&
        !draft?.pending &&
        route.query.get("withoutSearch") !== "1"
      ) {
        setNotice("Черновик сохранён. Повторите поиск, чтобы проверить актуальные общие проблемы.");
        setReview(false);
        go("/miniapp/new", { replace: true });
      }
    } else {
      setCompose(false);
      setReview(false);
      if (route.page === "cases" && route.id) {
        let active = true;
        api(role, `/v1/cases/${encodeURIComponent(route.id)}`)
          .then((item) => {
            if (active && item.houseId === houseId && item.isAuthor) setDetail(item);
            else if (active) setNotice("Обращение недоступно для этого дома и профиля.");
          })
          .catch((e) => active && setNotice(e.message));
        return () => {
          active = false;
        };
      }
      setDetail(null);
    }
  }, [route?.key, context?.actorId, houseId]);
  function openCase(item) {
    setDetail(item);
    go(`/miniapp/cases/${item.id}`);
  }
  function save(next) {
    setMatches(null);
    setSearchFailed(false);
    setDraft(next);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify({ ...next, savedAt: Date.now() }));
    } catch {
      setNotice("Браузер не сохранил черновик. Не закрывайте страницу до отправки.");
    }
  }
  function begin(kind) {
    if (!draft)
      save({
        kind,
        description: "",
        location: "",
        category: kind === "PROBLEM" ? "" : null,
        clientRequestId: key(),
      });
    setCompose(true);
    setReview(!!draft?.pending);
    setDetail(null);
    go((draft?.kind || kind) === "PAYMENT" ? "/miniapp/payment" : "/miniapp/new");
  }
  function changed(next) {
    setDetail(next);
    if (["new", "payment", "similar"].includes(route?.page)) go(`/miniapp/cases/${next.id}`);
    setItems((list) =>
      [next, ...list.filter((c) => c.id !== next.id)].sort((a, b) => b.number - a.number),
    );
  }
  async function refresh() {
    setBusy(true);
    try {
      setItems((await api(role, "/v1/cases")).filter((c) => c.houseId === houseId));
      setNotice("");
    } catch (e) {
      setNotice(e.message);
    } finally {
      setBusy(false);
    }
  }
  function problemInput() {
    return {
      houseId,
      description: draft.description,
      category: draft.category || null,
      location: draft.location || null,
      locationFeatures: draft.precise ? draft.locationFeatures : null,
    };
  }
  async function checkSimilar() {
    setNotice("");
    setSearchFailed(false);
    if (draft.kind === "PROBLEM" && !draft.category) {
      setNotice("Выберите категорию. Если не уверены, выберите «Другое».");
      return;
    }
    if (draft.kind !== "PROBLEM") {
      setReview(true);
      go("/miniapp/payment?review=1");
      return;
    }
    setBusy(true);
    try {
      setMatches(
        await api(role, "/v1/cases/similar-problems", {
          method: "POST",
          body: JSON.stringify(problemInput()),
        }),
      );
      setReview(true);
      go("/miniapp/similar");
    } catch (e) {
      setNotice(
        `Не удалось проверить похожие проблемы: ${e.message}. Можно повторить поиск или отправить отдельное обращение без проверки.`,
      );
      setSearchFailed(true);
    } finally {
      setBusy(false);
    }
  }
  async function joinProblem(id, source = "SHARED") {
    setBusy(true);
    setNotice("");
    save({ ...draft, pending: true, joining: id, joiningSource: source });
    try {
      if (source === "SHARED") {
        let created = await api(role, `/v1/shared-requests/${id}/join`, {
          method: "POST",
          body: JSON.stringify({ problem: problemInput(), clientRequestId: draft.clientRequestId }),
        });
        changed(created);
        setCompose(false);
        setReview(false);
        setDraft(null);
        try {
          sessionStorage.removeItem(storageKey);
        } catch {}
        for (const file of files) {
          try {
            const body = new FormData();
            body.append("file", file);
            created = await api(
              role,
              `/v1/cases/${created.id}/documents?${new URLSearchParams({ title: file.name })}`,
              { method: "POST", body },
            );
            changed(created);
          } catch (e) {
            setNotice(
              `Вы присоединились к заявке. Файл «${file.name}» не добавлен: ${e.message}. Добавьте его в документах обращения.`,
            );
            break;
          }
        }
        setFiles([]);
        return;
      }
    } catch (e) {
      setNotice(
        e.message +
          (e.status
            ? ""
            : " Результат пока неизвестен. Повторите присоединение — копия не создастся."),
      );
      if (e.status && e.status < 500) save({ ...draft, pending: false, joining: null });
    } finally {
      setBusy(false);
    }
  }
  async function submit() {
    setBusy(true);
    setNotice("");
    const sending = { ...draft, pending: true };
    save(sending);
    try {
      let created = await api(role, "/v1/cases", {
        method: "POST",
        body: JSON.stringify({
          houseId,
          kind: sending.kind,
          description:
            sending.kind === "PAYMENT" && sending.period
              ? `${sending.description}\nПериод: ${sending.period}`
              : sending.description,
          location: sending.location || null,
          clientRequestId: sending.clientRequestId,
          category: sending.kind === "PROBLEM" ? sending.category || null : null,
          locationFeatures:
            sending.kind === "PROBLEM" && sending.precise ? sending.locationFeatures : null,
        }),
      });
      changed(created);
      setCompose(false);
      setReview(false);
      setDraft(null);
      try {
        sessionStorage.removeItem(storageKey);
      } catch {}
      for (const file of files) {
        try {
          const body = new FormData();
          body.append("file", file);
          created = await api(
            role,
            `/v1/cases/${created.id}/documents?${new URLSearchParams({ title: file.name })}`,
            { method: "POST", body },
          );
          changed(created);
        } catch (e) {
          setNotice(
            `Обращение № ${created.number} зарегистрировано. Не удалось добавить файл «${file.name}»: ${e.message}. Его можно приложить в разделе документов.`,
          );
          break;
        }
      }
      setFiles([]);
    } catch (e) {
      setNotice(
        e.message +
          (e.status
            ? ""
            : " Результат отправки пока неизвестен. Повторите ту же отправку — копия не создастся."),
      );
      if (e.status && e.status < 500) save({ ...sending, pending: false });
    } finally {
      setBusy(false);
    }
  }
  const waiting = items.filter((i) => ["WAITING_RESIDENT", "RESULT_PROPOSED"].includes(i.status));
  const visible = items.filter(
    (i) =>
      filter === "ALL" ||
      (filter === "ACTIVE"
        ? i.status !== "CLOSED"
        : filter === "WAITING"
          ? ["WAITING_RESIDENT", "RESULT_PROPOSED"].includes(i.status)
          : i.kind === filter),
  );
  const featured =
    items.find((i) => i.visitOffered && !i.withdrawn) ||
    waiting.find((i) => !i.withdrawn) ||
    items.find((i) => i.status !== "CLOSED" && !i.withdrawn) ||
    items.find((i) => !i.withdrawn);
  return (
    <section
      className="case-workspace"
      aria-label="Обращения по дому"
      hidden={!["home", "cases", "new", "payment", "similar", "events"].includes(routePage)}
    >
      <Notice>{notice}</Notice>
      {view === "cases" && !!waiting.length && !detail && !compose && (
        <div className="case-next">
          <strong>Нужен ваш ответ · {waiting.length}</strong>
          <p>{waiting[0].nextStep}</p>
          <button type="button" onClick={() => openCase(waiting[0])}>
            Открыть обращение
          </button>
        </div>
      )}
      {!detail && !compose && canSubmit && ["home", "cases"].includes(routePage) && (
        <div className="case-start">
          <button type="button" disabled={!context} onClick={() => begin("PROBLEM")}>
            <NotePencil size={26} aria-hidden="true" />
            <strong>Сообщить о проблеме</strong>
            <ArrowRight size={22} aria-hidden="true" />
            <small>Кто поможет — определим сами.</small>
          </button>
          <button
            id="resident-payment-start"
            type="button"
            disabled={!context}
            onClick={() => begin("PAYMENT")}
          >
            <Receipt size={26} aria-hidden="true" />
            <strong>Вопрос по квитанции</strong>
            <ArrowRight size={20} aria-hidden="true" />
          </button>

          {draft && (
            <div className="case-draft">
              <strong>Есть сохранённый черновик</strong>
              <button
                type="button"
                onClick={() => {
                  begin(draft.kind);
                }}
              >
                Продолжить
              </button>
              {!draft.pending && (
                <button
                  type="button"
                  className="case-link"
                  onClick={() => {
                    setDraft(null);
                    try {
                      sessionStorage.removeItem(storageKey);
                    } catch {}
                  }}
                >
                  Удалить черновик
                </button>
              )}
            </div>
          )}
        </div>
      )}
      {compose && draft && view !== "events" && (
        <section className="case-panel">
          <div className="case-heading">
            <h2>
              {review
                ? draft.kind === "PROBLEM" && matches?.length
                  ? "Возможно, об этом уже сообщили"
                  : "Проверьте сообщение"
                : draft.kind === "PAYMENT"
                  ? "Что вас смущает в квитанции?"
                  : "Что случилось?"}
            </h2>
            <button
              type="button"
              className="case-link"
              disabled={busy}
              onClick={() => {
                setCompose(false);
                go("/miniapp/cases");
              }}
            >
              Сохранить и закрыть
            </button>
          </div>
          <p className="case-address">Ваш дом: {house.address}</p>
          {!context?.intakeAvailable && (
            <Notice>
              В этом доме пока не назначен сотрудник. Черновик можно сохранить, но приём обращений
              ещё не подключён.
            </Notice>
          )}
          {review ? (
            <>
              <p className="case-description">{draft.description}</p>
              {draft.category && <p>Категория: {problemCategories[draft.category]}</p>}
              {draft.kind === "PROBLEM" && matches !== null && !draft.joining && (
                <section aria-label="Похожие проблемы">
                  <p className="muted">
                    Проверьте, не сообщили ли соседи об этой же проблеме. Личные документы и
                    переписка участников закрыты.
                  </p>
                  {matches.length ? (
                    matches.map((item) => (
                      <article className="case-next" key={`${item.source}:${item.id}`}>
                        <strong>{item.description}</strong>
                        {item.location && <p>{item.location}</p>}
                        <p>Участников: {item.participants || 1}</p>
                        <p className="muted">Проверьте описание и место.</p>
                        <ul>
                          {item.reasons
                            .filter(
                              (r) =>
                                !["structured_location", "same_house", "same_category"].includes(
                                  r,
                                ) && !r.startsWith("shared_terms:"),
                            )
                            .slice(0, 4)
                            .map((r) =>
                              r === "own_case" ? "Ваше обращение" : candidateReason?.(r),
                            )
                            .filter(Boolean)
                            .map((r, i) => (
                              <li key={i}>{r}</li>
                            ))}
                        </ul>
                        {item.source === "OWN_SHARED" ? (
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() => {
                              const id = item.caseId || item.id;
                              api(role, `/v1/cases/${id}`)
                                .then((target) => {
                                  openCase(target);
                                  setCompose(false);
                                })
                                .catch((e) => setNotice(e.message));
                            }}
                          >
                            Открыть своё обращение
                          </button>
                        ) : (
                          <>
                            <p className="muted">
                              Вы присоединитесь к одной общей работе. Ваши документы, переписка и
                              время визита останутся закрытыми от соседей.
                            </p>
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => joinProblem(item.id, item.source)}
                            >
                              Это та же проблема — присоединиться
                            </button>
                          </>
                        )}
                      </article>
                    ))
                  ) : (
                    <p>Похожих активных проблем не найдено. Можно отправить новое обращение.</p>
                  )}
                  <p>Если это другая проблема, отправьте отдельное обращение ниже.</p>
                </section>
              )}
              {draft.kind !== "PAYMENT" && (
                <p>
                  Где заметили:{" "}
                  {draft.precise
                    ? placeDescription(draft.locationFeatures)
                    : draft.location || "Пока не определено"}
                </p>
              )}
              {draft.kind === "PAYMENT" && draft.period && <p>Период: {draft.period}</p>}
              {files.length > 0 && <p>Приложения: {files.map((f) => f.name).join(", ")}</p>}
              <p>Выбирать организацию и определять причину не нужно.</p>
              <div className="case-actions">
                <button
                  type="button"
                  disabled={busy || !context?.intakeAvailable}
                  onClick={() =>
                    draft.joining
                      ? joinProblem(draft.joining, draft.joiningSource || "SHARED")
                      : submit()
                  }
                >
                  {busy
                    ? "Отправляем…"
                    : draft.joining
                      ? "Повторить присоединение"
                      : draft.pending
                        ? "Повторить отправку"
                        : draft.kind === "PROBLEM" && matches?.length
                          ? "У меня другая проблема — отправить отдельно"
                          : "Отправить обращение"}
                </button>
                <button
                  type="button"
                  className="case-secondary"
                  disabled={busy || draft.pending}
                  onClick={() => {
                    setReview(false);
                    go(draft.kind === "PAYMENT" ? "/miniapp/payment" : "/miniapp/new");
                  }}
                >
                  Изменить
                </button>
              </div>
            </>
          ) : (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                checkSimilar();
              }}
            >
              {draft.kind === "PROBLEM" && (
                <p className="muted">
                  Описание самой проблемы будет видно жителям дома. Не включайте личные данные;
                  документы и дополнительные сообщения останутся закрытыми.
                </p>
              )}
              <label>
                Опишите своими словами
                <textarea
                  autoFocus
                  required
                  maxLength={4000}
                  value={draft.description}
                  disabled={draft.pending || busy}
                  onChange={(e) => save({ ...draft, description: e.target.value })}
                  placeholder={
                    draft.kind === "PAYMENT"
                      ? "Например: сумма стала больше, не понимаю почему"
                      : "Например: после дождя мокнет стена. Не знаю, откуда течёт."
                  }
                />
              </label>
              {draft.kind === "PAYMENT" && (
                <label>
                  Период, если знаете
                  <input
                    type="month"
                    onInput={(e) => save({ ...draft, period: e.currentTarget.value })}
                    value={draft.period || ""}
                    disabled={busy || draft.pending}
                    onChange={(e) => save({ ...draft, period: e.target.value })}
                  />
                  <small>Можно не указывать.</small>
                </label>
              )}
              {draft.kind === "PROBLEM" && (
                <>
                  <label>
                    Категория проблемы
                    <select
                      required
                      disabled={draft.pending || busy}
                      value={draft.category || ""}
                      onChange={(e) =>
                        save({
                          ...draft,
                          category: e.target.value,
                          precise: false,
                          locationFeatures: defaultPlace(e.target.value),
                        })
                      }
                    >
                      <option value="">Выберите категорию</option>
                      {Object.entries(problemCategories).map(([value, label]) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <p className="muted">
                    Если не уверены, выберите «Другое» и опишите ситуацию своими словами.
                  </p>
                  {draft.category && (
                    <label className="problem-precision">
                      <input
                        type="checkbox"
                        disabled={draft.pending || busy}
                        checked={!!draft.precise}
                        onChange={(e) =>
                          save({
                            ...draft,
                            precise: e.target.checked,
                            locationFeatures:
                              draft.locationFeatures || defaultPlace(draft.category),
                          })
                        }
                      />{" "}
                      Могу уточнить место для поиска похожих проблем
                    </label>
                  )}
                  {draft.precise && (
                    <>
                      <PlaceFields
                        category={draft.category}
                        value={draft.locationFeatures}
                        onChange={(change) =>
                          save({
                            ...draft,
                            locationFeatures:
                              typeof change === "function"
                                ? change(draft.locationFeatures)
                                : change,
                          })
                        }
                        passport={passport}
                        disabled={draft.pending || busy}
                      />
                      <p className="muted">
                        Если не знаете номер подъезда, этаж или другие сведения, снимите отметку —
                        можно описать место своими словами.
                      </p>
                    </>
                  )}
                </>
              )}
              {draft.kind !== "PAYMENT" && !draft.precise && (
                <label>
                  Где заметили? Если знаете
                  <input
                    maxLength={160}
                    value={draft.location}
                    disabled={draft.pending || busy}
                    onChange={(e) => save({ ...draft, location: e.target.value })}
                    placeholder="Можно оставить пустым"
                  />
                </label>
              )}
              {!!draft.attachmentNames?.length && !files.length && (
                <p className="info-note">
                  Черновик сохранён. Повторно выберите файлы: {draft.attachmentNames.join(", ")}.
                  Браузер не восстанавливает вложения после закрытия страницы.
                </p>
              )}
              <label>
                {draft.kind === "PAYMENT"
                  ? "Квитанция — по желанию"
                  : "Фотографии или документ — по желанию"}
                <input
                  type="file"
                  disabled={busy || draft.pending}
                  accept="application/pdf,image/jpeg,image/png"
                  multiple
                  onChange={(e) => {
                    const selected = [...e.target.files];
                    if (selected.length > 5 || selected.some((f) => f.size > 10 * 1024 * 1024)) {
                      setNotice("Выберите до пяти файлов по 10 МБ.");
                      e.target.value = "";
                      setFiles([]);
                    } else {
                      setFiles(selected);
                      save({ ...draft, attachmentNames: selected.map((f) => f.name) });
                      setNotice("");
                    }
                  }}
                />
              </label>
              <p className="muted">
                Можно продолжить без файла. Если понадобятся сведения, объясним, какие и зачем.
              </p>
              <button disabled={busy || !draft.description.trim()}>
                {busy
                  ? "Проверяем…"
                  : draft.kind === "PROBLEM"
                    ? "Найти похожие проблемы"
                    : "Проверить перед отправкой"}
              </button>
              {searchFailed && (
                <button
                  type="button"
                  className="case-secondary"
                  disabled={busy}
                  onClick={() => {
                    setMatches(null);
                    setReview(true);
                    go("/miniapp/similar?withoutSearch=1");
                  }}
                >
                  Продолжить без проверки
                </button>
              )}
            </form>
          )}
        </section>
      )}
      {detail && view !== "events" && (
        <CaseDetail
          key={detail.id}
          item={detail}
          context={context}
          role={role}
          api={api}
          headers={headers}
          onChange={changed}
          residentOnly
          section={route?.section}
          onClose={() => {
            setDetail(null);
            go("/miniapp/cases");
          }}
        />
      )}
      {!detail && !compose && view === "home" && featured && (
        <FeaturedCase
          item={featured}
          api={api}
          role={role}
          total={items.length}
          onShowCases={onShowCases}
          onOpen={(section) => {
            openCase(featured);
            if (section !== "overview") go(`/miniapp/cases/${featured.id}/${section}`);
            requestAnimationFrame(() =>
              document.querySelector(".case-workspace")?.scrollIntoView({ block: "start" }),
            );
          }}
        />
      )}

      {!detail && !compose && view !== "events" && (
        <section
          className={`case-panel case-all-list ${view === "home" && featured ? "case-list-on-home" : ""}`}
        >
          <div className="case-heading">
            <h2>Мои обращения</h2>
            <button type="button" className="case-link" disabled={busy} onClick={refresh}>
              Обновить
            </button>
          </div>
          <label className="case-filter" hidden={view === "home"}>
            Показать
            <select
              value={filter}
              onChange={(e) => {
                setFilter(e.target.value);
                go(`/miniapp/cases?filter=${e.target.value}`, { replace: true });
              }}
            >
              <option value="ALL">Все</option>
              <option value="ACTIVE">Активные</option>
              <option value="WAITING">Нужен мой ответ</option>
              <option value="PAYMENT">По квитанциям</option>
              <option value="PROBLEM">Проблемы дома</option>
              <option value="WARRANTY">Строительные дефекты</option>
            </select>
          </label>
          <div className="case-list">
            {(view === "home" ? visible.slice(0, 3) : visible).map((item) => (
              <button
                type="button"
                className="case-list-item"
                key={item.id}
                onClick={() => openCase(item)}
              >
                <small>
                  № {item.number} · {statuses[item.status]}
                </small>
                <strong>{text(item)}</strong>
                <span>
                  {item.withdrawn
                    ? "Ваше обращение отозвано"
                    : `${item.kind === "PAYMENT" ? "Личный вопрос" : `Участников: ${item.participants || 1}`} · ${item.nextStep}`}
                </span>
                <small>
                  {item.responsibleName} · {date(item.dueAt)}
                </small>
              </button>
            ))}
            {!visible.length && (
              <p className="muted">Здесь появятся ваши вопросы и ответы сотрудников.</p>
            )}
          </div>
        </section>
      )}
    </section>
  );
}

export function StaffCases({ houseId, role, api, headers, section = "all" }) {
  const route = useRoute();
  const [categoryFilter, setCategoryFilter] = useState(route.query.get("category") || "ALL");
  const [query, setQuery] = useState(route.query.get("q") || ""),
    [statusFilter, setStatusFilter] = useState(route.query.get("status") || "ALL"),
    [sort, setSort] = useState(route.query.get("sort") || "recent");
  useEffect(() => {
    setQuery(route.query.get("q") || "");
    setStatusFilter(route.query.get("status") || "ALL");
    setSort(route.query.get("sort") || "recent");
    setCategoryFilter(route.query.get("category") || "ALL");
  }, [route.key]);
  const staffStatus = {
    ...statuses,
    WAITING_RESIDENT: "Ожидается ответ жителя",
    RESULT_PROPOSED: "Ожидается проверка жителем",
  };
  function filters(next) {
    const u = new URL(location.href);
    for (const [k, v] of Object.entries(next))
      v ? u.searchParams.set(k, v) : u.searchParams.delete(k);
    go(u.pathname + u.search, { replace: true });
  }

  const [items, setItems] = useState([]),
    [context, setContext] = useState(null),
    [detail, setDetail] = useState(null),
    [notice, setNotice] = useState("");
  useEffect(() => {
    let active = true;
    setItems([]);
    setDetail(null);
    setContext(null);
    setNotice("");
    if (houseId)
      Promise.all([
        api(role, `/v1/houses/${houseId}/case-queue`),
        api(role, `/v1/houses/${houseId}/case-context`),
      ])
        .then(([list, ctx]) => {
          if (active) {
            setItems(list);
            setContext(ctx);
          }
        })
        .catch((e) => {
          if (active) setNotice(e.message);
        });
    return () => {
      active = false;
    };
  }, [houseId, role]);
  async function refresh() {
    try {
      setItems(await api(role, `/v1/houses/${houseId}/case-queue`));
      setContext(await api(role, `/v1/houses/${houseId}/case-context`));
      setNotice("");
    } catch (e) {
      setNotice(e.message);
    }
  }
  async function permission(staff) {
    try {
      await api(role, `/v1/houses/${houseId}/case-billing-permissions/${staff.id}`, {
        method: "PUT",
        body: JSON.stringify({ enabled: !staff.billingAccess }),
      });
      await refresh();
    } catch (e) {
      setNotice(e.message);
    }
  }
  useEffect(() => {
    if (route.query.get("caseId"))
      setDetail(items.find((i) => i.id === route.query.get("caseId")) || null);
    else setDetail(null);
  }, [items, route.key]);
  const visible = items
    .filter(
      (i) =>
        (categoryFilter === "ALL" || i.category === categoryFilter) &&
        `${i.number} ${i.description} ${i.location || ""}`
          .toLowerCase()
          .includes(query.toLowerCase()) &&
        (statusFilter === "ALL" ||
          (statusFilter === "OVERDUE"
            ? i.status !== "CLOSED" &&
              (i.overdueWorkCount > 0 || (i.dueAt && new Date(i.dueAt) < new Date()))
            : statusFilter === "MINE"
              ? i.responsibleUserId === context?.actorId
              : i.status === statusFilter)),
    )
    .sort((a, b) =>
      sort === "due" ? (a.dueAt || "9999").localeCompare(b.dueAt || "9999") : b.number - a.number,
    );
  if (!houseId) return null;
  return (
    <section className="case-workspace">
      <Notice>{notice}</Notice>
      {["all", "control"].includes(section) && (
        <>
          <ControlPanel
            key={`control:${houseId}:${context?.staff.map((s) => s.billingAccess).join()}`}
            houseId={houseId}
            role={role}
            api={api}
            renderCase={(props) => <CaseDetail {...props} api={api} headers={headers} />}
          />
        </>
      )}
      {["all", "work"].includes(section) && (
        <>
          <ExchangeRegistry
            key={`channels:${houseId}`}
            houseId={houseId}
            role={role}
            api={api}
            canManage={context?.canManagePermissions}
          />
          <HouseOperations houseId={houseId} role={role} api={api} optional />
          <OperationsManagement houseId={houseId} role={role} api={api} />
        </>
      )}
      {["all", "control"].includes(section) && (
        <>
          <BillingQueue
            houseId={houseId}
            role={role}
            api={api}
            refreshKey={context?.staff.find((s) => s.id === context.actorId)?.billingAccess}
            renderCase={(props) => (
              <CaseDetail {...props} context={context} role={role} api={api} headers={headers} />
            )}
          />
          {context?.canManagePermissions && (
            <details className="case-panel">
              <summary>Доступ к личным вопросам по начислениям</summary>
              <p className="muted">
                Это отдельное полномочие. Выдавайте его только сотруднику, который рассматривает
                начисления. Оно не предоставляется вместе с ролью администратора.
              </p>
              {context.staff
                .filter((s) => s.billingAssignable !== false)
                .map((s) => (
                  <div className="case-permission" key={s.id}>
                    <span>{s.name}</span>
                    <button type="button" className="case-secondary" onClick={() => permission(s)}>
                      {s.billingAccess ? "Отозвать доступ" : "Разрешить рассмотрение"}
                    </button>
                  </div>
                ))}
            </details>
          )}
        </>
      )}
      {["all", "queue"].includes(section) && (
        <>
          {detail ? (
            <CaseDetail
              item={detail}
              context={context}
              role={role}
              api={api}
              headers={headers}
              onChange={(next) => {
                setDetail(next);
                setItems((list) => list.map((c) => (c.id === next.id ? next : c)));
              }}
              onClose={() => {
                setDetail(null);
                filters({ caseId: "" });
              }}
            />
          ) : (
            <section className="case-panel">
              <div className="case-heading">
                <div>
                  {section === "all" && <small>ПЕРВИЧНОЕ РАССМОТРЕНИЕ</small>}
                  <h2>
                    {section === "all" ? "Очередь обращений" : `Обращений: ${visible.length}`}
                  </h2>
                </div>
                <button type="button" className="case-link" onClick={refresh}>
                  Обновить
                </button>
              </div>
              <div className="queue-filters">
                <label>
                  Найти обращение
                  <input
                    type="search"
                    value={query}
                    onChange={(e) => {
                      setQuery(e.target.value);
                      filters({ q: e.target.value });
                    }}
                    placeholder="Номер, описание или место"
                  />
                </label>
                <label>
                  Состояние
                  <select
                    value={statusFilter}
                    onChange={(e) => {
                      setStatusFilter(e.target.value);
                      filters({ status: e.target.value });
                    }}
                  >
                    <option value="ALL">Все</option>
                    <option value="MINE">Назначенные мне</option>
                    <option value="OVERDUE">Срок прошёл</option>
                    {Object.entries(staffStatus).map(([id, label]) => (
                      <option value={id} key={id}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Категория
                  <select
                    value={categoryFilter}
                    onChange={(e) => {
                      setCategoryFilter(e.target.value);
                      filters({ category: e.target.value });
                    }}
                  >
                    <option value="ALL">Все</option>
                    {Object.entries(problemCategories).map(([id, label]) => (
                      <option key={id} value={id}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Порядок
                  <select
                    value={sort}
                    onChange={(e) => {
                      setSort(e.target.value);
                      filters({ sort: e.target.value });
                    }}
                  >
                    <option value="recent">Новые</option>
                    <option value="due">По сроку</option>
                  </select>
                </label>
              </div>
              <div className="case-list">
                {visible.map((item) => (
                  <button
                    type="button"
                    className="case-list-item"
                    key={item.id}
                    disabled={!item.fullAccess && !context?.canManagePermissions}
                    onClick={() => {
                      setDetail(item);
                      filters({ caseId: item.id });
                    }}
                  >
                    <small>
                      № {item.number} · {staffStatus[item.status]} · {kinds[item.kind]}
                    </small>
                    <strong>{text(item)}</strong>
                    <small className="queue-kind">
                      {item.kind === "PAYMENT"
                        ? "Личный вопрос"
                        : item.sharedRequestId
                          ? `Общая заявка · участников: ${item.participants || 1}`
                          : "Работа по дому"}
                    </small>
                    <span>{item.nextStep}</span>
                    {item.overdueWorkCount > 0 && (
                      <strong className="workflow-overdue">
                        Прошёл срок заданий: {item.overdueWorkCount}
                      </strong>
                    )}
                  </button>
                ))}
                {!visible.length && <p className="muted">Новых обращений пока нет.</p>}
              </div>
            </section>
          )}
        </>
      )}
    </section>
  );
}
