import { PaymentComparison } from "./operations.jsx";
import React, { useEffect, useState } from "react";
import "./tariffs.css";
const units = {
  M3: "м³",
  KWH: "кВт·ч",
  GCAL: "Гкал",
  M2: "м²",
  PERSON: "чел.",
  MONTH: "месяц",
  OTHER: "другая единица",
};
const categories = {
  UTILITY: "Коммунальная услуга",
  HOUSING: "Содержание жилья",
  CAPITAL_REPAIR: "Капитальный ремонт",
  OTHER: "Другая плата",
};
const states = {
  DRAFT: "Черновик · жителям не виден",
  PUBLISHED: "Проверено и опубликовано",
  SUPERSEDED: "Заменено исправлением",
  WITHDRAWN: "Публикация отозвана",
};
const actions = {
  CREATED: "Создание версии",
  EDITED: "Изменение черновика",
  DOCUMENT_ADDED: "Добавление документа",
  DOCUMENT_REMOVED: "Исключение документа",
  PUBLISHED: "Проверка и публикация",
  SUPERSEDED: "Исправление публикации",
  WITHDRAWN: "Отзыв публикации",
};
const day = () =>
  new Intl.DateTimeFormat("sv-SE", { timeZone: "Europe/Moscow" }).format(new Date());
const date = (v) =>
  v ? new Date(v + "T12:00:00").toLocaleDateString("ru-RU") : "без указанной даты окончания";
const amount = (v) => Number(v).toLocaleString("ru-RU", { maximumFractionDigits: 6 });
const empty = {
  title: "",
  category: "UTILITY",
  provider: "",
  applicability: "",
  unit: "M3",
  unitLabel: "м³",
  amount: "",
  validFrom: day(),
  validUntil: "",
  sourceReference: "",
  sourceUrl: "",
  explanation: "",
  reason: "",
};
function Field({ label, children }) {
  return (
    <label>
      {label}
      {children}
    </label>
  );
}
function DraftForm({ initial, busy, onSave, onCancel }) {
  const [form, setForm] = useState({
    ...empty,
    ...initial,
    validUntil: initial?.validUntil || "",
    sourceUrl: initial?.sourceUrl || "",
    reason: "",
  });
  const text = (key, label, max = 250, type = "text", required = true) => (
    <Field label={label}>
      <input
        type={type}
        required={required}
        maxLength={max}
        value={form[key]}
        onChange={(e) => setForm({ ...form, [key]: e.target.value })}
      />
    </Field>
  );
  const area = (key, label, max) => (
    <Field label={label}>
      <textarea
        rows={3}
        required
        maxLength={max}
        value={form[key]}
        onChange={(e) => setForm({ ...form, [key]: e.target.value })}
      />
    </Field>
  );
  return (
    <form
      className="tariff-form"
      onSubmit={(e) => {
        e.preventDefault();
        onSave({
          ...form,
          amount: String(form.amount).replace(",", "."),
          validUntil: form.validUntil || null,
          sourceUrl: form.sourceUrl || null,
          revision: initial?.revision || 0,
        });
      }}
    >
      <h3>{initial?.id ? "Подготовить версию" : "Новая услуга или плата"}</h3>
      {text("title", "Название услуги", 160)}
      <Field label="Вид платы">
        <select
          value={form.category}
          onChange={(e) => setForm({ ...form, category: e.target.value })}
        >
          {Object.entries(categories).map(([k, v]) => (
            <option key={k} value={k}>
              {v}
            </option>
          ))}
        </select>
      </Field>
      {text("provider", "Поставщик / получатель платы")}
      {text("applicability", "Для каких помещений и условий применяется", 500)}
      <Field label="Единица расчёта">
        <select
          value={form.unit}
          onChange={(e) =>
            setForm({
              ...form,
              unit: e.target.value,
              unitLabel: e.target.value === "OTHER" ? "" : units[e.target.value],
            })
          }
        >
          {Object.entries(units).map(([k, v]) => (
            <option key={k} value={k}>
              {v}
            </option>
          ))}
        </select>
      </Field>
      {text("unitLabel", "Как показать единицу жителю", 60)}
      <Field label="Ставка, рублей за единицу">
        <input
          type="number"
          min="0"
          max="9999999999.999999"
          step="0.000001"
          required
          value={form.amount}
          onChange={(e) => setForm({ ...form, amount: e.target.value })}
        />
      </Field>
      {text("validFrom", "Начало действия", 30, "date")}
      {text("validUntil", "Окончание действия, если известно", 30, "date", false)}
      {area(
        "sourceReference",
        "Документ-основание: кем принят, номер, дата и применимый пункт",
        2000,
      )}
      {text("sourceUrl", "Ссылка на источник, если есть", 1500, "url", false)}
      {area("explanation", "Понятное пояснение для жителей: что действует или меняется", 3000)}
      {area("reason", "Причина внесения / изменения — для истории", 1000)}
      <p className="muted">
        Сохраняется черновик. Документ можно приложить после сохранения. Публикацию проверяет другой
        сотрудник с отдельным полномочием.
      </p>
      <div className="case-actions">
        <button disabled={busy}>Сохранить черновик</button>
        <button type="button" className="case-secondary" disabled={busy} onClick={onCancel}>
          Отмена
        </button>
      </div>
    </form>
  );
}
async function download(file, role, headers) {
  const response = await fetch(`/v1/tariff-documents/${file.id}`, { headers: headers(role) });
  if (!response.ok) throw new Error("Документ недоступен. Обновите сведения и проверьте доступ.");
  const blob = URL.createObjectURL(await response.blob()),
    a = document.createElement("a");
  a.href = blob;
  a.download =
    file.title +
    ({ "application/pdf": ".pdf", "image/png": ".png", "image/jpeg": ".jpg" }[file.mime] || "");
  a.click();
  setTimeout(() => URL.revokeObjectURL(blob), 30000);
}
function Source({ rate, role, headers, onError }) {
  return (
    <>
      <p>
        <strong>Основание:</strong> {rate.sourceReference}
      </p>
      {rate.sourceUrl && (
        <a className="case-link" href={rate.sourceUrl} target="_blank" rel="noopener noreferrer">
          Открыть источник
        </a>
      )}
      <div className="case-actions">
        {rate.files.map((f) => (
          <button
            key={f.id}
            type="button"
            className="case-link"
            onClick={() => download(f, role, headers).catch((e) => onError(e.message))}
          >
            Скачать: {f.title}
          </button>
        ))}
      </div>
    </>
  );
}
function RateCard({ rate, role, headers, onError, children }) {
  return (
    <article className="tariff-card">
      <span className={`tariff-status tariff-${rate.state.toLowerCase()}`}>
        {states[rate.state]}
      </span>
      <h3>{rate.title}</h3>
      <p className="tariff-price">
        {amount(rate.amount)} ₽ <span>/ {rate.unitLabel}</span>
      </p>
      <p>
        {rate.provider}
        <br />
        <strong>Применяется:</strong> {rate.applicability}
      </p>
      <p>
        С {date(rate.validFrom)}
        {rate.effectiveUntil ? ` по ${date(rate.effectiveUntil)}` : " · дата окончания не указана"}
      </p>
      <p>{rate.explanation}</p>
      <Source rate={rate} role={role} headers={headers} onError={onError} />
      {children}
    </article>
  );
}
function Upload({ rate, busy, onUpload }) {
  const [file, setFile] = useState(null),
    [title, setTitle] = useState("");
  return (
    <details>
      <summary>Добавить общедомовой документ-основание</summary>
      <form
        className="tariff-form"
        onSubmit={(e) => {
          e.preventDefault();
          const body = new FormData();
          body.append("file", file);
          body.append("title", title);
          body.append("revision", rate.revision);
          onUpload(body);
        }}
      >
        <Field label="Название документа">
          <input
            required
            maxLength={160}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
        </Field>
        <Field label="PDF, JPEG или PNG, до 10 МБ">
          <input
            type="file"
            accept="application/pdf,image/jpeg,image/png"
            required
            onChange={(e) => setFile(e.target.files[0])}
          />
        </Field>
        <label className="tariff-check">
          <input type="checkbox" required />
          Подтверждаю: документ можно показать жителям дома, он не содержит личных квитанций и
          персональных данных
        </label>
        <button disabled={busy || !file}>Приложить к черновику</button>
      </form>
    </details>
  );
}
function Decision({ rate, mode, busy, onSubmit, onCancel }) {
  const [reason, setReason] = useState(""),
    [start, setStart] = useState(rate.validFrom),
    [correction, setCorrection] = useState(rate.state === "WITHDRAWN");
  return (
    <form
      className="tariff-form"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(
          mode === "copy"
            ? { revision: rate.revision, validFrom: start, correction, reason }
            : { revision: rate.revision, reason },
        );
      }}
    >
      <h3>
        {mode === "copy"
          ? "Создать новую версию"
          : mode === "publish"
            ? "Проверить и опубликовать"
            : "Отозвать публикацию"}
      </h3>
      {mode === "copy" && (
        <>
          <p>
            Документы будут скопированы в черновик. Проверьте их актуальность для новой ставки и при
            необходимости замените.
          </p>
          <Field label="Назначение версии">
            <select
              disabled={rate.state === "WITHDRAWN"}
              value={String(correction)}
              onChange={(e) => {
                setCorrection(e.target.value === "true");
                setStart(rate.validFrom);
              }}
            >
              <option value="false">Ставка на новый период</option>
              <option value="true">Исправление исходной публикации</option>
            </select>
          </Field>
          <Field label="Начало периода">
            <input
              type="date"
              required
              readOnly={correction}
              min={correction ? undefined : rate.validFrom}
              value={start}
              onChange={(e) => setStart(e.target.value)}
            />
          </Field>
        </>
      )}
      <Field
        label={
          mode === "publish"
            ? "Что проверено и на каком основании"
            : "Причина — сохранится в истории"
        }
      >
        <textarea
          required
          maxLength={1000}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
      </Field>
      {mode === "publish" && (
        <label className="tariff-check">
          <input type="checkbox" required />Я проверил ставку, единицы, применимость, период и
          документы. Сведения и приложенные документы можно показать жителям дома.
        </label>
      )}
      {mode === "withdraw" && (
        <p>
          Запись останется в истории с причиной отзыва. Предыдущая ставка автоматически не
          восстановится.
        </p>
      )}
      <div className="case-actions">
        <button disabled={busy}>
          {mode === "copy"
            ? "Создать черновик"
            : mode === "publish"
              ? "Опубликовать для жителей"
              : "Подтвердить отзыв"}
        </button>
        <button type="button" className="case-secondary" onClick={onCancel} disabled={busy}>
          Отмена
        </button>
      </div>
    </form>
  );
}
function PermissionPanel({ staff, busy, onGrant }) {
  const [reason, setReason] = useState("");
  return (
    <details className="tariff-permissions">
      <summary>Полномочия на подготовку и публикацию</summary>
      <p>
        Это доступ только к общедомовым ставкам. Личные квитанции он не открывает. Подготовку и
        проверку одной версии выполняют разные сотрудники.
      </p>
      <Field label="Основание назначения или отзыва">
        <textarea maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />
      </Field>
      {staff.map((s) => (
        <div className="tariff-person" key={s.userId}>
          <strong>
            {s.name}
            {!s.active && " · доступ к дому не действует"}
          </strong>
          {["EDITOR", "PUBLISHER"].map((role) => (
            <div className="case-permission" key={role}>
              <span>
                {role === "EDITOR" ? "Подготовка сведений" : "Проверка и публикация"} ·{" "}
                {s.permissions.includes(role) ? "выдано" : "не выдано"}
              </span>
              <button
                type="button"
                className="case-secondary"
                disabled={busy || !reason.trim() || (!s.active && !s.permissions.includes(role))}
                onClick={() =>
                  onGrant(s.userId, { role, enabled: !s.permissions.includes(role), reason })
                }
              >
                {s.permissions.includes(role) ? "Отозвать" : "Назначить"}
              </button>
            </div>
          ))}
        </div>
      ))}
    </details>
  );
}
export function HouseTariffs({ houseId, role, api, headers, onQuestion, optional = false }) {
  const [catalogue, setCatalogue] = useState(null),
    [rights, setRights] = useState(null),
    [registry, setRegistry] = useState([]),
    [staff, setStaff] = useState([]),
    [selectedDate, setDate] = useState(day()),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [edit, setEdit] = useState(null),
    [decision, setDecision] = useState(null),
    [loaded, setLoaded] = useState(false),
    [unavailable, setUnavailable] = useState(false);
  const base = `/v1/houses/${houseId}/tariffs`;
  async function load() {
    const r = await api(role, base + "/access"),
      c = await api(role, base + `?date=${selectedDate}`);
    const [list, people] = await Promise.all([
      r.canManage || r.canEdit || r.canPublish ? api(role, base + "/registry") : [],
      r.canManage ? api(role, base + "/staff") : [],
    ]);
    return { r, c, list, people };
  }
  function apply({ r, c, list, people }) {
    setRights(r);
    setCatalogue(c);
    setRegistry(list);
    setStaff(people);
  }
  useEffect(() => {
    let active = true;
    setCatalogue(null);
    setEdit(null);
    setDecision(null);
    setNotice("");
    setLoaded(false);
    setUnavailable(false);
    load()
      .then((result) => {
        if (active) apply(result);
      })
      .catch((e) => {
        if (active) {
          setNotice(e.message);
          if (optional && e.status === 404) setUnavailable(true);
        }
      })
      .finally(() => {
        if (active) setLoaded(true);
      });
    return () => {
      active = false;
    };
  }, [houseId, role, selectedDate]);
  async function run(path, options) {
    setBusy(true);
    setNotice("");
    try {
      const result = await api(role, path, options);
      setEdit(null);
      setDecision(null);
      apply(await load());
      setNotice("Сведения сохранены.");
      return result;
    } catch (e) {
      setNotice(e.message);
      return null;
    } finally {
      setBusy(false);
    }
  }
  const json = (path, method, body) => run(path, { method, body: JSON.stringify(body) });
  if (unavailable) return null;
  return (
    <details className="case-panel case-workspace tariff-section">
      <summary>Платежи и тарифы</summary>
      <p>
        Проверенные ставки вашего дома, периоды действия и документы-основания. Это справочные
        сведения: личная сумма зависит от объёма и условий начисления.
      </p>
      <p className="case-notice" role="status">
        {notice}
      </p>
      <PaymentComparison
        key={houseId}
        houseId={houseId}
        role={role}
        api={api}
        onQuestion={onQuestion}
      />
      <div className="tariff-toolbar">
        <Field label="Показать ставки на дату">
          <input
            type="date"
            required
            disabled={busy}
            value={selectedDate}
            onChange={(e) => {
              if (e.target.value) setDate(e.target.value);
            }}
          />
        </Field>
        <button
          type="button"
          className="case-secondary"
          disabled={busy}
          onClick={() => {
            load()
              .then(apply)
              .catch((e) => setNotice(e.message));
          }}
        >
          Обновить
        </button>
      </div>
      {!loaded && <p>Загружаем сведения…</p>}
      {catalogue && (
        <>
          {selectedDate !== day() && (
            <p className="tariff-date-note">Вы смотрите сведения на {date(selectedDate)}.</p>
          )}
          {!catalogue.rates.length && (
            <p className="tariff-empty">
              На выбранную дату проверенные ставки пока не опубликованы. Это не означает, что плата
              отсутствует. Можно задать вопрос сотруднику.
            </p>
          )}
          <div className="tariff-grid">
            {catalogue.rates.map((r) => (
              <RateCard key={r.id} rate={r} role={role} headers={headers} onError={setNotice} />
            ))}
          </div>
          {!!catalogue.upcoming.length && (
            <details>
              <summary>Предстоящие изменения · {catalogue.upcoming.length}</summary>
              {catalogue.upcoming.map((c, i) => (
                <p key={`${c.seriesId}:${c.validFrom}:${i}`}>
                  <strong>
                    {c.title} · с {date(c.validFrom)}
                  </strong>
                  <br />
                  {c.previousAmount != null ? `${amount(c.previousAmount)} → ` : ""}
                  {amount(c.amount)} ₽ / {c.unitLabel}
                  <br />
                  {c.explanation}
                </p>
              ))}
            </details>
          )}
          {!!catalogue.history.length && (
            <details>
              <summary>
                История опубликованных ставок и документы · {catalogue.history.length}
              </summary>
              <div className="tariff-grid">
                {catalogue.history.map((r) => (
                  <RateCard key={r.id} rate={r} role={role} headers={headers} onError={setNotice}>
                    {r.history.map((h) => (
                      <p key={h.revision}>
                        <strong>{actions[h.action]}</strong> ·{" "}
                        {new Date(h.createdAt).toLocaleDateString("ru-RU")}
                        <br />
                        {h.reason}
                      </p>
                    ))}
                  </RateCard>
                ))}
              </div>
            </details>
          )}
        </>
      )}
      {onQuestion && (
        <button type="button" className="case-secondary" onClick={onQuestion}>
          Задать вопрос по квитанции
        </button>
      )}
      {rights && (rights.canManage || rights.canEdit || rights.canPublish) && (
        <details className="tariff-registry">
          <summary>Управление справочными ставками</summary>
          <p>
            Никакие суммы не подставляются автоматически. Укажите применимость и основание. Новый
            период завершает предыдущий период этой услуги накануне своей даты начала. Исправления
            сохраняют исходную публикацию в истории.
          </p>
          {rights.canManage && (
            <PermissionPanel
              staff={staff}
              busy={busy}
              onGrant={(user, r) => json(base + `/staff/${user}`, "PUT", r)}
            />
          )}
          {rights.canEdit && !edit && (
            <button
              disabled={busy}
              type="button"
              onClick={() => {
                setDecision(null);
                setEdit({});
              }}
            >
              Добавить услугу или плату
            </button>
          )}
          {edit && (
            <DraftForm
              key={`${edit.id || "new"}:${edit.revision || 0}`}
              initial={edit}
              busy={busy}
              onCancel={() => setEdit(null)}
              onSave={(body) =>
                json(
                  base + "/registry" + (edit.id ? `/${edit.id}` : ""),
                  edit.id ? "PUT" : "POST",
                  body,
                )
              }
            />
          )}
          <div className="tariff-grid">
            {registry.map((r) => (
              <RateCard key={r.id} rate={r} role={role} headers={headers} onError={setNotice}>
                <div className="case-actions">
                  {r.state === "DRAFT" && rights.canEdit && (
                    <button
                      type="button"
                      disabled={busy}
                      className="case-secondary"
                      onClick={() => {
                        setDecision(null);
                        setEdit(r);
                      }}
                    >
                      Изменить черновик
                    </button>
                  )}
                  {r.state === "DRAFT" && rights.canPublish && (
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => {
                        setEdit(null);
                        setDecision({ rate: r, mode: "publish" });
                      }}
                    >
                      Проверить и опубликовать
                    </button>
                  )}
                  {["PUBLISHED", "WITHDRAWN"].includes(r.state) && rights.canEdit && (
                    <button
                      type="button"
                      disabled={busy}
                      className="case-secondary"
                      onClick={() => {
                        setEdit(null);
                        setDecision({ rate: r, mode: "copy" });
                      }}
                    >
                      Новый период или исправление
                    </button>
                  )}
                  {r.state === "PUBLISHED" && rights.canPublish && (
                    <button
                      type="button"
                      disabled={busy}
                      className="case-secondary"
                      onClick={() => setDecision({ rate: r, mode: "withdraw" })}
                    >
                      Отозвать публикацию
                    </button>
                  )}
                </div>
                {decision?.rate.id === r.id && (
                  <Decision
                    key={`${r.id}:${decision.mode}`}
                    rate={r}
                    mode={decision.mode}
                    busy={busy}
                    onCancel={() => setDecision(null)}
                    onSubmit={async (body) => {
                      const result = await json(
                        `/v1/tariffs/${r.id}/${decision.mode}`,
                        "POST",
                        body,
                      );
                      if (result && decision.mode === "copy") setEdit(result);
                    }}
                  />
                )}
                {r.state === "DRAFT" && rights.canEdit && (
                  <>
                    <Upload
                      key={`${r.id}:${r.revision}`}
                      rate={r}
                      busy={busy}
                      onUpload={(body) =>
                        run(`/v1/tariffs/${r.id}/documents`, { method: "POST", body })
                      }
                    />
                    {r.files.map((f) => (
                      <button
                        key={f.id}
                        className="case-link"
                        type="button"
                        disabled={busy}
                        onClick={() =>
                          run(`/v1/tariffs/${r.id}/documents/${f.id}?revision=${r.revision}`, {
                            method: "DELETE",
                          })
                        }
                      >
                        Исключить из черновика: {f.title}
                      </button>
                    ))}
                  </>
                )}
                <details>
                  <summary>История подготовки · {r.history.length}</summary>
                  {r.history.map((h) => {
                    const snapshot = h.snapshot ? JSON.parse(h.snapshot) : null;
                    return (
                      <p key={h.revision}>
                        <strong>{actions[h.action]}</strong> · {h.actor}
                        <br />
                        {h.reason}
                        {snapshot && (
                          <>
                            <br />
                            {snapshot.title} · {amount(snapshot.amount)} ₽ / {snapshot.unit_label}
                            <br />С {date(snapshot.valid_from)}
                            <br />
                            {snapshot.source_reference}
                          </>
                        )}
                      </p>
                    );
                  })}
                </details>
              </RateCard>
            ))}
          </div>
        </details>
      )}
    </details>
  );
}
