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
  return response.json();
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

function Resident() {
  const formRef = useRef(null);
  const [houses, setHouses] = useState([]);
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
  const request = (path, options) => api("true", path, options);
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
      if (!myHouses.length) setNotice("У вас пока нет подтверждённого доступа к дому. Попросите приглашение у администратора.");
    }
    load().catch(fail);
  }, []);

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
    <div className="content-grid">
      <Card className="issues-card">
        <SectionHeading number="01" title="Мои заявки" subtitle="Следите за тем, как решаются ваши обращения" />
        <div className="item-list">{issues.length ? issues.map(item => <CellSimple key={item.id} className="issue-cell" title={item.location || item.address} subtitle={item.description} overline={statusLabels[item.status] || item.status} showChevron onClick={() => showIssue(item)} />) : <p className="muted empty">Заявок пока нет.</p>}</div>
        <Button mode="tertiary" className="quiet-action" type="button" stretched onClick={() => refreshIssues().catch(fail)}>Обновить список</Button>
      </Card>
      {step === "form" && <Card className="form-card">
        <SectionHeading number="02" title="Сообщить о проблеме" subtitle="Заполнение займёт пару минут" />
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
        <SectionHeading number="03" title="Похожие проблемы" subtitle="Выберите существующую заявку или создайте новую" />
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
  <MaxUI>{document.body.dataset.page === "dispatcher" ? <Dispatcher /> : <Resident />}</MaxUI>
);
