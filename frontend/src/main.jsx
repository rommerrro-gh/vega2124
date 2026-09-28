import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { MaxUI, Panel, Button as MaxButton, CellSimple } from "@maxhub/max-ui";
import "@maxhub/max-ui/dist/styles.css";
import "./theme.css";

function Button({ mode = "primary", className = "", ...props }) {
  return <MaxButton mode={mode} className={`app-button-${mode} ${className}`.trim()} {...props} />;
}

const statusLabels = {
  DRAFT: "Ожидает диспетчера", OPEN: "Принята", ASSIGNED: "Назначена",
  IN_PROGRESS: "В работе", VERIFICATION_72H: "Ожидает вашего подтверждения",
  CLOSED_CONFIRMED: "Закрыта после подтверждения",
  CLOSED_UNCONFIRMED: "Закрыта по истечении срока", REOPENED: "Открыта повторно",
  REVIEW_REQUIRED: "Требует решения диспетчера", WITHDRAWN: "Отозвана",
};
const categoryLabels = {
  LIGHTING: "Освещение", WATER: "Вода", HEATING: "Отопление",
  ELEVATOR: "Лифт", OTHER: "Другое",
};
const transitions = {
  DRAFT: ["OPEN", "Принять заявку"], OPEN: ["ASSIGNED", "Назначить себе"],
  ASSIGNED: ["IN_PROGRESS", "Начать работу"], IN_PROGRESS: ["RESOLVED", "Отметить выполненной"],
  REOPENED: ["ASSIGNED", "Повторно назначить себе"],
  REVIEW_REQUIRED: ["WITHDRAWN", "Закрыть отозванную заявку"],
};
const editableIssueStatuses = ["DRAFT", "OPEN", "ASSIGNED", "IN_PROGRESS", "REOPENED"];
const issueStage = { DRAFT: 0, OPEN: 1, ASSIGNED: 2, REOPENED: 2, IN_PROGRESS: 3 };
const passportLabels = {
  management_company: "Управляющая организация",
  building_year: "Год постройки",
  emergency_contact: "Аварийный контакт",
};

function passportValue(value) {
  if (value == null) return "Не указано";
  if (typeof value === "string" || typeof value === "number") return String(value);
  if (Array.isArray(value)) return value.map(passportValue).join(", ");
  return Object.entries(value).map(([key, item]) => `${key}: ${passportValue(item)}`).join(" · ");
}

function passportDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString("ru-RU");
}

function moscowToday() {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Moscow", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
  const value = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day}`;
}

function issueOverdue(issue) {
  return issue?.plannedDate && issue.plannedDate < moscowToday() && editableIssueStatuses.includes(issue.status);
}

function safeSourceUrl(value) {
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) ? url.href : null;
  } catch { return null; }
}

function authHeaders(role) {
  const initData = window.WebApp?.initData;
  return initData ? { "X-Max-Init-Data": initData } : { "X-Demo-Session": role };
}

async function api(role, path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { ...authHeaders(role), ...(options.body && !(options.body instanceof FormData)
      ? { "Content-Type": "application/json" } : {}) },
  });
  if (!response.ok) {
    if (response.status === 401) throw new Error("Нет доступа. Откройте приложение в MAX. Для локального просмотра запустите сервер с профилем demo.");
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `Ошибка ${response.status}`);
  }
  if (response.status === 204) return null;
  const body = await response.text();
  return body ? JSON.parse(body) : null;
}

function navigate(path) {
  const initData = window.WebApp?.initData;
  const launchData = window.location.hash || (initData ? `#WebAppData=${encodeURIComponent(initData)}` : "");
  const pathname = new URL(path, location.origin).pathname;
  if (["/miniapp/index.html", "/dispatcher/index.html", "/admin/index.html", "/uk/index.html", "/system/index.html"].includes(pathname)) {
    localStorage.setItem("pulse-last-mode", pathname);
  }
  window.location.assign(`${path}${launchData}`);
}

async function downloadAttachment(role, attachment, onError) {
  try {
    const response = await fetch(`/v1/attachments/${encodeURIComponent(attachment.id)}`, { headers: authHeaders(role) });
    if (!response.ok) throw new Error("Не удалось открыть вложение");
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = url;
    link.download = attachment.id;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  } catch (error) { onError(error.message); }
}

function Brand({ role }) {
  return <header className="topbar">
    <div className="brand"><span className="brand-mark" aria-hidden="true"><i /></span><span>Пульс дома</span></div>
    <span className="max-chip"><span className="max-dot" /> {role} · MAX</span>
  </header>;
}

const demoPages = [
  ["Житель", "/miniapp/index.html"],
  ["Диспетчер", "/dispatcher/index.html"],
  ["Администратор дома", "/admin/index.html"],
  ["Администратор УК", "/uk/index.html"],
  ["Системный администратор", "/system/index.html"],
];

function DemoRoleSwitcher() {
  const [enabled, setEnabled] = useState(false);
  useEffect(() => { api("true", "/v1/access/config").then(config => setEnabled(config.demoMode === true)).catch(() => {}); }, []);
  if (!enabled) return null;
  return <nav className="demo-role-switcher" aria-label="Демонстрационные роли">
    <strong>Демонстрационный стенд</strong><span>Выберите роль для проверки:</span>
    <div>{demoPages.map(([label, path]) => <button key={path} type="button" className={location.pathname === path ? "active" : ""} aria-current={location.pathname === path ? "page" : undefined} onClick={() => navigate(path)}>{label}</button>)}</div>
  </nav>;
}

function Hero({ dispatcher = false }) {
  return <section className="hero" aria-labelledby="page-title">
    <div className="hero-copy"><div className="hero-kicker"><span className="live-dot" /> {dispatcher ? "Кабинет диспетчера" : "Сервис вашего дома"}</div>
      <h1 id="page-title">{dispatcher ? <>Все обращения<br />под контролем</> : <>Дом становится<br />лучше с вас</>}</h1>
      <p>{dispatcher ? "Принимайте заявки жителей и ведите их до решения в одном месте." : "Расскажите о проблеме. Мы проверим похожие обращения и поможем отправить заявку."}</p>
    </div>
    <div className="hero-art" aria-hidden="true"><div className="hero-orbit orbit-one" /><div className="hero-orbit orbit-two" /><div className="hero-core"><span>⌁</span></div><div className="hero-art-label">В ритме вашего дома</div></div>
  </section>;
}

function Card({ children, className = "" }) {
  return <section className={`card ${className}`}><Panel mode="secondary" className="card-panel">{children}</Panel></section>;
}

function DisclosureCard({ title, summary, children, className = "" }) {
  return <Card className={`disclosure-card ${className}`}><details>
    <summary><span><strong>{title}</strong><small>{summary}</small></span><span className="disclosure-arrow" aria-hidden="true" /></summary>
    <div className="disclosure-content">{children}</div>
  </details></Card>;
}

function SectionHeading({ number, title, subtitle }) {
  return <div className="section-heading">{number && <span className="section-number">{number}</span>}<div><h2>{title}</h2><p>{subtitle}</p></div></div>;
}

function countLabel(count, one, few, many) {
  const mod100 = count % 100;
  const mod10 = count % 10;
  return `${count} ${mod100 >= 11 && mod100 <= 14 ? many : mod10 === 1 ? one : mod10 >= 2 && mod10 <= 4 ? few : many}`;
}

function Field({ id, label, children }) {
  return <div className="field"><label htmlFor={id}>{label}</label>{children}</div>;
}

function Notice({ message }) {
  return message ? <div className={`notice${message.startsWith("Нет доступа.") ? " notice-error" : ""}`} role="alert">{message}</div> : null;
}

function QuickLink({ dispatcher = false }) {
  return <div className="quick-link">
    <span className="quick-link-icon" aria-hidden="true">{dispatcher ? "↙" : "↗"}</span>
    <div><strong>{dispatcher ? "Мини-приложение жителя" : "Кабинет диспетчера"}</strong><small>{dispatcher ? "Вернуться к своим заявкам" : "Очередь заявок вашего дома"}</small></div>
    <Button mode="tertiary" type="button" onClick={() => navigate(dispatcher ? "/miniapp/index.html" : "/dispatcher/index.html")}>Открыть</Button>
  </div>;
}

function AdminLink() {
  return <div className="quick-link">
    <span className="quick-link-icon" aria-hidden="true">⌂</span>
    <div><strong>Кабинет администратора дома</strong><small>Контакты и приглашения жителей</small></div>
    <Button mode="tertiary" type="button" onClick={() => navigate("/admin/index.html")}>Открыть</Button>
  </div>;
}

function InvitationToken({ token, role = "admin" }) {
  const [username, setUsername] = useState("");
  useEffect(() => { api(role, "/v1/access/config").then(config => setUsername(config.botUsername || "")).catch(() => {}); }, []);
  if (!token) return null;
  const link = username ? `https://max.ru/${username}?startapp=${encodeURIComponent(token)}` : "";
  return <div className="invite-token" role="status"><strong>Приглашение создано</strong>
    {link ? <><code>{link}</code><Button mode="secondary" type="button" onClick={() => navigator.clipboard.writeText(link)}>Скопировать ссылку</Button></>
      : <><code>{token}</code><Button mode="secondary" type="button" onClick={() => navigator.clipboard.writeText(token)}>Скопировать токен</Button><p>Укажите MAX_BOT_USERNAME на стенде, чтобы здесь появилась готовая ссылка. Пока добавьте токен к ссылке бота как параметр startapp.</p></>}
    <p>Сохраните ссылку сейчас: из соображений безопасности она показывается один раз. Если потеряли её, отзовите приглашение и создайте новое.</p></div>;
}

function AttachmentList({ attachments, role, onError }) {
  if (!attachments?.length) return null;
  return <div className="item-list">{attachments.map(file => <button className="attachment-link" type="button" key={file.id} onClick={() => downloadAttachment(role, file, onError)}>Открыть вложение · {file.mime}</button>)}</div>;
}

function IssueDetails({ issue, role, onError }) {
  return <>
    <div className="detail-lines"><p className="muted">{issue.address}</p>{issue.location && <p className="muted">Место: {issue.location}</p>}{issue.occurredAt && <p className="muted">Замечено: {new Date(issue.occurredAt).toLocaleString("ru-RU")}</p>}</div>
    <p className="detail-description">{issue.description || "Активных обращений нет."}</p>
    {issue.plannedDate && <div className={`planned-date ${issueOverdue(issue) ? "planned-date-overdue" : ""}`}>
      <strong>{issueOverdue(issue) ? "Плановый срок прошёл" : "Планируем выполнить до"} {passportDate(`${issue.plannedDate}T12:00:00`)}</strong>
      <small>Дата указана по московскому времени</small>
    </div>}
    {!!issue.plannedDateHistory?.length && <details className="date-history"><summary>История плановых сроков</summary><ol>{issue.plannedDateHistory.map((change, index) =>
      <li key={`${change.changedAt}-${index}`}><strong>{change.previousDate ? `С ${passportDate(`${change.previousDate}T12:00:00`)} на ${passportDate(`${change.newDate}T12:00:00`)}` : `Установлен срок ${passportDate(`${change.newDate}T12:00:00`)}`}</strong>
        <span>{passportDate(change.changedAt)}{change.previousDateMissed ? " · предыдущий срок прошёл" : ""}</span>
        {change.reason && <p>Причина: {change.reason}</p>}</li>)}</ol></details>}
    <AttachmentList attachments={issue.attachments} role={role} onError={onError} />
    <div className="result-meta"><span>{statusLabels[issue.status] || issue.status}</span><span>Участников: {issue.participants}</span></div>
  </>;
}

function HousePassport({ passport, loading, error }) {
  const summary = loading ? "Загружаем сведения…" : error ? "Не удалось загрузить сведения" : passport ? `${countLabel(passport.fields.length, "поле", "поля", "полей")} · ${countLabel(passport.contacts?.length || 0, "контакт", "контакта", "контактов")}` : "Сведения о доме";
  return <DisclosureCard className="passport-card" title="Паспорт дома" summary={summary}>
    <p className="muted">Сведения о доме с источником и датой каждого поля</p>
    {loading ? <p className="muted">Загружаем сведения о доме…</p> : error ? <p className="notice" role="alert">{error}</p> : passport && <>
      <h3 className="passport-address">{passport.address}</h3>
      {passport.fields.length ? <div className="passport-fields">{passport.fields.map(field => {
        const sourceUrl = safeSourceUrl(field.sourceUrl);
        return <div className="passport-field" key={field.key}>
          <div className="passport-field-label">{passportLabels[field.key] || field.key.replaceAll("_", " ")}</div>
          <div className="passport-field-value">{passportValue(field.value)}</div>
          <div className="passport-field-source">Источник: {sourceUrl ? <a href={sourceUrl} target="_blank" rel="noopener noreferrer">{field.source}</a> : field.source} · получено {passportDate(field.fetchedAt)}{field.validAt && ` · актуально на ${passportDate(field.validAt)}`}</div>
        </div>;
      })}</div> : <p className="muted passport-empty">Поля паспорта пока не заполнены. Данные появятся здесь вместе с источником и датой получения.</p>}
      {!!passport.contacts?.length && <div className="passport-contacts"><h4>Контакты дома</h4><div className="passport-fields">{passport.contacts.map(contact => <div className="passport-field" key={contact.id}>
        <div className="passport-field-label">{contact.type === "EMERGENCY" ? "Аварийный контакт" : "Местный контакт"}</div>
        <div className="passport-field-value">{contact.title}</div>
        <a className="passport-phone" href={`tel:${contact.phone.replace(/[^+0-9]/g, "")}`}>{contact.phone}</a>
        {contact.details && <p className="passport-details">{contact.details}</p>}
        <div className="passport-field-source">Источник: администратор дома · обновлено {passportDate(contact.updatedAt)}</div>
      </div>)}</div></div>}
    </>}
  </DisclosureCard>;
}

function PollCard({ poll, onVote, busy }) {
  const [optionId, setOptionId] = useState("");
  const totalVotes = poll.resultsVisible ? poll.options.reduce((total, option) => total + option.votes, 0) : 0;
  return <div className="poll-card">
    <div className="poll-heading"><strong>{poll.question}</strong><span>{poll.closed ? "Завершён" : `До ${passportDate(poll.closesAt)}`}</span></div>
    <p className="hint">Предварительный опрос жителей · не является официальным голосованием собственников</p>
    <div className="poll-options">{poll.options.map(option => <label key={option.id} className={`poll-option ${poll.myOptionId === option.id ? "poll-option-selected" : ""}`}>
      {!poll.myOptionId && !poll.closed && onVote && <input type="radio" name={`poll-${poll.id}`} value={option.id} checked={optionId === option.id} onChange={() => setOptionId(option.id)} />}
      <span className="poll-option-content"><span className="poll-option-top"><span>{option.label}{poll.myOptionId === option.id ? " · ваш выбор" : ""}</span>
      {poll.resultsVisible && <strong>{totalVotes ? Math.round(option.votes * 100 / totalVotes) : 0}%</strong>}</span>
      {poll.resultsVisible && <span className="poll-progress" role="img" aria-label={`${option.votes} голосов из ${totalVotes}`}><span style={{ width: `${totalVotes ? option.votes * 100 / totalVotes : 0}%` }} /></span>}</span>
    </label>)}</div>
    {poll.resultsVisible && <p className="poll-total">Проголосовали: {totalVotes}</p>}
    {!poll.resultsVisible && <p className="hint">Итоги появятся после закрытия опроса.</p>}
    {onVote && !poll.myOptionId && !poll.closed && <Button mode="primary" type="button" disabled={!optionId || busy} onClick={() => onVote(poll.id, optionId)}>Проголосовать</Button>}
  </div>;
}

function Resident() {
  const formRef = useRef(null);
  const [houses, setHouses] = useState([]);
  const [selectedHouseId, setSelectedHouseId] = useState("");
  const [passport, setPassport] = useState(null);
  const [passportLoading, setPassportLoading] = useState(false);
  const [passportError, setPassportError] = useState("");
  const [polls, setPolls] = useState([]);
  const [pollBusy, setPollBusy] = useState(false);
  const [issues, setIssues] = useState([]);
  const [dispatcher, setDispatcher] = useState(false);
  const [access, setAccess] = useState([]);
  const [notice, setNotice] = useState("");
  const [step, setStep] = useState("form");
  const [showReportForm, setShowReportForm] = useState(false);
  const [reportId, setReportId] = useState(null);
  const [reportData, setReportData] = useState(null);
  const [uploaded, setUploaded] = useState(0);
  const [candidates, setCandidates] = useState([]);
  const [issue, setIssue] = useState(null);
  const [issueMessage, setIssueMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [verificationComment, setVerificationComment] = useState("");
  const [withdrawReason, setWithdrawReason] = useState("");
  const demoSession = new URLSearchParams(location.search).get("demoSession") === "admin" ? "admin" : "true";
  const request = (path, options) => api(demoSession, path, options);
  const refreshIssues = async () => setIssues(await request("/v1/me/issues"));
  const fail = error => setNotice(error.message || String(error));

  useEffect(() => {
    async function load() {
      const [myHouses, myIssues, dispatcherHouses, roles, selected] = await Promise.all([
        request("/v1/me/houses"), request("/v1/me/issues"), request("/v1/me/dispatcher-houses"),
        request("/v1/access/me"), request("/v1/me/active-house"),
      ]);
      setHouses(myHouses); setIssues(myIssues); setDispatcher(dispatcherHouses.length > 0); setAccess(roles);
      if (myHouses.length) setSelectedHouseId(myHouses.find(house => house.id === selected.houseId)?.id || myHouses[0].id);
      if (!roles.length) setNotice("Профиль создан. Чтобы получить доступ, откройте приглашение от администратора.");
    }
    load().catch(fail);
  }, []);

  useEffect(() => {
    if (!selectedHouseId) return;
    let active = true;
    const membership = houses.find(house => house.id === selectedHouseId)?.memberships || [];
    const canVote = membership.some(access => access.role === "RESIDENT" && access.verificationStatus === "VERIFIED");
    setPassport(null); setPolls([]); setPassportError(""); setPassportLoading(true);
    Promise.all([request(`/v1/houses/${encodeURIComponent(selectedHouseId)}`),
      canVote ? request(`/v1/houses/${encodeURIComponent(selectedHouseId)}/polls`) : Promise.resolve([])])
      .then(([data, housePolls]) => { if (active) { setPassport(data); setPolls(housePolls); } })
      .catch(error => { if (active) setPassportError(error.message || String(error)); })
      .finally(() => { if (active) setPassportLoading(false); });
    return () => { active = false; };
  }, [selectedHouseId, houses]);

  async function voteInPoll(pollId, optionId) {
    setPollBusy(true); setNotice("");
    try {
      await request(`/v1/houses/${encodeURIComponent(selectedHouseId)}/polls/${encodeURIComponent(pollId)}/votes`,
        { method: "POST", body: JSON.stringify({ optionId }) });
      setPolls(await request(`/v1/houses/${encodeURIComponent(selectedHouseId)}/polls`));
    } catch (error) { fail(error); } finally { setPollBusy(false); }
  }

  async function refreshPolls() {
    if (!selectedHouseId) return;
    setPollBusy(true); setNotice("");
    try { setPolls(await request(`/v1/houses/${encodeURIComponent(selectedHouseId)}/polls`)); }
    catch (error) { fail(error); } finally { setPollBusy(false); }
  }

  function showIssue(nextIssue, message = "") {
    setIssue(nextIssue); setIssueMessage(message); setVerificationComment(""); setWithdrawReason(""); setStep("result"); setNotice("");
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function submitReport(event) {
    event.preventDefault(); setBusy(true); setNotice("");
    try {
      const form = event.currentTarget;
      const files = [...form.elements.attachments.files];
      if (files.length > 5 || files.some(file => file.size > 10 * 1024 * 1024)) throw new Error("Можно прикрепить до 5 файлов по 10 МБ");
      let report = reportData;
      let id = reportId;
      if (!id) {
        report = await request("/v1/reports", { method: "POST", body: JSON.stringify({
          houseId: selectedHouseId, category: form.elements.category.value,
          location: form.elements.location.value.trim(),
          occurredAt: new Date(form.elements["occurred-at"].value).toISOString(),
          text: form.elements.description.value.trim(),
        }) });
        id = report.reportId;
        setReportId(id); setReportData(report);
      }
      for (let index = uploaded; index < files.length; index++) {
        const body = new FormData(); body.append("file", files[index]);
        await request(`/v1/reports/${encodeURIComponent(id)}/attachments`, { method: "POST", body });
        setUploaded(index + 1);
      }
      const details = await Promise.all(report.candidates.map(async candidate => ({
        ...candidate, issue: await request(`/v1/issues/${encodeURIComponent(candidate.issueId)}`),
      })));
      setCandidates(details); setStep("decision");
      window.scrollTo({ top: 0, behavior: "smooth" });
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  }

  async function decide(path) {
    setBusy(true); setNotice("");
    try {
      const result = await request(path, { method: "POST", body: JSON.stringify({ reportId }) });
      showIssue(result, path === "/v1/issues" ? "Новая заявка сохранена" : "Вы присоединились к заявке");
      await refreshIssues();
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  }

  async function verify(confirmed) {
    if (!confirmed && !verificationComment.trim()) { setNotice("Опишите, что осталось неисправным."); return; }
    setBusy(true); setNotice("");
    try {
      const result = await request(`/v1/issues/${encodeURIComponent(issue.id)}/verify`, {
        method: "POST", body: JSON.stringify({ confirmed, comment: verificationComment.trim() }),
      });
      showIssue(result, "Ответ сохранён");
      await refreshIssues();
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  }

  async function withdrawReport(id) {
    if (!withdrawReason.trim()) { setNotice("Укажите причину отзыва обращения."); return; }
    setBusy(true); setNotice("");
    try {
      await request(`/v1/reports/${encodeURIComponent(id)}/withdraw`, {
        method: "POST", body: JSON.stringify({ reason: withdrawReason.trim() }),
      });
      await refreshIssues();
      again(); setShowReportForm(false); setNotice("Обращение отозвано.");
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  }

  function again() {
    setReportId(null); setReportData(null); setUploaded(0); setCandidates([]); setIssue(null); setIssueMessage("");
    setStep("form"); setShowReportForm(true); setNotice("");
    localNow.current = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    requestAnimationFrame(() => {
      formRef.current?.reset();
      const date = formRef.current?.elements["occurred-at"];
      if (date) date.value = localNow.current;
    });
  }

  const localNow = useRef(new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16));
  const selectedHouse = houses.find(house => house.id === selectedHouseId);
  const canReport = selectedHouse?.memberships?.some(item => item.role === "RESIDENT" && item.verificationStatus === "VERIFIED");
  const houseIssues = issues.filter(item => item.houseId === selectedHouseId);
  const awaitingVerification = houseIssues.filter(item => item.status === "VERIFICATION_72H");
  const openPolls = polls.filter(poll => !poll.closed && !poll.myOptionId);
  return <main className="shell">
    <Brand role="Жителю" /><div className="resident-hero"><Hero /></div><Notice message={notice} />
    {selectedHouse && <div className="house-switcher"><span className="house-switcher-icon" aria-hidden="true">⌂</span><div className="house-switcher-copy"><span>Ваш дом</span>{houses.length > 1 ? <select aria-label="Выбранный дом" value={selectedHouseId} disabled={step !== "form" || !!reportId} onChange={event => { setSelectedHouseId(event.target.value); request("/v1/me/active-house", { method: "PUT", body: JSON.stringify({ houseId: event.target.value }) }).catch(fail); }}>{houses.map(house => <option key={house.id} value={house.id}>{house.address}</option>)}</select> : <strong>{selectedHouse.address}</strong>}</div></div>}
    {!!awaitingVerification.length && step === "form" && <div className="priority-notice"><div><strong>Подтвердите выполнение работ</strong><p>{countLabel(awaitingVerification.length, "заявка ждёт", "заявки ждут", "заявок ждут")} вашего ответа.</p></div><Button mode="secondary" type="button" onClick={() => showIssue(awaitingVerification[0])}>Открыть</Button></div>}
    {selectedHouse && canReport && step === "form" && !showReportForm && <div className="start-action"><div><strong>Заметили проблему?</strong><p>Проверьте похожие заявки и сообщите о новой.</p></div><Button mode="primary" type="button" onClick={() => setShowReportForm(true)}>Сообщить о проблеме <span aria-hidden="true">→</span></Button></div>}
    <div className="content-grid">
      {step === "form" && showReportForm && canReport && <Card className="form-card">
        <div className="form-title"><SectionHeading title="Сообщить о проблеме" subtitle={`Дом: ${selectedHouse?.address || "не выбран"}`} />{!reportId && <button className="form-close" type="button" onClick={() => setShowReportForm(false)}>Отменить</button>}</div>
        <form ref={formRef} onSubmit={submitReport}>
          <Field id="category" label="Категория"><select id="category" name="category" required disabled={!!reportId} defaultValue=""><option value="">Выберите категорию</option><option value="LIGHTING">Освещение</option><option value="WATER">Вода</option><option value="HEATING">Отопление</option><option value="ELEVATOR">Лифт</option><option value="OTHER">Другое</option></select></Field>
          <Field id="location" label="Где именно?"><input id="location" name="location" maxLength="160" placeholder="Например, подъезд 1, этаж 2" required disabled={!!reportId} /></Field>
          <Field id="occurred-at" label="Когда заметили?"><input id="occurred-at" name="occurred-at" type="datetime-local" defaultValue={localNow.current} required disabled={!!reportId} /></Field>
          <Field id="description" label="Что произошло?"><textarea id="description" name="description" maxLength="4000" minLength="8" placeholder="Опишите, что случилось и как это влияет на жителей" required disabled={!!reportId} /></Field>
          <Field id="attachments" label="Фото, видео или документ"><input id="attachments" name="attachments" type="file" accept="image/jpeg,image/png,video/mp4,application/pdf" multiple /></Field>
          <p className="hint">До 5 файлов по 10 МБ. Содержимое файлов не анализируется.</p>
          <Button mode="primary" className="brand-button" type="submit" stretched disabled={busy || !selectedHouseId}>Проверить похожие заявки <span aria-hidden="true">→</span></Button>
        </form>
      </Card>}
      {step === "form" && selectedHouse && <DisclosureCard className="issues-card" title="Мои заявки" summary={houseIssues.length ? `${countLabel(houseIssues.length, "заявка", "заявки", "заявок")} по выбранному дому` : "Пока нет заявок"}>
        <div className="item-list">{houseIssues.length ? houseIssues.map(item => <CellSimple key={item.id} className="issue-cell" title={item.location || item.address} subtitle={item.description} overline={statusLabels[item.status] || item.status} showChevron onClick={() => showIssue(item)} />) : <p className="muted empty">Заявок пока нет.</p>}</div>
        <Button mode="tertiary" className="quiet-action" type="button" stretched onClick={() => refreshIssues().catch(fail)}>Обновить список</Button>
      </DisclosureCard>}
      {step === "form" && canReport && <DisclosureCard title="Опросы дома" summary={openPolls.length ? `${countLabel(openPolls.length, "опрос ждёт", "опроса ждут", "опросов ждут")} вашего ответа` : polls.length ? countLabel(polls.length, "опрос", "опроса", "опросов") : "Опросов пока нет"}><div className="poll-list">{polls.map(poll => <PollCard key={poll.id} poll={poll} onVote={voteInPoll} busy={pollBusy} />)}{!polls.length && <p className="muted">В выбранном доме пока нет опросов.</p>}<Button mode="secondary" type="button" disabled={pollBusy} onClick={refreshPolls}>Обновить опросы</Button></div></DisclosureCard>}
      {step === "form" && selectedHouse && <HousePassport passport={passport} loading={passportLoading} error={passportError} />}
      {step === "decision" && <Card className="flow-card">
        <SectionHeading title="Похожие проблемы" subtitle="Выберите существующую заявку или создайте новую" />
        <div className="item-list">{candidates.length ? candidates.map(({ issue: item, score }) => <div className="candidate" key={item.id}><span className="score">{Math.round(score * 100)}% совпадение</span><strong>{item.description || item.category || "Проблема дома"}</strong><p>{item.address} · {item.participants} участников</p><button type="button" disabled={busy} onClick={() => decide(`/v1/issues/${encodeURIComponent(item.id)}/join`)}>Присоединиться</button></div>) : <p className="muted empty">Похожих активных заявок не найдено.</p>}</div>
        <Button mode="primary" className="brand-button" type="button" stretched disabled={busy} onClick={() => decide("/v1/issues")}>Создать новую заявку <span aria-hidden="true">→</span></Button>
        <div className="secondary-operation"><Field id="withdraw-pending-reason" label="Причина отзыва обращения"><textarea id="withdraw-pending-reason" maxLength="1000" value={withdrawReason} onChange={event => setWithdrawReason(event.target.value)} placeholder="Например, отправлено по ошибке" /></Field><Button mode="tertiary" type="button" stretched disabled={busy || !withdrawReason.trim()} onClick={() => withdrawReport(reportId)}>Отозвать обращение</Button></div>
      </Card>}
      {step === "result" && issue && <Card className="flow-card">
        {issueMessage && <div className="success-mark" aria-hidden="true">✓</div>}
        <h2>{issueMessage || "Заявка"}</h2>
        <IssueDetails issue={issue} role="true" onError={setNotice} />
        {editableIssueStatuses.includes(issue.status) && !!issue.reports?.length && <div className="secondary-operation"><h3>Мои обращения в заявке</h3><Field id="withdraw-reason" label="Причина отзыва"><textarea id="withdraw-reason" maxLength="1000" value={withdrawReason} onChange={event => setWithdrawReason(event.target.value)} placeholder="Например, проблема уже решена" /></Field>{issue.reports.map(report => <div className="linked-report" key={report.id}><p>{report.description}</p><Button mode="tertiary" type="button" disabled={busy || !withdrawReason.trim()} onClick={() => withdrawReport(report.id)}>Отозвать обращение</Button></div>)}</div>}
        {issue.status === "VERIFICATION_72H" && <div className="verification"><h3>Работа выполнена?</h3><p className="muted">{issue.verificationDueAt ? `Подтвердите до ${new Date(issue.verificationDueAt).toLocaleString("ru-RU")}` : ""}</p><Field id="verification-comment" label="Комментарий, если проблема осталась"><textarea id="verification-comment" maxLength="2000" placeholder="Что ещё не исправлено?" value={verificationComment} onChange={event => setVerificationComment(event.target.value)} /></Field><Button mode="primary" className="brand-button" type="button" stretched disabled={busy} onClick={() => verify(true)}>Да, проблема решена</Button><Button mode="secondary" type="button" stretched disabled={busy} onClick={() => verify(false)}>Нет, проблема осталась</Button></div>}
        <Button mode="secondary" className="bottom-action" type="button" stretched onClick={again}>Сообщить о другой проблеме</Button>
        <Button mode="tertiary" className="quiet-action" type="button" stretched onClick={() => { again(); setShowReportForm(false); }}>На главную</Button>
      </Card>}
    </div>
    {dispatcher && <QuickLink />}
    {houses.some(house => house.memberships?.some(access => access.role === "HOUSE_ADMIN" && access.verificationStatus === "VERIFIED")) && <AdminLink />}
    {access.some(item => item.role === "UK_ADMIN") && <div className="quick-link"><strong>Кабинет УК</strong><Button mode="tertiary" onClick={() => navigate("/uk/index.html")}>Открыть</Button></div>}
    {access.some(item => item.role === "SYSTEM_ADMIN") && <div className="quick-link"><strong>Управление системой</strong><Button mode="tertiary" onClick={() => navigate("/system/index.html")}>Открыть</Button></div>}
    <footer>Пульс дома <span>·</span> Сделаем дом лучше вместе</footer>
  </main>;
}

function Admin() {
  const [houses, setHouses] = useState([]);
  const [houseId, setHouseId] = useState("");
  const [currentUserId, setCurrentUserId] = useState(null);
  const [residents, setResidents] = useState([]);
  const [contacts, setContacts] = useState([]);
  const [invitations, setInvitations] = useState([]);
  const [polls, setPolls] = useState([]);
  const [pollQuestion, setPollQuestion] = useState("");
  const [pollOptions, setPollOptions] = useState(["", ""]);
  const [pollClose, setPollClose] = useState(() => new Date(Date.now() + 7 * 86400000 - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16));
  const [hidePollResults, setHidePollResults] = useState(false);
  const [createdToken, setCreatedToken] = useState("");
  const [editingId, setEditingId] = useState("");
  const [contactType, setContactType] = useState("EMERGENCY");
  const [title, setTitle] = useState("");
  const [phone, setPhone] = useState("");
  const [details, setDetails] = useState("");
  const [days, setDays] = useState(7);
  const [activationLimit, setActivationLimit] = useState(10);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const request = (path, options) => api("admin", path, options);
  const fail = error => setNotice(error.message || String(error));
  const base = `/v1/houses/${encodeURIComponent(houseId)}`;

  useEffect(() => {
    Promise.all([request("/v1/me/houses"), request("/v1/access/me")]).then(([list, access]) => {
      setCurrentUserId(access[0]?.userId || null);
      const allowed = list.filter(house => house.memberships?.some(access => access.role === "HOUSE_ADMIN" && access.verificationStatus === "VERIFIED"));
      setHouses(allowed); if (allowed.length) setHouseId(allowed[0].id);
      else setNotice("У вас нет подтверждённой роли администратора дома.");
    }).catch(fail);
  }, []);
  useEffect(() => {
    if (!houseId) return;
    setCreatedToken(""); setNotice("");
    Promise.all([request(`${base}/contacts`), request("/v1/access/invitations"), request(`${base}/polls`), request(`/v1/access/houses/${houseId}/residents`)])
      .then(([nextContacts, nextInvitations, nextPolls, nextResidents]) => { setContacts(nextContacts); setInvitations(nextInvitations); setPolls(nextPolls); setResidents(nextResidents); })
      .catch(fail);
  }, [houseId]);

  function resetContact() { setEditingId(""); setContactType("EMERGENCY"); setTitle(""); setPhone(""); setDetails(""); }
  async function saveContact(event) {
    event.preventDefault(); setBusy(true); setNotice("");
    try {
      const path = editingId ? `${base}/contacts/${encodeURIComponent(editingId)}` : `${base}/contacts`;
      await request(path, { method: editingId ? "PUT" : "POST", body: JSON.stringify({ type: contactType, title: title.trim(), phone: phone.trim(), details: details.trim() }) });
      setContacts(await request(`${base}/contacts`)); resetContact();
    } catch (error) { fail(error); } finally { setBusy(false); }
  }
  async function removeContact(id) {
    if (!window.confirm("Удалить этот контакт?")) return;
    setBusy(true); setNotice("");
    try { await request(`${base}/contacts/${encodeURIComponent(id)}`, { method: "DELETE" }); setContacts(await request(`${base}/contacts`)); if (editingId === id) resetContact(); }
    catch (error) { fail(error); } finally { setBusy(false); }
  }
  async function createInvitation(event) {
    event.preventDefault(); setBusy(true); setNotice(""); setCreatedToken("");
    try {
      const result = await request("/v1/access/invitations", { method: "POST", body: JSON.stringify({ role: "RESIDENT", houseIds: [houseId], days: Number(days), activationLimit: Number(activationLimit) }) });
      setCreatedToken(result.token); setInvitations(await request("/v1/access/invitations"));
    } catch (error) { fail(error); } finally { setBusy(false); }
  }
  async function revokeInvitation(id) {
    setBusy(true); setNotice("");
    try { await request(`/v1/access/invitations/${encodeURIComponent(id)}/revoke`, { method: "POST" }); setInvitations(await request("/v1/access/invitations")); }
    catch (error) { fail(error); } finally { setBusy(false); }
  }
  async function revokeResident(userId) {
    setBusy(true); setNotice("");
    try { await request("/v1/access/assignments/revoke", { method: "POST", body: JSON.stringify({ role: "RESIDENT", userId, houseId }) }); setResidents(await request(`/v1/access/houses/${houseId}/residents`)); }
    catch (error) { fail(error); } finally { setBusy(false); }
  }
  async function createPoll(event) {
    event.preventDefault(); setBusy(true); setNotice("");
    try {
      const options = pollOptions.map(value => value.trim());
      await request(`${base}/polls`, { method: "POST", body: JSON.stringify({
        question: pollQuestion.trim(), options, closesAt: new Date(pollClose).toISOString(),
        resultsHiddenUntilClose: hidePollResults,
      }) });
      setPolls(await request(`${base}/polls`)); setPollQuestion(""); setPollOptions(["", ""]);
    } catch (error) { fail(error); } finally { setBusy(false); }
  }

  return <main className="shell">
    <Brand role="Администратору" />
    <section className="hero"><div className="hero-copy"><div className="hero-kicker"><span className="live-dot" /> Кабинет дома</div><h1>Информация<br />для жителей</h1><p>Поддерживайте контакты дома и приглашайте новых участников.</p></div></section>
    <Notice message={notice} />
    <div className="quick-link"><span className="quick-link-icon" aria-hidden="true">↙</span><div><strong>Мини-приложение жителя</strong><small>Посмотреть паспорт дома</small></div><Button mode="tertiary" type="button" onClick={() => navigate("/miniapp/index.html?demoSession=admin")}>Открыть</Button></div>
    {!!houses.length && <div className="content-grid">
      <Card><SectionHeading number="01" title="Дом" subtitle="Изменения доступны только для выбранного дома" /><Field id="admin-house" label="Администрируемый дом"><select id="admin-house" value={houseId} onChange={event => { setHouseId(event.target.value); resetContact(); }}>{houses.map(house => <option key={house.id} value={house.id}>{house.address}</option>)}</select></Field></Card>
      <Card><SectionHeading number="02" title="Контакты" subtitle="Локальные сведения с датой изменения в паспорте дома" />
        <div className="item-list">{contacts.map(contact => <div className="admin-row" key={contact.id}><div><strong>{contact.title}</strong><small>{contact.type === "EMERGENCY" ? "Аварийный" : "Местный"} · {contact.phone}</small></div><button type="button" onClick={() => { setEditingId(contact.id); setContactType(contact.type); setTitle(contact.title); setPhone(contact.phone); setDetails(contact.details || ""); }}>Изменить</button><button type="button" disabled={busy} onClick={() => removeContact(contact.id)}>Удалить</button></div>)}</div>
        <form onSubmit={saveContact} className="admin-form"><h3>{editingId ? "Изменить контакт" : "Добавить контакт"}</h3><p className="hint">Указывайте только публичные телефоны служб дома.</p><Field id="contact-type" label="Тип"><select id="contact-type" value={contactType} onChange={event => setContactType(event.target.value)}><option value="EMERGENCY">Аварийный</option><option value="LOCAL">Местный</option></select></Field><Field id="contact-title" label="Название"><input id="contact-title" value={title} onChange={event => setTitle(event.target.value)} maxLength="120" required /></Field><Field id="contact-phone" label="Телефон"><input id="contact-phone" type="tel" value={phone} onChange={event => setPhone(event.target.value)} maxLength="40" pattern="[+0-9() .-]{2,40}" required /></Field><Field id="contact-details" label="Примечание"><textarea id="contact-details" value={details} onChange={event => setDetails(event.target.value)} maxLength="500" /></Field><Button mode="primary" className="brand-button" type="submit" stretched disabled={busy}>{editingId ? "Сохранить изменения" : "Добавить контакт"}</Button>{editingId && <Button mode="tertiary" type="button" stretched onClick={resetContact}>Отмена</Button>}</form>
      </Card>
      <Card><SectionHeading number="03" title="Приглашения" subtitle="Ссылка добавляет жителя только в выбранный дом" />
        <form onSubmit={createInvitation} className="admin-form"><div className="field-grid"><Field id="invite-days" label="Срок, дней"><input id="invite-days" type="number" min="1" max="30" value={days} onChange={event => setDays(event.target.value)} required /></Field><Field id="invite-limit" label="Число активаций"><input id="invite-limit" type="number" min="1" max="100" value={activationLimit} onChange={event => setActivationLimit(event.target.value)} required /></Field></div><Button mode="primary" className="brand-button" type="submit" stretched disabled={busy}>Создать приглашение</Button></form>
        <InvitationToken token={createdToken} />
        <div className="item-list admin-invitations">{invitations.filter(invitation => invitation.role === "RESIDENT" && invitation.houses.some(house => house.id === houseId)).map(invitation => <div className="admin-row" key={invitation.id}><div><strong>{invitation.revokedAt ? "Отозвано" : new Date(invitation.expiresAt) < new Date() ? "Истекло" : "Активно"}</strong><small>До {passportDate(invitation.expiresAt)} · использовано {invitation.activationCount} из {invitation.activationLimit}</small></div>{!invitation.revokedAt && <button type="button" disabled={busy} onClick={() => revokeInvitation(invitation.id)}>Отозвать</button>}</div>)}</div>
        <h3>Жители дома</h3><p className="hint">Здесь управляют только ролью жителя. Другие роли пользователя сохраняются.</p><div className="item-list">{residents.map(item => <div className="admin-row" key={item.userId}><div><strong>{item.displayName}</strong><small>{item.status}{item.userId === currentUserId ? " · вы" : ""}</small></div>{item.status === "ACTIVE" && currentUserId && item.userId !== currentUserId && <button type="button" disabled={busy} onClick={() => revokeResident(item.userId)}>Отозвать роль жителя</button>}</div>)}</div>
      </Card>
      <Card><SectionHeading number="04" title="Предварительные опросы" subtitle="Узнайте мнение жителей выбранного дома" />
        <form onSubmit={createPoll} className="admin-form">
          <Field id="poll-question" label="Вопрос"><textarea id="poll-question" value={pollQuestion} onChange={event => setPollQuestion(event.target.value)} maxLength="500" required /></Field>
          <div className="poll-option-editor"><strong>Варианты ответа</strong>{pollOptions.map((value, index) =>
            <div className="poll-option-edit-row" key={index}><label htmlFor={`poll-option-${index}`}>{index + 1}</label>
              <input id={`poll-option-${index}`} value={value} maxLength="200" required placeholder={`Вариант ${index + 1}`} onChange={event => setPollOptions(current => current.map((item, position) => position === index ? event.target.value : item))} />
              {pollOptions.length > 2 && <button type="button" aria-label={`Удалить вариант ${index + 1}`} onClick={() => setPollOptions(current => current.filter((_, position) => position !== index))}>×</button>}</div>)}
            {pollOptions.length < 20 && <button className="add-poll-option" type="button" onClick={() => setPollOptions(current => [...current, ""])}>+ Добавить вариант</button>}</div>
          <Field id="poll-close" label="Закрыть опрос"><input id="poll-close" type="datetime-local" value={pollClose} onChange={event => setPollClose(event.target.value)} required /></Field>
          <label className="poll-check"><input type="checkbox" checked={hidePollResults} onChange={event => setHidePollResults(event.target.checked)} /> Показывать итоги только после закрытия</label>
          <p className="hint">Опрос не заменяет официальное голосование собственников.</p>
          <Button mode="primary" className="brand-button" type="submit" stretched disabled={busy}>Создать опрос</Button>
        </form>
        <div className="poll-list">{polls.map(poll => <PollCard key={poll.id} poll={poll} />)}</div>
      </Card>
    </div>}
    <footer>Пульс дома <span>·</span> Кабинет администратора</footer>
  </main>;
}

function Dispatcher() {
  const [houses, setHouses] = useState([]);
  const [houseId, setHouseId] = useState("");
  const [queue, setQueue] = useState([]);
  const [issue, setIssue] = useState(null);
  const [reason, setReason] = useState("");
  const [mergeTargetId, setMergeTargetId] = useState("");
  const [splitReason, setSplitReason] = useState("");
  const [plannedDate, setPlannedDate] = useState("");
  const [plannedDateReason, setPlannedDateReason] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const request = (path, options) => api("dispatcher", path, options);
  const fail = error => setNotice(error.message || String(error));
  const refreshQueue = async id => setQueue(await request(`/v1/dispatcher/issues?houseId=${encodeURIComponent(id)}`));

  useEffect(() => {
    request("/v1/me/dispatcher-houses").then(list => {
      setHouses(list);
      if (list.length) setHouseId(String(list[0].id));
    }).catch(fail);
  }, []);
  useEffect(() => {
    setIssue(null);
    if (houseId) refreshQueue(houseId).catch(fail);
  }, [houseId]);

  async function showIssue(id) {
    try { const selected = await request(`/v1/dispatcher/issues/${encodeURIComponent(id)}`); setIssue(selected); setPlannedDate(selected.plannedDate || ""); setPlannedDateReason(""); setMergeTargetId(""); setSplitReason(""); setNotice(""); }
    catch (error) { fail(error); }
  }

  async function nextStatus() {
    if (!reason.trim()) { setNotice("Укажите причину изменения статуса."); return; }
    setBusy(true); setNotice("");
    try {
      await request(`/v1/issues/${encodeURIComponent(issue.id)}/status`, {
        method: "PATCH", body: JSON.stringify({ status: transitions[issue.status][0], reason: reason.trim() }),
      });
      setReason("");
      await refreshQueue(houseId);
      await showIssue(issue.id);
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  }

  async function savePlannedDate() {
    if (!plannedDate) { setNotice("Укажите плановую дату."); return; }
    if (issue.plannedDate && !plannedDateReason.trim()) { setNotice("Укажите причину изменения срока."); return; }
    setBusy(true); setNotice("");
    try {
      const updated = await request(`/v1/issues/${encodeURIComponent(issue.id)}/planned-date`, {
        method: "PATCH", body: JSON.stringify({ date: plannedDate, reason: plannedDateReason.trim() }),
      });
      setIssue(updated); setPlannedDateReason(""); await refreshQueue(houseId);
      setNotice("Плановая дата сохранена. Жители увидят изменение в заявке.");
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  }

  async function mergeIssues() {
    if (!mergeTargetId) { setNotice("Выберите заявку для объединения."); return; }
    setBusy(true); setNotice("");
    try {
      const merged = await request(`/v1/issues/${encodeURIComponent(issue.id)}/merge`, {
        method: "POST", body: JSON.stringify({ targetIssueId: mergeTargetId }),
      });
      await refreshQueue(houseId);
      setIssue(merged); setMergeTargetId(""); setSplitReason(""); setNotice("Заявки объединены.");
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  }

  async function splitIssue(reportId) {
    if (!splitReason.trim()) { setNotice("Укажите причину разделения."); return; }
    setBusy(true); setNotice("");
    try {
      const separated = await request(`/v1/issues/${encodeURIComponent(issue.id)}/split`, {
        method: "POST", body: JSON.stringify({ reportId, reason: splitReason.trim() }),
      });
      await refreshQueue(houseId);
      setIssue(separated); setMergeTargetId(""); setSplitReason(""); setNotice("Обращение выделено в новую заявку.");
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  }

  const mergeTargets = issue && editableIssueStatuses.includes(issue.status) ? queue.filter(item =>
    item.id !== issue.id && editableIssueStatuses.includes(item.status)
      && issueStage[item.status] >= issueStage[issue.status]) : [];

  return <main className="shell">
    <Brand role="Диспетчеру" /><Hero dispatcher /><Notice message={notice} /><QuickLink dispatcher />
    <div className="content-grid">
      <Card className="queue-card"><SectionHeading number="01" title="Очередь заявок" subtitle="Обращения жителей по вашему дому" />
        <Field id="house" label="Дом"><select id="house" value={houseId} onChange={event => setHouseId(event.target.value)}><option value="">Выберите дом</option>{houses.map(h => <option value={h.id} key={h.id}>{h.address}</option>)}</select></Field>
        <div className="item-list">{queue.length ? queue.map(item => <div key={item.id} className="queue-item"><CellSimple className="issue-cell" title={item.location || "Место не указано"} subtitle={item.description || "Все обращения отозваны"} overline={statusLabels[item.status] || item.status} showChevron onClick={() => showIssue(item.id)} />{item.plannedDate && <small className={issueOverdue(item) ? "deadline-red" : ""}>{issueOverdue(item) ? "Просрочено: " : "План до: "}{passportDate(`${item.plannedDate}T12:00:00`)}</small>}</div>) : <p className="muted empty">Новых заявок пока нет.</p>}</div>
        <Button mode="tertiary" className="quiet-action" type="button" stretched disabled={!houseId} onClick={() => refreshQueue(houseId).catch(fail)}>Обновить очередь</Button>
      </Card>
      {issue && <Card className="detail-panel"><SectionHeading number="02" title="Карточка заявки" subtitle="Детали обращения и следующий шаг" />
        <h3>{categoryLabels[issue.category] || issue.category || "Проблема дома"}</h3>
        <IssueDetails issue={issue} role="dispatcher" onError={setNotice} />
        {editableIssueStatuses.includes(issue.status) && <div className="secondary-operation"><h3>Плановая дата исполнения</h3><p className="hint">Жители увидят дату и все её изменения. День заканчивается по московскому времени.</p><Field id="planned-date" label="Планируем выполнить до"><input id="planned-date" type="date" min={moscowToday()} value={plannedDate} onChange={event => setPlannedDate(event.target.value)} /></Field>{issue.plannedDate && <Field id="planned-date-reason" label="Причина изменения"><textarea id="planned-date-reason" maxLength="1000" value={plannedDateReason} onChange={event => setPlannedDateReason(event.target.value)} placeholder="Например, ожидаем запчасть" /></Field>}<Button mode="primary" className="brand-button" type="button" stretched disabled={busy || !plannedDate || plannedDate === issue.plannedDate || (!!issue.plannedDate && !plannedDateReason.trim())} onClick={savePlannedDate}>{issue.plannedDate ? "Изменить дату" : "Установить дату"}</Button></div>}
        {transitions[issue.status] && <><Field id="reason" label="Комментарий к изменению статуса"><textarea id="reason" maxLength="1000" placeholder="Что сделано или кому передана задача" value={reason} onChange={event => setReason(event.target.value)} /></Field><Button mode="primary" className="brand-button" type="button" stretched disabled={busy} onClick={nextStatus}>{transitions[issue.status][1]}</Button></>}
        {editableIssueStatuses.includes(issue.status) && <div className="secondary-operation"><h3>Обращения в заявке</h3>{issue.reports?.length > 1 && <Field id="split-reason" label="Причина разделения"><textarea id="split-reason" maxLength="1000" value={splitReason} onChange={event => setSplitReason(event.target.value)} placeholder="Например, другая проблема или место" /></Field>}{issue.reports?.map(report => <div className="linked-report" key={report.id}><small>{report.author}</small><p>{report.description}</p>{issue.reports.length > 1 && <Button mode="tertiary" type="button" disabled={busy || !splitReason.trim()} onClick={() => splitIssue(report.id)}>Выделить в новую заявку</Button>}</div>)}</div>}
        {!!mergeTargets.length && <div className="secondary-operation"><h3>Объединить заявки</h3><p className="hint">Обращения из этой заявки перейдут в выбранную. Выберите заявку на той же или более поздней стадии работы.</p><Field id="merge-target" label="Основная заявка"><select id="merge-target" value={mergeTargetId} onChange={event => setMergeTargetId(event.target.value)}><option value="">Выберите заявку</option>{mergeTargets.map(item => <option key={item.id} value={item.id}>{item.location || item.address} · {statusLabels[item.status] || item.status} · {item.id.slice(0, 8)}</option>)}</select></Field><Button mode="secondary" type="button" stretched disabled={busy || !mergeTargetId} onClick={mergeIssues}>Объединить с выбранной заявкой</Button></div>}
      </Card>}
    </div>
    <footer>Пульс дома <span>·</span> Кабинет диспетчера</footer>
  </main>;
}

const accessRoleLabels = {
  SYSTEM_ADMIN: "Системный администратор", UK_ADMIN: "Администратор УК",
  HOUSE_ADMIN: "Администратор дома", DISPATCHER: "Диспетчер", RESIDENT: "Житель",
};

function InvitationGate() {
  const [token, setToken] = useState(() => new URLSearchParams(location.search).get("invite") || window.WebApp?.initDataUnsafe?.start_param || "");
  const [dismissed, setDismissed] = useState(false);
  const [invitation, setInvitation] = useState(null);
  const [legacy, setLegacy] = useState(false);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const role = new URLSearchParams(location.search).get("demoSession") === "admin" ? "admin" : "true";
  useEffect(() => {
    if (!token) return;
    api(role, `/v1/access/invitations/${encodeURIComponent(token)}`)
      .then(setInvitation).catch(async () => {
        try {
          const old = await api(role, `/v1/invitations/${encodeURIComponent(token)}`);
          setLegacy(true);
          setInvitation({ role: "RESIDENT", inviter: "Администратор дома", organizationName: "",
            houses: [{ id: old.houseId, address: old.address }], expiresAt: old.expiresAt, acceptedByMe: false });
        } catch (error) { setNotice(error.message || String(error)); }
      });
  }, [token]);
  useEffect(() => {
    if (!token && !dismissed) {
      const lastMode = localStorage.getItem("pulse-last-mode");
      if (lastMode && lastMode !== "/miniapp/index.html") navigate(lastMode);
    }
  }, [token, dismissed]);
  if (!token) return <Resident />;
  function continueToRole() {
    if (invitation.role === "UK_ADMIN") { navigate("/uk/index.html"); return; }
    if (invitation.role === "HOUSE_ADMIN") { navigate("/admin/index.html"); return; }
    if (invitation.role === "DISPATCHER") { navigate("/dispatcher/index.html"); return; }
    setDismissed(true); setToken(""); localStorage.setItem("pulse-last-mode", "/miniapp/index.html");
    const url = new URL(location.href); url.searchParams.delete("invite"); history.replaceState(null, "", url);
  }
  async function accept() {
    setBusy(true); setNotice("");
    try {
      await api(role, legacy ? `/v1/invitations/${encodeURIComponent(token)}/accept`
        : `/v1/access/invitations/${encodeURIComponent(token)}/accept`, { method: "POST" });
      continueToRole();
    } catch (error) { setNotice(error.message || String(error)); } finally { setBusy(false); }
  }
  return <main className="shell"><Brand role="Активация" /><section className="hero"><div className="hero-copy"><h1>Приглашение<br />в Пульс дома</h1><p>Проверьте роль и дома перед подтверждением доступа.</p></div></section>
    <Notice message={notice} />
    {invitation && <Card><SectionHeading title={accessRoleLabels[invitation.role]} subtitle={invitation.organizationName || "Доступ к дому"} />
      <p>Пригласил: {invitation.inviter}</p><p>Дома: {invitation.houses.map(house => house.address).join(", ")}</p>
      <p>Приглашение действует до {new Date(invitation.expiresAt).toLocaleString("ru-RU")}.</p>
      <Button mode="primary" type="button" stretched disabled={busy} onClick={invitation.acceptedByMe ? continueToRole : accept}>
        {invitation.acceptedByMe ? "Перейти в приложение" : "Принять приглашение"}</Button></Card>}
    {notice && <Button mode="tertiary" type="button" onClick={() => { setDismissed(true); setToken(""); }}>Открыть мои доступы</Button>}
  </main>;
}

function HouseChecks({ houses, selected, onChange }) {
  return <div className="house-checks">{houses.map(house => <label key={house.id}><input type="checkbox" checked={selected.includes(house.id)} onChange={event => onChange(event.target.checked ? [...selected, house.id] : selected.filter(id => id !== house.id))} /> {house.address}</label>)}</div>;
}

function IssuedInvitations({ items, onRevoke, busy }) {
  return <div className="item-list admin-invitations">{items.map(item => <div className="admin-row" key={item.id}><div><strong>{accessRoleLabels[item.role]} · {item.revokedAt ? "отозвано" : new Date(item.expiresAt) < new Date() ? "истекло" : "активно"}</strong><small>{item.houses.map(house => house.address).join(", ")} · использовано {item.activationCount} из {item.activationLimit}</small></div>{!item.revokedAt && <button type="button" disabled={busy} onClick={() => onRevoke(item.id)}>Отозвать</button>}</div>)}</div>;
}

function SystemAdmin() {
  const [organizations, setOrganizations] = useState([]);
  const [houses, setHouses] = useState([]);
  const [admins, setAdmins] = useState([]);
  const [invitations, setInvitations] = useState([]);
  const [organizationId, setOrganizationId] = useState("");
  const [organizationName, setOrganizationName] = useState("");
  const [houseAddress, setHouseAddress] = useState("");
  const [houseId, setHouseId] = useState("");
  const [selected, setSelected] = useState([]);
  const [token, setToken] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const request = (path, options) => api("admin", path, options);
  const refresh = async () => { const [orgs, homes] = await Promise.all([request("/v1/access/organizations"), request("/v1/access/houses")]); setOrganizations(orgs); setHouses(homes); if (!organizationId && orgs.length) setOrganizationId(orgs[0].id); };
  useEffect(() => { Promise.all([refresh(), request("/v1/access/invitations").then(setInvitations)]).catch(error => setNotice(error.message)); }, []);
  useEffect(() => { if (organizationId) request(`/v1/access/organizations/${organizationId}/admins`).then(setAdmins).catch(error => setNotice(error.message)); }, [organizationId]);
  const org = organizations.find(item => item.id === organizationId);
  async function run(action) { setBusy(true); setNotice(""); try { await action(); await refresh(); } catch (error) { setNotice(error.message); } finally { setBusy(false); } }
  async function revokeInvitation(id) { await run(async () => { await request(`/v1/access/invitations/${id}/revoke`, { method: "POST" }); setInvitations(await request("/v1/access/invitations")); }); }
  return <main className="shell"><Brand role="Системному администратору" /><section className="hero"><div className="hero-copy"><h1>УК и доступы</h1><p>Создавайте организации, закрепляйте дома и приглашайте администраторов УК.</p></div></section><Notice message={notice} />
    <div className="content-grid"><Card><SectionHeading title="Управляющие компании" />
      <form onSubmit={event => { event.preventDefault(); run(async () => { await request("/v1/access/organizations", { method: "POST", body: JSON.stringify({ name: organizationName.trim() }) }); setOrganizationName(""); }); }}><Field id="org-name" label="Название УК"><input id="org-name" value={organizationName} maxLength="200" required onChange={event => setOrganizationName(event.target.value)} /></Field><Button mode="primary" type="submit" disabled={busy}>Создать УК</Button></form>
      <Field id="org-select" label="УК"><select id="org-select" value={organizationId} onChange={event => { setOrganizationId(event.target.value); setSelected([]); }}><option value="">Выберите УК</option>{organizations.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}</select></Field>
      <form onSubmit={event => { event.preventDefault(); run(async () => { await request("/v1/access/houses", { method: "POST", body: JSON.stringify({ address: houseAddress.trim() }) }); setHouseAddress(""); }); }}><Field id="house-address" label="Новый дом"><input id="house-address" value={houseAddress} maxLength="300" required onChange={event => setHouseAddress(event.target.value)} /></Field><Button mode="secondary" type="submit" disabled={busy}>Добавить дом</Button></form>
      {org && <><Field id="house-link" label="Закрепить дом за УК"><select id="house-link" value={houseId} onChange={event => setHouseId(event.target.value)}><option value="">Выберите дом</option>{houses.map(house => <option value={house.id} key={house.id}>{house.address}</option>)}</select></Field><Button mode="secondary" type="button" disabled={busy || !houseId} onClick={() => run(() => request(`/v1/access/organizations/${organizationId}/houses/${houseId}`, { method: "PUT", body: JSON.stringify({ active: true }) }))}>Закрепить</Button>
        <div className="item-list linked-house-list">{org.houses.map(house => <div className="admin-row" key={house.id}><span>{house.address}</span><button type="button" disabled={busy} onClick={() => { if (window.confirm("Приостановить доступ УК и её сотрудников к дому?")) run(() => request(`/v1/access/organizations/${organizationId}/houses/${house.id}`, { method: "PUT", body: JSON.stringify({ active: false }) })); }}>Приостановить</button></div>)}</div></>}
    </Card><Card><SectionHeading title="Пригласить администратора УК" subtitle="Одноразовая ссылка на 72 часа" />
      {org && <form onSubmit={event => { event.preventDefault(); run(async () => { const result = await request("/v1/access/invitations", { method: "POST", body: JSON.stringify({ role: "UK_ADMIN", organizationId, houseIds: selected, days: 3, activationLimit: 1 }) }); setToken(result.token); setInvitations(await request("/v1/access/invitations")); }); }}><HouseChecks houses={org.houses} selected={selected} onChange={setSelected} /><Button mode="primary" type="submit" disabled={busy || !selected.length}>Создать приглашение</Button></form>}
      <InvitationToken token={token} />
      <IssuedInvitations items={invitations.filter(item => item.role === "UK_ADMIN" && item.organizationId === organizationId)} onRevoke={revokeInvitation} busy={busy} />
      <h3>Администраторы УК</h3><div className="item-list">{admins.map(item => <div className="admin-row" key={`${item.userId}-${item.houseId}`}><div><strong>{item.displayName}</strong><small>{item.houseAddress || "Без домов"} · {item.status}</small></div>{item.status !== "REVOKED" && <button type="button" disabled={busy} onClick={() => run(async () => { await request("/v1/access/assignments/revoke", { method: "POST", body: JSON.stringify({ role: "UK_ADMIN", userId: item.userId, organizationId }) }); setAdmins(await request(`/v1/access/organizations/${organizationId}/admins`)); })}>Отозвать роль</button>}</div>)}</div>
    </Card></div><Button mode="tertiary" onClick={() => navigate("/miniapp/index.html?demoSession=admin")}>В мини-приложение</Button></main>;
}

function UkAdmin() {
  const [organizations, setOrganizations] = useState([]);
  const [organizationId, setOrganizationId] = useState("");
  const [role, setRole] = useState("DISPATCHER");
  const [selected, setSelected] = useState([]);
  const [assignments, setAssignments] = useState([]);
  const [invitations, setInvitations] = useState([]);
  const [token, setToken] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const request = (path, options) => api("admin", path, options);
  useEffect(() => { Promise.all([request("/v1/access/my-organizations"), request("/v1/access/invitations")]).then(([list, issued]) => { setOrganizations(list); setInvitations(issued); if (list.length) setOrganizationId(list[0].id); else setNotice("Нет доступа администратора УК."); }).catch(error => setNotice(error.message)); }, []);
  useEffect(() => { if (organizationId) request(`/v1/access/organizations/${organizationId}/assignments`).then(setAssignments).catch(error => setNotice(error.message)); }, [organizationId]);
  const org = organizations.find(item => item.id === organizationId);
  async function create(event) {
    event.preventDefault(); setBusy(true); setNotice("");
    try { const result = await request("/v1/access/invitations", { method: "POST", body: JSON.stringify({ role, organizationId, houseIds: selected, days: 3, activationLimit: 1 }) }); setToken(result.token); setInvitations(await request("/v1/access/invitations")); }
    catch (error) { setNotice(error.message); } finally { setBusy(false); }
  }
  async function revoke(assignment) {
    setBusy(true); setNotice("");
    try { await request("/v1/access/assignments/revoke", { method: "POST", body: JSON.stringify({ role: assignment.role, userId: assignment.userId, organizationId, houseId: assignment.houseId }) }); setAssignments(await request(`/v1/access/organizations/${organizationId}/assignments`)); }
    catch (error) { setNotice(error.message); } finally { setBusy(false); }
  }
  async function revokeInvitation(id) {
    setBusy(true); setNotice("");
    try { await request(`/v1/access/invitations/${id}/revoke`, { method: "POST" }); setInvitations(await request("/v1/access/invitations")); }
    catch (error) { setNotice(error.message); } finally { setBusy(false); }
  }
  return <main className="shell"><Brand role="Администратору УК" /><section className="hero"><div className="hero-copy"><h1>Команда УК</h1><p>Приглашайте диспетчеров и администраторов назначенных домов.</p></div></section><Notice message={notice} />
    <div className="content-grid"><Card><SectionHeading title="Пригласить сотрудника" /><Field id="uk-org" label="Управляющая компания"><select id="uk-org" value={organizationId} onChange={event => { setOrganizationId(event.target.value); setSelected([]); }}><option value="">Выберите УК</option>{organizations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></Field>
      <form onSubmit={create}><Field id="staff-role" label="Роль"><select id="staff-role" value={role} onChange={event => { setRole(event.target.value); setSelected([]); }}><option value="DISPATCHER">Диспетчер</option><option value="HOUSE_ADMIN">Администратор дома</option></select></Field><HouseChecks houses={org?.houses || []} selected={selected} onChange={setSelected} /><Button mode="primary" type="submit" disabled={busy || !selected.length || (role === "HOUSE_ADMIN" && selected.length !== 1)}>Создать приглашение</Button></form><InvitationToken token={token} /><IssuedInvitations items={invitations.filter(item => item.organizationId === organizationId)} onRevoke={revokeInvitation} busy={busy} /></Card>
      <Card><SectionHeading title="Назначенные сотрудники" /><div className="item-list">{assignments.map(item => <div className="admin-row" key={`${item.userId}-${item.role}-${item.houseId}`}><div><strong>{item.displayName}</strong><small>{accessRoleLabels[item.role]} · {item.houseAddress} · {item.status}</small></div>{item.status === "ACTIVE" && <button type="button" disabled={busy} onClick={() => revoke(item)}>Отозвать</button>}</div>)}</div></Card>
    </div><Button mode="tertiary" onClick={() => navigate("/miniapp/index.html?demoSession=admin")}>В мини-приложение</Button></main>;
}

createRoot(document.getElementById("app")).render(
  <MaxUI><DemoRoleSwitcher />{document.body.dataset.page === "dispatcher" ? <Dispatcher /> : document.body.dataset.page === "admin" ? <Admin /> : document.body.dataset.page === "system" ? <SystemAdmin /> : document.body.dataset.page === "uk" ? <UkAdmin /> : <InvitationGate />}</MaxUI>
);
