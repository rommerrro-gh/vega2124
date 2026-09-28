import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { MaxUI, Panel, Button, CellSimple } from "@maxhub/max-ui";
import "@maxhub/max-ui/dist/styles.css";
import "./theme.css";

const statusLabels = {
  DRAFT: "Ожидает диспетчера", OPEN: "Принята", ASSIGNED: "Назначена",
  IN_PROGRESS: "В работе", VERIFICATION_72H: "Ожидает вашего подтверждения",
  CLOSED_CONFIRMED: "Закрыта после подтверждения",
  CLOSED_UNCONFIRMED: "Закрыта по истечении срока", REOPENED: "Открыта повторно",
};
const categoryLabels = {
  LIGHTING: "Освещение", WATER: "Вода", HEATING: "Отопление",
  ELEVATOR: "Лифт", OTHER: "Другое",
};
const transitions = {
  DRAFT: ["OPEN", "Принять заявку"], OPEN: ["ASSIGNED", "Назначить себе"],
  ASSIGNED: ["IN_PROGRESS", "Начать работу"], IN_PROGRESS: ["RESOLVED", "Отметить выполненной"],
  REOPENED: ["ASSIGNED", "Повторно назначить себе"],
};
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
    if (response.status === 401) throw new Error("Откройте приложение в MAX. Для локального просмотра запустите сервер с профилем demo.");
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `Ошибка ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

function navigate(path) {
  const initData = window.WebApp?.initData;
  const launchData = window.location.hash || (initData ? `#WebAppData=${encodeURIComponent(initData)}` : "");
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

function SectionHeading({ number, title, subtitle }) {
  return <div className="section-heading"><span className="section-number">{number}</span><div><h2>{title}</h2><p>{subtitle}</p></div></div>;
}

function Field({ id, label, children }) {
  return <div className="field"><label htmlFor={id}>{label}</label>{children}</div>;
}

function Notice({ message }) {
  return message ? <div className="notice" role="alert">{message}</div> : null;
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

function AttachmentList({ attachments, role, onError }) {
  if (!attachments?.length) return null;
  return <div className="item-list">{attachments.map(file => <button className="attachment-link" type="button" key={file.id} onClick={() => downloadAttachment(role, file, onError)}>Открыть вложение · {file.mime}</button>)}</div>;
}

function IssueDetails({ issue, role, onError }) {
  return <>
    <div className="detail-lines"><p className="muted">{issue.address}</p>{issue.location && <p className="muted">Место: {issue.location}</p>}{issue.occurredAt && <p className="muted">Замечено: {new Date(issue.occurredAt).toLocaleString("ru-RU")}</p>}</div>
    <p className="detail-description">{issue.description}</p>
    <AttachmentList attachments={issue.attachments} role={role} onError={onError} />
    <div className="result-meta"><span>{statusLabels[issue.status] || issue.status}</span><span>Участников: {issue.participants}</span></div>
  </>;
}

function HousePassport({ houses, selectedId, onSelect, passport, loading, error }) {
  if (!houses.length) return null;
  return <Card className="passport-card">
    <SectionHeading number="01" title="Паспорт дома" subtitle="Сведения о доме с источником и датой каждого поля" />
    {houses.length > 1 && <Field id="passport-house" label="Дом"><select id="passport-house" value={selectedId} onChange={event => onSelect(event.target.value)}>{houses.map(house => <option key={house.id} value={house.id}>{house.address}</option>)}</select></Field>}
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
  </Card>;
}

function PollCard({ poll, onVote, busy }) {
  const [optionId, setOptionId] = useState("");
  return <div className="poll-card">
    <div className="poll-heading"><strong>{poll.question}</strong><span>{poll.closed ? "Завершён" : `До ${passportDate(poll.closesAt)}`}</span></div>
    <p className="hint">Предварительный опрос жителей · не является официальным голосованием собственников</p>
    <div className="poll-options">{poll.options.map(option => <label key={option.id} className="poll-option">
      {!poll.myOptionId && !poll.closed && onVote && <input type="radio" name={`poll-${poll.id}`} value={option.id} checked={optionId === option.id} onChange={() => setOptionId(option.id)} />}
      <span>{option.label}{poll.myOptionId === option.id ? " · ваш выбор" : ""}</span>
      {poll.resultsVisible && <small>{option.votes}</small>}
    </label>)}</div>
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
  const [notice, setNotice] = useState("");
  const [step, setStep] = useState("form");
  const [reportId, setReportId] = useState(null);
  const [reportData, setReportData] = useState(null);
  const [uploaded, setUploaded] = useState(0);
  const [candidates, setCandidates] = useState([]);
  const [issue, setIssue] = useState(null);
  const [busy, setBusy] = useState(false);
  const [verificationComment, setVerificationComment] = useState("");
  const demoSession = new URLSearchParams(location.search).get("demoSession") === "admin" ? "admin" : "true";
  const request = (path, options) => api(demoSession, path, options);
  const refreshIssues = async () => setIssues(await request("/v1/me/issues"));
  const fail = error => setNotice(error.message || String(error));

  useEffect(() => {
    async function load() {
      const invitation = new URLSearchParams(location.search).get("invite") || window.WebApp?.initDataUnsafe?.start_param;
      if (invitation) await request(`/v1/invitations/${encodeURIComponent(invitation)}/accept`, { method: "POST" });
      const [myHouses, myIssues, dispatcherHouses] = await Promise.all([
        request("/v1/me/houses"), request("/v1/me/issues"), request("/v1/me/dispatcher-houses"),
      ]);
      setHouses(myHouses); setIssues(myIssues); setDispatcher(dispatcherHouses.length > 0);
      if (myHouses.length) setSelectedHouseId(myHouses[0].id);
      if (!myHouses.length) setNotice("У вас пока нет подтверждённого доступа к дому. Попросите приглашение у администратора.");
    }
    load().catch(fail);
  }, []);

  useEffect(() => {
    if (!selectedHouseId) return;
    let active = true;
    const membership = houses.find(house => house.id === selectedHouseId)?.memberships || [];
    const canVote = membership.some(access => ["RESIDENT", "HOUSE_ADMIN"].includes(access.role) && access.verificationStatus === "VERIFIED");
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

  function showIssue(nextIssue) {
    setIssue(nextIssue); setVerificationComment(""); setStep("result"); setNotice("");
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
          houseId: form.elements.house.value, category: form.elements.category.value,
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
      showIssue(result);
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
      showIssue(result);
      await refreshIssues();
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  }

  function again() {
    setReportId(null); setReportData(null); setUploaded(0); setCandidates([]); setIssue(null);
    setStep("form"); setNotice("");
    requestAnimationFrame(() => {
      formRef.current?.reset();
      const date = formRef.current?.elements["occurred-at"];
      if (date) date.value = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    });
  }

  const localNow = useRef(new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16));
  return <main className="shell">
    <Brand role="Жителю" /><Hero /><Notice message={notice} />
    {dispatcher && <QuickLink />}
    {houses.some(house => house.memberships?.some(access => access.role === "HOUSE_ADMIN" && access.verificationStatus === "VERIFIED")) && <AdminLink />}
    <div className="content-grid">
      <HousePassport houses={houses} selectedId={selectedHouseId} onSelect={setSelectedHouseId} passport={passport} loading={passportLoading} error={passportError} />
      {!!polls.length && <Card><SectionHeading number="02" title="Опросы дома" subtitle="Ваше мнение о делах дома" /><div className="poll-list">{polls.map(poll => <PollCard key={poll.id} poll={poll} onVote={voteInPoll} busy={pollBusy} />)}</div></Card>}
      <Card className="issues-card">
        <SectionHeading number="02" title="Мои заявки" subtitle="Следите за тем, как решаются ваши обращения" />
        <div className="item-list">{issues.length ? issues.map(item => <CellSimple key={item.id} className="issue-cell" title={item.location || item.address} subtitle={item.description} overline={statusLabels[item.status] || item.status} showChevron onClick={() => showIssue(item)} />) : <p className="muted empty">Заявок пока нет.</p>}</div>
        <Button mode="tertiary" className="quiet-action" type="button" stretched onClick={() => refreshIssues().catch(fail)}>Обновить список</Button>
      </Card>
      {step === "form" && <Card className="form-card">
        <SectionHeading number="03" title="Сообщить о проблеме" subtitle="Заполнение займёт пару минут" />
        <form ref={formRef} onSubmit={submitReport}>
          <div className="field-grid">
            <Field id="house" label="Дом"><select id="house" name="house" required disabled={!!reportId} defaultValue={houses.length === 1 ? houses[0].id : ""} key={houses.map(h => h.id).join("|")}><option value="">Выберите дом</option>{houses.map(h => <option value={h.id} key={h.id}>{h.address}</option>)}</select></Field>
            <Field id="category" label="Категория"><select id="category" name="category" required disabled={!!reportId} defaultValue=""><option value="">Выберите категорию</option><option value="LIGHTING">Освещение</option><option value="WATER">Вода</option><option value="HEATING">Отопление</option><option value="ELEVATOR">Лифт</option><option value="OTHER">Другое</option></select></Field>
          </div>
          <Field id="location" label="Где именно?"><input id="location" name="location" maxLength="160" placeholder="Например, подъезд 1, этаж 2" required disabled={!!reportId} /></Field>
          <Field id="occurred-at" label="Когда заметили?"><input id="occurred-at" name="occurred-at" type="datetime-local" defaultValue={localNow.current} required disabled={!!reportId} /></Field>
          <Field id="description" label="Что произошло?"><textarea id="description" name="description" maxLength="4000" minLength="8" placeholder="Опишите, что случилось и как это влияет на жителей" required disabled={!!reportId} /></Field>
          <Field id="attachments" label="Фото, видео или документ"><input id="attachments" name="attachments" type="file" accept="image/jpeg,image/png,video/mp4,application/pdf" multiple /></Field>
          <p className="hint">До 5 файлов по 10 МБ. Содержимое файлов не анализируется.</p>
          <Button mode="primary" className="brand-button" type="submit" stretched disabled={busy}>Проверить похожие заявки <span aria-hidden="true">→</span></Button>
        </form>
      </Card>}
      {step === "decision" && <Card className="flow-card">
        <SectionHeading number="04" title="Похожие проблемы" subtitle="Выберите существующую заявку или создайте новую" />
        <div className="item-list">{candidates.length ? candidates.map(({ issue: item, score }) => <div className="candidate" key={item.id}><span className="score">{Math.round(score * 100)}% совпадение</span><strong>{item.description || item.category || "Проблема дома"}</strong><p>{item.address} · {item.participants} участников</p><button type="button" disabled={busy} onClick={() => decide(`/v1/issues/${encodeURIComponent(item.id)}/join`)}>Присоединиться</button></div>) : <p className="muted empty">Похожих активных заявок не найдено.</p>}</div>
        <Button mode="primary" className="brand-button" type="button" stretched disabled={busy} onClick={() => decide("/v1/issues")}>Создать новую заявку <span aria-hidden="true">→</span></Button>
      </Card>}
      {step === "result" && issue && <Card className="flow-card">
        <div className="success-mark" aria-hidden="true">✓</div><p className="eyebrow">ОБРАЩЕНИЕ СОХРАНЕНО</p>
        <h2>{issue.status === "DRAFT" ? "Новая заявка сохранена" : "Заявка"}</h2>
        <IssueDetails issue={issue} role="true" onError={setNotice} />
        {issue.status === "VERIFICATION_72H" && <div className="verification"><h3>Работа выполнена?</h3><p className="muted">{issue.verificationDueAt ? `Подтвердите до ${new Date(issue.verificationDueAt).toLocaleString("ru-RU")}` : ""}</p><Field id="verification-comment" label="Комментарий, если проблема осталась"><textarea id="verification-comment" maxLength="2000" placeholder="Что ещё не исправлено?" value={verificationComment} onChange={event => setVerificationComment(event.target.value)} /></Field><Button mode="primary" className="brand-button" type="button" stretched disabled={busy} onClick={() => verify(true)}>Да, проблема решена</Button><Button mode="secondary" type="button" stretched disabled={busy} onClick={() => verify(false)}>Нет, проблема осталась</Button></div>}
        <Button mode="secondary" className="bottom-action" type="button" stretched onClick={again}>Сообщить о другой проблеме</Button>
      </Card>}
    </div>
    <footer>Пульс дома <span>·</span> Сделаем дом лучше вместе</footer>
  </main>;
}

function Admin() {
  const [houses, setHouses] = useState([]);
  const [houseId, setHouseId] = useState("");
  const [contacts, setContacts] = useState([]);
  const [invitations, setInvitations] = useState([]);
  const [polls, setPolls] = useState([]);
  const [pollQuestion, setPollQuestion] = useState("");
  const [pollOptions, setPollOptions] = useState("Да\nНет");
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
    request("/v1/me/houses").then(list => {
      const allowed = list.filter(house => house.memberships?.some(access => access.role === "HOUSE_ADMIN" && access.verificationStatus === "VERIFIED"));
      setHouses(allowed); if (allowed.length) setHouseId(allowed[0].id);
      else setNotice("У вас нет подтверждённой роли администратора дома.");
    }).catch(fail);
  }, []);
  useEffect(() => {
    if (!houseId) return;
    setCreatedToken(""); setNotice("");
    Promise.all([request(`${base}/contacts`), request(`${base}/invitations`), request(`${base}/polls`)])
      .then(([nextContacts, nextInvitations, nextPolls]) => { setContacts(nextContacts); setInvitations(nextInvitations); setPolls(nextPolls); })
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
      const result = await request(`${base}/invitations`, { method: "POST", body: JSON.stringify({ days: Number(days), activationLimit: Number(activationLimit) }) });
      setCreatedToken(result.token); setInvitations(await request(`${base}/invitations`));
    } catch (error) { fail(error); } finally { setBusy(false); }
  }
  async function revokeInvitation(id) {
    setBusy(true); setNotice("");
    try { await request(`${base}/invitations/${encodeURIComponent(id)}/revoke`, { method: "POST" }); setInvitations(await request(`${base}/invitations`)); }
    catch (error) { fail(error); } finally { setBusy(false); }
  }
  async function createPoll(event) {
    event.preventDefault(); setBusy(true); setNotice("");
    try {
      const options = pollOptions.split("\n").map(value => value.trim()).filter(Boolean);
      await request(`${base}/polls`, { method: "POST", body: JSON.stringify({
        question: pollQuestion.trim(), options, closesAt: new Date(pollClose).toISOString(),
        resultsHiddenUntilClose: hidePollResults,
      }) });
      setPolls(await request(`${base}/polls`)); setPollQuestion(""); setPollOptions("Да\nНет");
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
        {createdToken && <div className="invite-token" role="status"><strong>Токен приглашения</strong><code>{createdToken}</code><p>Передайте его жителю приватно и добавьте к ссылке бота MAX как параметр <code>startapp</code>. После обновления страницы токен больше не отображается.</p></div>}
        <div className="item-list admin-invitations">{invitations.map(invitation => <div className="admin-row" key={invitation.id}><div><strong>{invitation.revokedAt ? "Отозвано" : new Date(invitation.expiresAt) < new Date() ? "Истекло" : "Активно"}</strong><small>До {passportDate(invitation.expiresAt)} · использовано {invitation.activationCount} из {invitation.activationLimit}</small></div>{!invitation.revokedAt && <button type="button" disabled={busy} onClick={() => revokeInvitation(invitation.id)}>Отозвать</button>}</div>)}</div>
      </Card>
      <Card><SectionHeading number="04" title="Предварительные опросы" subtitle="Узнайте мнение жителей выбранного дома" />
        <form onSubmit={createPoll} className="admin-form">
          <Field id="poll-question" label="Вопрос"><textarea id="poll-question" value={pollQuestion} onChange={event => setPollQuestion(event.target.value)} maxLength="500" required /></Field>
          <Field id="poll-options" label="Варианты ответа — каждый с новой строки"><textarea id="poll-options" value={pollOptions} onChange={event => setPollOptions(event.target.value)} required /></Field>
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
    try { setIssue(await request(`/v1/dispatcher/issues/${encodeURIComponent(id)}`)); setNotice(""); }
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

  return <main className="shell">
    <Brand role="Диспетчеру" /><Hero dispatcher /><Notice message={notice} /><QuickLink dispatcher />
    <div className="content-grid">
      <Card className="queue-card"><SectionHeading number="01" title="Очередь заявок" subtitle="Обращения жителей по вашему дому" />
        <Field id="house" label="Дом"><select id="house" value={houseId} onChange={event => setHouseId(event.target.value)}><option value="">Выберите дом</option>{houses.map(h => <option value={h.id} key={h.id}>{h.address}</option>)}</select></Field>
        <div className="item-list">{queue.length ? queue.map(item => <CellSimple key={item.id} className="issue-cell" title={item.location || "Место не указано"} subtitle={item.description} overline={statusLabels[item.status] || item.status} showChevron onClick={() => showIssue(item.id)} />) : <p className="muted empty">Новых заявок пока нет.</p>}</div>
        <Button mode="tertiary" className="quiet-action" type="button" stretched disabled={!houseId} onClick={() => refreshQueue(houseId).catch(fail)}>Обновить очередь</Button>
      </Card>
      {issue && <Card className="detail-panel"><SectionHeading number="02" title="Карточка заявки" subtitle="Детали обращения и следующий шаг" />
        <h3>{categoryLabels[issue.category] || issue.category || "Проблема дома"}</h3>
        <IssueDetails issue={issue} role="dispatcher" onError={setNotice} />
        {transitions[issue.status] && <><Field id="reason" label="Комментарий к изменению статуса"><textarea id="reason" maxLength="1000" placeholder="Что сделано или кому передана задача" value={reason} onChange={event => setReason(event.target.value)} /></Field><Button mode="primary" className="brand-button" type="button" stretched disabled={busy} onClick={nextStatus}>{transitions[issue.status][1]}</Button></>}
      </Card>}
    </div>
    <footer>Пульс дома <span>·</span> Кабинет диспетчера</footer>
  </main>;
}

createRoot(document.getElementById("app")).render(
  <MaxUI>{document.body.dataset.page === "dispatcher" ? <Dispatcher /> : document.body.dataset.page === "admin" ? <Admin /> : <Resident />}</MaxUI>
);
