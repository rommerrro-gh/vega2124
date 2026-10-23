import { Users, ArrowRight, UserCircle } from "@phosphor-icons/react";
import { AppearancePicker, ResidentHeader, ResidentNavigation, useAppearance } from "./design.jsx";
import { OperationsInbox } from "./operations.jsx";
import { ExchangePortal } from "./case-exchange.jsx";
import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { createPortal } from "react-dom";
import { MaxUI, Panel, Button as MaxButton } from "@maxhub/max-ui";
import "@maxhub/max-ui/dist/styles.css";
import "./theme.css";
import "./cases.css";
import "./workflow.css";
import { WorkPortal, WarrantyRegistry, WorkflowStaff } from "./warranty-workflow.jsx";
import { HouseTariffs } from "./house-tariffs.jsx";
import { CaseDetail, ResidentCases, StaffCases } from "./resident-cases.jsx";
import "./redesign.css";

function Button({ mode = "primary", className = "", ...props }) {
  return <MaxButton mode={mode} className={`app-button-${mode} ${className}`.trim()} {...props} />;
}

const candidateReasonLabels = {
  structured_location: "Место сравнивается по выбранным полям",
  same_lift_type: "Тот же тип лифта",
  lift_type_unspecified: "Тип лифта не уточнён — проверьте совпадение",
  whole_house_scope: "Общая проблема дома может включать ваше место",
  whole_entrance_scope: "Общая уборка подъезда может включать этот этаж",
  same_waste_site: "Та же мусорная площадка",
  outdoor_place_unspecified: "Место во дворе не уточнено — проверьте, что проблема та же",
  legacy_location: "В старой заявке место указано текстом — проверьте его",
  same_house: "Тот же дом",
  same_category: "Та же категория",
  same_location: "Совпадает место",
  same_entrance: "Тот же подъезд",
  same_floor: "Тот же этаж",
  elevator_floor_ignored: "Для лифта этаж может отличаться",
  floor_unspecified: "В одной заявке этаж не указан — проверьте место",
  same_outdoor_object: "Тот же тип объекта во дворе",
  check_outdoor_zone: "Проверьте, что это один объект и участок",
  location_unspecified: "Место не уточнено",
  recent_issue: "Недавняя заявка",
};
function candidateReason(reason) {
  return reason.startsWith("shared_terms:")
    ? `Общих слов: ${reason.split(":")[1]}`
    : candidateReasonLabels[reason];
}
const passportLabels = {
  management_company: "Управляющая организация",
  building_year: "Год постройки",
  floor_count: "Этажность",
  wall_material: "Материал стен",
  entrance_count: "Количество подъездов",
  apartment_count: "Количество квартир",
  total_area_sqm: "Площадь дома, м²",
  registered_residents_count: "Зарегистрировано жителей",
  capital_repairs: "Проведённый капитальный ремонт",
  emergency_contact: "Аварийный контакт",
};
const passportFieldOrder = Object.keys(passportLabels);

function passportValue(value) {
  if (value == null) return "Не указано";
  if (typeof value === "string" || typeof value === "number") return String(value);
  if (Array.isArray(value)) return value.map(passportValue).join(", ");
  return Object.entries(value)
    .map(([key, item]) => `${key}: ${passportValue(item)}`)
    .join(" · ");
}

function passportDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString("ru-RU");
}

function safeSourceUrl(value) {
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) ? url.href : null;
  } catch {
    return null;
  }
}

function authHeaders(role) {
  const initData = window.WebApp?.initData;
  return initData ? { "X-Max-Init-Data": initData } : { "X-Demo-Session": role };
}

async function api(role, path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      ...authHeaders(role),
      ...(options.body && !(options.body instanceof FormData)
        ? { "Content-Type": "application/json" }
        : {}),
    },
  });
  if (!response.ok) {
    if (response.status === 401)
      throw new Error(
        "Нет доступа. Откройте приложение в MAX. Для локального просмотра запустите сервер с профилем demo.",
      );
    const error = await response.json().catch(() => ({}));
    const failure = new Error(error.message || `Ошибка ${response.status}`);
    failure.status = response.status;
    throw failure;
  }
  if (response.status === 204) return null;
  const body = await response.text();
  return body ? JSON.parse(body) : null;
}

function navigate(path) {
  const initData = window.WebApp?.initData;
  const launchData =
    window.location.hash || (initData ? `#WebAppData=${encodeURIComponent(initData)}` : "");
  const pathname = new URL(path, location.origin).pathname;
  if (
    [
      "/miniapp/index.html",
      "/dispatcher/index.html",
      "/admin/index.html",
      "/uk/index.html",
      "/system/index.html",
      "/work/index.html",
      "/exchange/index.html",
    ].includes(pathname)
  ) {
    localStorage.setItem("pulse-last-mode", pathname);
  }
  window.location.assign(`${path}${launchData}`);
}

function refreshAccess(goHome = false) {
  if (goHome) localStorage.setItem("pulse-last-mode", "/miniapp/index.html");
  if (goHome && location.pathname !== "/miniapp/index.html") {
    history.replaceState(null, "", `/miniapp/index.html${location.hash}`);
  }
  window.dispatchEvent(new Event("pulse-access-changed"));
}

async function downloadAttachment(role, attachment, onError) {
  try {
    const response = await fetch(`/v1/attachments/${encodeURIComponent(attachment.id)}`, {
      headers: authHeaders(role),
    });
    if (!response.ok) throw new Error("Не удалось открыть вложение");
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = url;
    link.download =
      attachment.id +
      ({
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "application/pdf": ".pdf",
        "video/mp4": ".mp4",
      }[attachment.mime] || "");
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  } catch (error) {
    onError(error.message);
  }
}

const rolePages = [
  ["RESIDENT", "Житель", "/miniapp/index.html"],
  ["DISPATCHER", "Диспетчер", "/dispatcher/index.html"],
  ["HOUSE_ADMIN", "Администратор дома", "/admin/index.html"],
  ["UK_ADMIN", "Администратор УК", "/uk/index.html"],
  ["SYSTEM_ADMIN", "Системный администратор", "/system/index.html"],
];

function Brand({ role }) {
  const menu = useRef(null);
  const [access, setAccess] = useState([]);
  const [demo, setDemo] = useState(null);
  const [localDemo, setLocalDemo] = useState(false);
  const [workAccess, setWorkAccess] = useState(false);
  const [exchangeAccess, setExchangeAccess] = useState(false);
  const [notice, setNotice] = useState("");
  const [help, setHelp] = useState(false);
  useEffect(() => {
    Promise.all([
      api("true", "/v1/access/me"),
      api("true", "/v1/guided-demo"),
      api("true", "/v1/access/config"),
      api("true", "/v1/workflow/houses"),
      api("true", "/v1/exchange/inbox"),
    ])
      .then(([roles, state, config, workHouses, packets]) => {
        setAccess(roles);
        setDemo(state);
        setLocalDemo(config.demoMode === true);
        setWorkAccess(workHouses.length > 0);
        setExchangeAccess(packets.length > 0);
      })
      .catch(() => {});
  }, []);
  useEffect(() => {
    const closeOnEscape = (event) => {
      if (event.key === "Escape" && menu.current) menu.current.open = false;
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, []);
  const available = localDemo
    ? rolePages
    : rolePages.filter(([name]) => access.some((item) => item.role === name));
  const current = rolePages.find(([, , path]) => location.pathname === path);
  async function demoAction(action) {
    setNotice("");
    if (menu.current) menu.current.open = false;
    try {
      await api("true", `/v1/guided-demo/${action}`, { method: "POST" });
      refreshAccess(true);
    } catch (error) {
      setNotice(error.message);
    }
  }
  return (
    <>
      <header className="topbar">
        <div className="brand">
          <span className="brand-icon-frame">
            <img className="brand-icon" src="/miniapp/app-icon.png?v=20261023-design-a" alt="" />
          </span>
          <span>Пульс дома</span>
        </div>
        <OperationsInbox
          api={api}
          role={
            location.pathname === "/uk/index.html" || location.pathname === "/admin/index.html"
              ? "admin"
              : location.pathname === "/dispatcher/index.html"
                ? "dispatcher"
                : new URLSearchParams(location.search).get("demoSession") || "true"
          }
          onOpen={(n) =>
            navigate(
              location.pathname === "/miniapp/index.html"
                ? `/miniapp/index.html?view=resident&caseId=${encodeURIComponent(n.case_id)}`
                : `/work/index.html?houseId=${encodeURIComponent(n.house_id)}&caseId=${encodeURIComponent(n.case_id)}${localDemo ? "&demoSession=dispatcher" : ""}`,
            )
          }
        />
        {available.length || workAccess || exchangeAccess ? (
          <details ref={menu} className="role-menu">
            <summary aria-label="Выбрать роль">
              <UserCircle className="profile-icon" size={34} weight="regular" aria-hidden="true" />
              <span className="role-menu-label">{current?.[1] || role}</span>
            </summary>
            <button
              className="role-menu-backdrop"
              type="button"
              aria-label="Закрыть меню ролей"
              onClick={() => {
                menu.current.open = false;
              }}
            />
            <nav aria-label="Доступные роли">
              {available.map(([name, label, path]) => (
                <button
                  key={name}
                  type="button"
                  aria-current={location.pathname === path ? "page" : undefined}
                  onClick={() =>
                    navigate(path === "/miniapp/index.html" ? `${path}?view=resident` : path)
                  }
                >
                  {label}
                  {location.pathname === path ? " ✓" : ""}
                </button>
              ))}
              {localDemo && (
                <button
                  type="button"
                  onClick={() => navigate("/work/index.html?demoSession=engineer")}
                >
                  Инженер и исполнитель
                </button>
              )}
              {(workAccess || localDemo) && (
                <button
                  type="button"
                  onClick={() =>
                    navigate(
                      "/work/index.html" +
                        (localDemo
                          ? `?demoSession=${["/uk/index.html", "/admin/index.html"].includes(location.pathname) ? "admin" : "dispatcher"}`
                          : ""),
                    )
                  }
                >
                  Осмотры и работы
                </button>
              )}
              {(exchangeAccess || localDemo) && (
                <button
                  type="button"
                  onClick={() =>
                    navigate("/exchange/index.html" + (localDemo ? "?demoSession=recipient" : ""))
                  }
                >
                  Полученные материалы организаций
                </button>
              )}
              <hr />
              <AppearancePicker />
              {demo?.active && (
                <>
                  <hr />
                  <span className="role-menu-caption">
                    Демонстрационный режим · тестовые данные
                  </span>
                  <button
                    type="button"
                    onClick={() => {
                      menu.current.open = false;
                      setHelp((value) => !value);
                    }}
                  >
                    Инструкция по сценарию
                  </button>
                  <button type="button" onClick={() => demoAction("restart")}>
                    Начать заново
                  </button>
                  <button type="button" onClick={() => demoAction("exit")}>
                    Выйти из демонстрации
                  </button>
                </>
              )}
            </nav>
          </details>
        ) : (
          <span className="max-chip">
            <span className="max-dot" /> {role} · MAX
          </span>
        )}
      </header>
      {demo?.active && (
        <div className="guided-demo-badge">Демонстрационный режим · {demo.address}</div>
      )}
      {help && (
        <div className="guided-demo-help">
          <strong>Проверка сценария</strong>
          <ol>
            <li>
              В роли жителя переключите два дома: у них одна УК, но разные паспорта и опросы.
              Вернитесь в первый дом и создайте обращение «Не работает свет в подъезде», место
              «Подъезд 1, этаж 2».
            </li>
            <li>Присоединитесь к похожей заявке или создайте новую.</li>
            <li>Переключитесь в диспетчера и проведите заявку до выполнения.</li>
            <li>Проверьте сообщение от бота, вернитесь в роль жителя и подтвердите результат.</li>
          </ol>
        </div>
      )}
      {notice && (
        <div className="notice" role="alert">
          {notice}
        </div>
      )}
    </>
  );
}

function GuidedDemoEntry() {
  const [enabled, setEnabled] = useState(false);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  useEffect(() => {
    api("true", "/v1/guided-demo")
      .then((state) => setEnabled(state.enabled && !state.active))
      .catch(() => {});
  }, []);
  async function activate(value) {
    setBusy(true);
    setNotice("");
    try {
      await api("true", "/v1/guided-demo/activate", {
        method: "POST",
        body: JSON.stringify({ code: value }),
      });
      refreshAccess(true);
    } catch (error) {
      setNotice(error.message);
      setBusy(false);
    }
  }
  useEffect(() => {
    if (!enabled) return;
    const start =
      window.WebApp?.initDataUnsafe?.start_param ||
      new URLSearchParams(location.search).get("WebAppStartParam") ||
      "";
    if (start.startsWith("demo_") && start.length > 5) activate(start.slice(5));
  }, [enabled]);
  if (!enabled) return null;
  return (
    <Card className="guided-demo-entry">
      <SectionHeading
        title="Проверяете проект?"
        subtitle="Введите код: для вас подготовятся два тестовых дома одной УК внутри MAX."
      />
      <form
        onSubmit={(event) => {
          event.preventDefault();
          activate(code);
        }}
      >
        <Field id="demo-code" label="Код демонстрации">
          <input
            id="demo-code"
            value={code}
            maxLength="128"
            autoComplete="off"
            required
            onChange={(event) => setCode(event.target.value)}
          />
        </Field>
        <Button mode="primary" type="submit" disabled={busy}>
          Запустить демонстрацию
        </Button>
      </form>
      {notice && <Notice message={notice} />}
    </Card>
  );
}

function Hero({ dispatcher = false }) {
  return (
    <section className="hero" aria-labelledby="page-title">
      <div className="hero-copy">
        <div className="hero-kicker">
          <span className="live-dot" /> {dispatcher ? "Кабинет диспетчера" : "Сервис вашего дома"}
        </div>
        <h1 id="page-title">
          {dispatcher ? (
            <>
              Все обращения
              <br />
              под контролем
            </>
          ) : (
            <>
              Дом становится
              <br />
              лучше с вами
            </>
          )}
        </h1>
        <p>
          {dispatcher
            ? "Принимайте заявки жителей и ведите их до решения в одном месте."
            : "Опишите, что случилось или что непонятно в квитанции. Мы поможем разобраться."}
        </p>
      </div>
    </section>
  );
}

function Card({ children, className = "" }) {
  return (
    <section className={`card ${className}`}>
      <Panel mode="secondary" className="card-panel">
        {children}
      </Panel>
    </section>
  );
}

function DisclosureCard({ title, summary, children, className = "" }) {
  return (
    <Card className={`disclosure-card ${className}`}>
      <details>
        <summary>
          <span>
            <strong>{title}</strong>
            <small>{summary}</small>
          </span>
          <span className="disclosure-arrow" aria-hidden="true" />
        </summary>
        <div className="disclosure-content">{children}</div>
      </details>
    </Card>
  );
}

function SectionHeading({ number, title, subtitle }) {
  return (
    <div className="section-heading">
      {number && <span className="section-number">{number}</span>}
      <div>
        <h2>{title}</h2>
        <p>{subtitle}</p>
      </div>
    </div>
  );
}

function countLabel(count, one, few, many) {
  const mod100 = count % 100;
  const mod10 = count % 10;
  return `${count} ${mod100 >= 11 && mod100 <= 14 ? many : mod10 === 1 ? one : mod10 >= 2 && mod10 <= 4 ? few : many}`;
}

function Field({ id, label, children }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children}
    </div>
  );
}

function Notice({ message }) {
  return message ? (
    <div
      className={`notice${message.startsWith("Нет доступа.") ? " notice-error" : ""}`}
      role="alert"
    >
      {message}
    </div>
  ) : null;
}

function InvitationToken({ token, role = "admin" }) {
  const [username, setUsername] = useState("");
  useEffect(() => {
    api(role, "/v1/access/config")
      .then((config) => setUsername(config.botUsername || ""))
      .catch(() => {});
  }, []);
  if (!token) return null;
  const link = username ? `https://max.ru/${username}?startapp=${encodeURIComponent(token)}` : "";
  return (
    <div className="invite-token" role="status">
      <strong>Приглашение создано</strong>
      {link ? (
        <>
          <code>{link}</code>
          <Button
            mode="secondary"
            type="button"
            onClick={() => navigator.clipboard.writeText(link)}
          >
            Скопировать ссылку
          </Button>
        </>
      ) : (
        <>
          <code>{token}</code>
          <Button
            mode="secondary"
            type="button"
            onClick={() => navigator.clipboard.writeText(token)}
          >
            Скопировать токен
          </Button>
          <p>
            Укажите MAX_BOT_USERNAME на стенде, чтобы здесь появилась готовая ссылка. Пока добавьте
            токен к ссылке бота как параметр startapp.
          </p>
        </>
      )}
      <p>
        Сохраните ссылку сейчас: из соображений безопасности она показывается один раз. Если
        потеряли её, отзовите приглашение и создайте новое.
      </p>
    </div>
  );
}

function HousePassport({ passport, loading, error }) {
  const summary = loading
    ? "Загружаем сведения…"
    : error
      ? "Не удалось загрузить сведения"
      : passport
        ? `${countLabel(passport.fields.length, "поле", "поля", "полей")} · ${countLabel(passport.contacts?.length || 0, "контакт", "контакта", "контактов")}`
        : "Сведения о доме";
  const known = new Map(passport?.fields.map((field) => [field.key, field]) || []);
  const displayFields = passport
    ? [
        ...passportFieldOrder.map((key) => known.get(key) || { key, value: null }),
        ...passport.fields.filter((field) => !passportFieldOrder.includes(field.key)),
      ]
    : [];
  return (
    <DisclosureCard className="passport-card" title="Паспорт дома" summary={summary}>
      <p className="muted">
        Сведения о доме. Для заполненных полей указаны источник и дата получения.
      </p>
      {loading ? (
        <p className="muted">Загружаем сведения о доме…</p>
      ) : error ? (
        <p className="notice" role="alert">
          {error}
        </p>
      ) : (
        passport && (
          <>
            <h3 className="passport-address">{passport.address}</h3>
            <div className="passport-fields">
              {displayFields.map((field) => {
                const sourceUrl = safeSourceUrl(field.sourceUrl);
                return (
                  <div className="passport-field" key={field.key}>
                    <div className="passport-field-label">
                      {passportLabels[field.key] || field.key.replaceAll("_", " ")}
                    </div>
                    <div className="passport-field-value">{passportValue(field.value)}</div>
                    {field.source && (
                      <div className="passport-field-source">
                        Источник:{" "}
                        {sourceUrl ? (
                          <a href={sourceUrl} target="_blank" rel="noopener noreferrer">
                            {field.source}
                          </a>
                        ) : (
                          field.source
                        )}{" "}
                        · получено {passportDate(field.fetchedAt)}
                        {field.validAt && ` · актуально на ${passportDate(field.validAt)}`}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            {!!passport.contacts?.length && (
              <div className="passport-contacts">
                <h4>Контакты дома</h4>
                <div className="passport-fields">
                  {passport.contacts.map((contact) => (
                    <div className="passport-field" key={contact.id}>
                      <div className="passport-field-label">
                        {contact.type === "EMERGENCY" ? "Аварийный контакт" : "Местный контакт"}
                      </div>
                      <div className="passport-field-value">{contact.title}</div>
                      <a
                        className="passport-phone"
                        href={`tel:${contact.phone.replace(/[^+0-9]/g, "")}`}
                      >
                        {contact.phone}
                      </a>
                      {contact.details && <p className="passport-details">{contact.details}</p>}
                      <div className="passport-field-source">
                        Источник: администратор дома · обновлено {passportDate(contact.updatedAt)}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        )
      )}
    </DisclosureCard>
  );
}

function PollCard({ poll, onVote, busy }) {
  const [optionId, setOptionId] = useState("");
  const totalVotes = poll.resultsVisible
    ? poll.options.reduce((total, option) => total + option.votes, 0)
    : 0;
  return (
    <div className="poll-card">
      <div className="poll-heading">
        <strong>{poll.question}</strong>
        <span>{poll.closed ? "Завершён" : `До ${passportDate(poll.closesAt)}`}</span>
      </div>
      <p className="hint">
        Предварительный опрос жителей · не является официальным голосованием собственников
      </p>
      <div className="poll-options">
        {poll.options.map((option) => (
          <label
            key={option.id}
            className={`poll-option ${!poll.myOptionId && !poll.closed && onVote ? "poll-option-selectable" : ""} ${poll.myOptionId === option.id ? "poll-option-selected" : ""}`}
          >
            {!poll.myOptionId && !poll.closed && onVote && (
              <input
                type="radio"
                name={`poll-${poll.id}`}
                value={option.id}
                checked={optionId === option.id}
                onChange={() => setOptionId(option.id)}
              />
            )}
            <span className="poll-option-content">
              <span className="poll-option-top">
                <span>
                  {option.label}
                  {poll.myOptionId === option.id ? " · ваш выбор" : ""}
                </span>
                {poll.resultsVisible && (
                  <strong>{totalVotes ? Math.round((option.votes * 100) / totalVotes) : 0}%</strong>
                )}
              </span>
              {poll.resultsVisible && (
                <span
                  className="poll-progress"
                  role="img"
                  aria-label={`${option.votes} голосов из ${totalVotes}`}
                >
                  <span
                    style={{ width: `${totalVotes ? (option.votes * 100) / totalVotes : 0}%` }}
                  />
                </span>
              )}
            </span>
          </label>
        ))}
      </div>
      {poll.resultsVisible && <p className="poll-total">Проголосовали: {totalVotes}</p>}
      {!poll.resultsVisible && <p className="hint">Итоги появятся после закрытия опроса.</p>}
      {onVote && !poll.myOptionId && !poll.closed && (
        <Button
          mode="primary"
          type="button"
          disabled={!optionId || busy}
          onClick={() => onVote(poll.id, optionId)}
        >
          Проголосовать
        </Button>
      )}
    </div>
  );
}

function Resident() {
  const [initialLoading, setInitialLoading] = useState(true);
  const [houses, setHouses] = useState([]);
  const [selectedHouseId, setSelectedHouseId] = useState("");
  const [initialCaseId, setInitialCaseId] = useState(null);
  const [passport, setPassport] = useState(null);
  const [passportLoading, setPassportLoading] = useState(false);
  const [passportError, setPassportError] = useState("");
  const [polls, setPolls] = useState([]);
  const [residentView, setResidentView] = useState(
    new URLSearchParams(location.search).has("caseId") ? "cases" : "home",
  );
  function changeResidentView(next) {
    setResidentView(next);
    window.dispatchEvent(new CustomEvent("pulse-resident-navigation", { detail: next }));
    requestAnimationFrame(() => {
      window.scrollTo({ top: 0, behavior: "auto" });
      if (next === "events")
        document
          .querySelector(".resident-events .disclosure-card details")
          ?.setAttribute("open", "");
    });
  }
  const [homePollOpen, setHomePollOpen] = useState(false);
  const [homeUtilitiesOpen, setHomeUtilitiesOpen] = useState(false);
  const [pollBusy, setPollBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const demoSession =
    new URLSearchParams(location.search).get("demoSession") === "admin" ? "admin" : "true";
  const request = (path, options) => api(demoSession, path, options);
  const fail = (error) => setNotice(error.message || String(error));

  useEffect(() => {
    async function load() {
      const [myHouses, roles, selected] = await Promise.all([
        request("/v1/me/houses"),
        request("/v1/access/me"),
        request("/v1/me/active-house"),
      ]);
      setHouses(myHouses);
      const launch = new URLSearchParams(location.search).get("caseId")
        ? `case_${new URLSearchParams(location.search).get("caseId")}`
        : window.WebApp?.initDataUnsafe?.start_param ||
          new URLSearchParams(location.search).get("WebAppStartParam") ||
          "";
      const match = typeof launch === "string" && /^case_([0-9a-f-]{36})$/.exec(launch);
      let linkedHouse = null;
      if (match) {
        try {
          // A launch parameter selects a view; only the authenticated API grants access.
          const linked = await request(`/v1/cases/${match[1]}`);
          if (linked.isAuthor && myHouses.some((h) => h.id === linked.houseId)) {
            linkedHouse = linked.houseId;
            setInitialCaseId(linked.id);
          } else setNotice("Обращение недоступно для этого профиля.");
        } catch (error) {
          setNotice(error.message);
        }
      }
      if (myHouses.length)
        setSelectedHouseId(
          linkedHouse ||
            myHouses.find((house) => house.id === selected.houseId)?.id ||
            myHouses[0].id,
        );
      if (!roles.length)
        setNotice("Профиль создан. Чтобы получить доступ, откройте приглашение от администратора.");
    }
    load()
      .catch(fail)
      .finally(() => setInitialLoading(false));
  }, []);

  useEffect(() => {
    if (!selectedHouseId) return;
    let active = true;
    const membership = houses.find((house) => house.id === selectedHouseId)?.memberships || [];
    const canVote = membership.some(
      (access) => access.role === "RESIDENT" && access.verificationStatus === "VERIFIED",
    );
    setPassport(null);
    setPolls([]);
    setPassportError("");
    setPassportLoading(true);
    Promise.all([
      request(`/v1/houses/${encodeURIComponent(selectedHouseId)}`),
      canVote
        ? request(`/v1/houses/${encodeURIComponent(selectedHouseId)}/polls`)
        : Promise.resolve([]),
    ])
      .then(([data, housePolls]) => {
        if (active) {
          setPassport(data);
          setPolls(housePolls);
        }
      })
      .catch((error) => {
        if (active) setPassportError(error.message || String(error));
      })
      .finally(() => {
        if (active) setPassportLoading(false);
      });
    return () => {
      active = false;
    };
  }, [selectedHouseId, houses]);

  async function voteInPoll(pollId, optionId) {
    setPollBusy(true);
    setNotice("");
    try {
      await request(
        `/v1/houses/${encodeURIComponent(selectedHouseId)}/polls/${encodeURIComponent(pollId)}/votes`,
        { method: "POST", body: JSON.stringify({ optionId }) },
      );
      setPolls(await request(`/v1/houses/${encodeURIComponent(selectedHouseId)}/polls`));
    } catch (error) {
      fail(error);
    } finally {
      setPollBusy(false);
    }
  }

  async function refreshPolls() {
    if (!selectedHouseId) return;
    setPollBusy(true);
    setNotice("");
    try {
      setPolls(await request(`/v1/houses/${encodeURIComponent(selectedHouseId)}/polls`));
    } catch (error) {
      fail(error);
    } finally {
      setPollBusy(false);
    }
  }

  const selectedHouse = houses.find((house) => house.id === selectedHouseId);
  const canReport = selectedHouse?.memberships?.some(
    (item) => item.role === "RESIDENT" && item.verificationStatus === "VERIFIED",
  );
  const openPolls = polls.filter((poll) => !poll.closed && !poll.myOptionId);
  if (initialLoading)
    return (
      <main className="shell resident-shell">
        <Brand role="Жителю" />
        <ResidentHeader loading />
        <Card className="loading-card">
          <p role="status">Загружаем данные вашего дома…</p>
        </Card>
      </main>
    );
  return (
    <main className={`shell resident-shell resident-view-${residentView}`}>
      <Brand role="Жителю" />
      <Notice message={notice} />
      <GuidedDemoEntry />
      <ResidentHeader
        house={selectedHouse}
        houses={houses}
        onChange={(id) => {
          setSelectedHouseId(id);
          request("/v1/me/active-house", {
            method: "PUT",
            body: JSON.stringify({ houseId: id }),
          }).catch(fail);
        }}
      />

      {selectedHouse && (
        <ResidentCases
          key={`${demoSession}:${selectedHouseId}`}
          house={selectedHouse}
          role={demoSession}
          api={api}
          headers={authHeaders}
          canSubmit={canReport}
          view={residentView}
          onShowCases={() => changeResidentView("cases")}
          initialCaseId={initialCaseId}
          passport={passport}
          candidateReason={candidateReason}
        />
      )}

      <div hidden={residentView !== "home" || !homeUtilitiesOpen} className="resident-tariffs">
        {selectedHouse && (
          <HouseTariffs
            key={`tariffs:${demoSession}:${selectedHouseId}`}
            houseId={selectedHouseId}
            role={demoSession}
            api={api}
            headers={authHeaders}
            onQuestion={
              canReport
                ? (text) => {
                    if (typeof text === "string")
                      window.dispatchEvent(
                        new CustomEvent("pulse-payment-question", {
                          detail: { houseId: selectedHouseId, text },
                        }),
                      );
                    else document.getElementById("resident-payment-start")?.click();
                  }
                : undefined
            }
          />
        )}
      </div>
      <div className="content-grid resident-events">
        {canReport && residentView === "home" && (
          <section className="home-poll">
            <button
              type="button"
              className="home-poll-row"
              aria-expanded={homePollOpen}
              onClick={() => setHomePollOpen((v) => !v)}
            >
              <Users size={30} />
              <span>
                <strong>Опрос жителей</strong>
                <small>{(openPolls[0] || polls[0])?.question || "Пока нет активных опросов"}</small>
              </span>
              <ArrowRight size={21} />
            </button>
            {homePollOpen && (
              <div className="poll-list">
                {openPolls[0] || polls[0] ? (
                  <PollCard poll={openPolls[0] || polls[0]} onVote={voteInPoll} busy={pollBusy} />
                ) : (
                  <p className="muted">Когда появится опрос, он будет здесь.</p>
                )}
              </div>
            )}
          </section>
        )}
        {canReport && (
          <div hidden={residentView !== "events"}>
            <DisclosureCard
              title="Опросы дома"
              summary={
                openPolls.length
                  ? `${countLabel(openPolls.length, "опрос ждёт", "опроса ждут", "опросов ждут")} вашего ответа`
                  : polls.length
                    ? countLabel(polls.length, "опрос", "опроса", "опросов")
                    : "Опросов пока нет"
              }
            >
              <div className="poll-list">
                {polls.map((poll) => (
                  <PollCard key={poll.id} poll={poll} onVote={voteInPoll} busy={pollBusy} />
                ))}
                {!polls.length && <p className="muted">В выбранном доме пока нет опросов.</p>}
                <Button mode="secondary" type="button" disabled={pollBusy} onClick={refreshPolls}>
                  Обновить опросы
                </Button>
              </div>
            </DisclosureCard>
          </div>
        )}
        {selectedHouse && residentView === "home" && (
          <button
            type="button"
            className="home-information-toggle"
            aria-expanded={homeUtilitiesOpen}
            onClick={() => setHomeUtilitiesOpen((v) => !v)}
          >
            Платежи, тарифы и информация о доме
          </button>
        )}
        {selectedHouse && (
          <div hidden={residentView !== "home" || !homeUtilitiesOpen}>
            <DisclosureCard title="Мой дом" summary="Паспорт и полезные контакты">
              <HousePassport passport={passport} loading={passportLoading} error={passportError} />
            </DisclosureCard>
          </div>
        )}
      </div>
      <footer>
        Пульс дома <span>·</span> Сделаем дом лучше вместе
      </footer>
      <ResidentNavigation value={residentView} onChange={changeResidentView} />
    </main>
  );
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
  const [pollClose, setPollClose] = useState(() =>
    new Date(Date.now() + 7 * 86400000 - new Date().getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 16),
  );
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
  const fail = (error) => setNotice(error.message || String(error));
  const base = `/v1/houses/${encodeURIComponent(houseId)}`;

  useEffect(() => {
    Promise.all([request("/v1/me/houses"), request("/v1/access/me")])
      .then(([list, access]) => {
        setCurrentUserId(access[0]?.userId || null);
        const allowed = list.filter((house) =>
          house.memberships?.some(
            (access) => access.role === "HOUSE_ADMIN" && access.verificationStatus === "VERIFIED",
          ),
        );
        setHouses(allowed);
        if (allowed.length) setHouseId(allowed[0].id);
        else setNotice("У вас нет подтверждённой роли администратора дома.");
      })
      .catch(fail);
  }, []);
  useEffect(() => {
    if (!houseId) return;
    setCreatedToken("");
    setNotice("");
    Promise.all([
      request(`${base}/contacts`),
      request("/v1/access/invitations"),
      request(`${base}/polls`),
      request(`/v1/access/houses/${houseId}/residents`),
    ])
      .then(([nextContacts, nextInvitations, nextPolls, nextResidents]) => {
        setContacts(nextContacts);
        setInvitations(nextInvitations);
        setPolls(nextPolls);
        setResidents(nextResidents);
      })
      .catch(fail);
  }, [houseId]);

  function resetContact() {
    setEditingId("");
    setContactType("EMERGENCY");
    setTitle("");
    setPhone("");
    setDetails("");
  }
  async function saveContact(event) {
    event.preventDefault();
    setBusy(true);
    setNotice("");
    try {
      const path = editingId
        ? `${base}/contacts/${encodeURIComponent(editingId)}`
        : `${base}/contacts`;
      await request(path, {
        method: editingId ? "PUT" : "POST",
        body: JSON.stringify({
          type: contactType,
          title: title.trim(),
          phone: phone.trim(),
          details: details.trim(),
        }),
      });
      setContacts(await request(`${base}/contacts`));
      resetContact();
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  async function removeContact(id) {
    if (!window.confirm("Удалить этот контакт?")) return;
    setBusy(true);
    setNotice("");
    try {
      await request(`${base}/contacts/${encodeURIComponent(id)}`, { method: "DELETE" });
      setContacts(await request(`${base}/contacts`));
      if (editingId === id) resetContact();
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  async function createInvitation(event) {
    event.preventDefault();
    setBusy(true);
    setNotice("");
    setCreatedToken("");
    try {
      const result = await request("/v1/access/invitations", {
        method: "POST",
        body: JSON.stringify({
          role: "RESIDENT",
          houseIds: [houseId],
          days: Number(days),
          activationLimit: Number(activationLimit),
        }),
      });
      setCreatedToken(result.token);
      setInvitations(await request("/v1/access/invitations"));
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  async function revokeInvitation(id) {
    setBusy(true);
    setNotice("");
    try {
      await request(`/v1/access/invitations/${encodeURIComponent(id)}/revoke`, { method: "POST" });
      setInvitations(await request("/v1/access/invitations"));
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  async function revokeResident(userId) {
    setBusy(true);
    setNotice("");
    try {
      await request("/v1/access/assignments/revoke", {
        method: "POST",
        body: JSON.stringify({ role: "RESIDENT", userId, houseId }),
      });
      setResidents(await request(`/v1/access/houses/${houseId}/residents`));
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  async function createPoll(event) {
    event.preventDefault();
    setBusy(true);
    setNotice("");
    try {
      const options = pollOptions.map((value) => value.trim());
      await request(`${base}/polls`, {
        method: "POST",
        body: JSON.stringify({
          question: pollQuestion.trim(),
          options,
          closesAt: new Date(pollClose).toISOString(),
          resultsHiddenUntilClose: hidePollResults,
        }),
      });
      setPolls(await request(`${base}/polls`));
      setPollQuestion("");
      setPollOptions(["", ""]);
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="shell">
      <Brand role="Администратору" />
      <section className="hero">
        <div className="hero-copy">
          <div className="hero-kicker">
            <span className="live-dot" /> Кабинет дома
          </div>
          <h1>
            Информация
            <br />
            для жителей
          </h1>
          <p>Поддерживайте контакты дома и приглашайте новых участников.</p>
        </div>
      </section>
      <Notice message={notice} />
      {!!houses.length && (
        <div className="content-grid">
          <Card>
            <SectionHeading
              number="01"
              title="Дом"
              subtitle="Изменения доступны только для выбранного дома"
            />
            <Field id="admin-house" label="Администрируемый дом">
              <select
                id="admin-house"
                value={houseId}
                onChange={(event) => {
                  setHouseId(event.target.value);
                  resetContact();
                }}
              >
                {houses.map((house) => (
                  <option key={house.id} value={house.id}>
                    {house.address}
                  </option>
                ))}
              </select>
            </Field>
          </Card>
          <Card>
            <SectionHeading
              number="02"
              title="Контакты"
              subtitle="Локальные сведения с датой изменения в паспорте дома"
            />
            <div className="item-list">
              {contacts.map((contact) => (
                <div className="admin-row" key={contact.id}>
                  <div>
                    <strong>{contact.title}</strong>
                    <small>
                      {contact.type === "EMERGENCY" ? "Аварийный" : "Местный"} · {contact.phone}
                    </small>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setEditingId(contact.id);
                      setContactType(contact.type);
                      setTitle(contact.title);
                      setPhone(contact.phone);
                      setDetails(contact.details || "");
                    }}
                  >
                    Изменить
                  </button>
                  <button type="button" disabled={busy} onClick={() => removeContact(contact.id)}>
                    Удалить
                  </button>
                </div>
              ))}
            </div>
            <form onSubmit={saveContact} className="admin-form">
              <h3>{editingId ? "Изменить контакт" : "Добавить контакт"}</h3>
              <p className="hint">Указывайте только публичные телефоны служб дома.</p>
              <Field id="contact-type" label="Тип">
                <select
                  id="contact-type"
                  value={contactType}
                  onChange={(event) => setContactType(event.target.value)}
                >
                  <option value="EMERGENCY">Аварийный</option>
                  <option value="LOCAL">Местный</option>
                </select>
              </Field>
              <Field id="contact-title" label="Название">
                <input
                  id="contact-title"
                  value={title}
                  onChange={(event) => setTitle(event.target.value)}
                  maxLength="120"
                  required
                />
              </Field>
              <Field id="contact-phone" label="Телефон">
                <input
                  id="contact-phone"
                  type="tel"
                  value={phone}
                  onChange={(event) => setPhone(event.target.value)}
                  maxLength="40"
                  pattern="[+0-9() .-]{2,40}"
                  required
                />
              </Field>
              <Field id="contact-details" label="Примечание">
                <textarea
                  id="contact-details"
                  value={details}
                  onChange={(event) => setDetails(event.target.value)}
                  maxLength="500"
                />
              </Field>
              <Button
                mode="primary"
                className="brand-button"
                type="submit"
                stretched
                disabled={busy}
              >
                {editingId ? "Сохранить изменения" : "Добавить контакт"}
              </Button>
              {editingId && (
                <Button mode="tertiary" type="button" stretched onClick={resetContact}>
                  Отмена
                </Button>
              )}
            </form>
          </Card>
          <Card>
            <SectionHeading
              number="03"
              title="Приглашения"
              subtitle="Ссылка добавляет жителя только в выбранный дом"
            />
            <form onSubmit={createInvitation} className="admin-form">
              <div className="field-grid">
                <Field id="invite-days" label="Срок, дней">
                  <input
                    id="invite-days"
                    type="number"
                    min="1"
                    max="30"
                    value={days}
                    onChange={(event) => setDays(event.target.value)}
                    required
                  />
                </Field>
                <Field id="invite-limit" label="Число активаций">
                  <input
                    id="invite-limit"
                    type="number"
                    min="1"
                    max="100"
                    value={activationLimit}
                    onChange={(event) => setActivationLimit(event.target.value)}
                    required
                  />
                </Field>
              </div>
              <Button
                mode="primary"
                className="brand-button"
                type="submit"
                stretched
                disabled={busy}
              >
                Создать приглашение
              </Button>
            </form>
            <InvitationToken token={createdToken} />
            <div className="item-list admin-invitations">
              {invitations
                .filter(
                  (invitation) =>
                    invitation.role === "RESIDENT" &&
                    invitation.houses.some((house) => house.id === houseId),
                )
                .map((invitation) => (
                  <div className="admin-row" key={invitation.id}>
                    <div>
                      <strong>
                        {invitation.revokedAt
                          ? "Отозвано"
                          : new Date(invitation.expiresAt) < new Date()
                            ? "Истекло"
                            : "Активно"}
                      </strong>
                      <small>
                        До {passportDate(invitation.expiresAt)} · использовано{" "}
                        {invitation.activationCount} из {invitation.activationLimit}
                      </small>
                    </div>
                    {!invitation.revokedAt && (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => revokeInvitation(invitation.id)}
                      >
                        Отозвать
                      </button>
                    )}
                  </div>
                ))}
            </div>
            <h3>Жители дома</h3>
            <p className="hint">
              Здесь управляют только ролью жителя. Другие роли пользователя сохраняются.
            </p>
            <div className="item-list">
              {residents.map((item) => (
                <div className="admin-row" key={item.userId}>
                  <div>
                    <strong>{item.displayName}</strong>
                    <small>
                      {item.status}
                      {item.userId === currentUserId ? " · вы" : ""}
                    </small>
                  </div>
                  {item.status === "ACTIVE" && currentUserId && item.userId !== currentUserId && (
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => revokeResident(item.userId)}
                    >
                      Отозвать роль жителя
                    </button>
                  )}
                </div>
              ))}
            </div>
          </Card>
          <Card>
            <SectionHeading
              number="04"
              title="Предварительные опросы"
              subtitle="Узнайте мнение жителей выбранного дома"
            />
            <form onSubmit={createPoll} className="admin-form">
              <Field id="poll-question" label="Вопрос">
                <textarea
                  id="poll-question"
                  value={pollQuestion}
                  onChange={(event) => setPollQuestion(event.target.value)}
                  maxLength="500"
                  required
                />
              </Field>
              <div className="poll-option-editor">
                <strong>Варианты ответа</strong>
                {pollOptions.map((value, index) => (
                  <div className="poll-option-edit-row" key={index}>
                    <label htmlFor={`poll-option-${index}`}>{index + 1}</label>
                    <input
                      id={`poll-option-${index}`}
                      value={value}
                      maxLength="200"
                      required
                      placeholder={`Вариант ${index + 1}`}
                      onChange={(event) =>
                        setPollOptions((current) =>
                          current.map((item, position) =>
                            position === index ? event.target.value : item,
                          ),
                        )
                      }
                    />
                    {pollOptions.length > 2 && (
                      <button
                        className="option-control option-control-remove"
                        type="button"
                        aria-label={`Удалить вариант ${index + 1}`}
                        onClick={() =>
                          setPollOptions((current) =>
                            current.filter((_, position) => position !== index),
                          )
                        }
                      >
                        −
                      </button>
                    )}
                  </div>
                ))}
                {pollOptions.length < 20 && (
                  <button
                    className="option-control option-control-add"
                    type="button"
                    onClick={() => setPollOptions((current) => [...current, ""])}
                  >
                    <span aria-hidden="true">+</span> Добавить вариант
                  </button>
                )}
              </div>
              <Field id="poll-close" label="Закрыть опрос">
                <input
                  id="poll-close"
                  type="datetime-local"
                  value={pollClose}
                  onChange={(event) => setPollClose(event.target.value)}
                  required
                />
              </Field>
              <label className="poll-check">
                <input
                  type="checkbox"
                  checked={hidePollResults}
                  onChange={(event) => setHidePollResults(event.target.checked)}
                />{" "}
                Показывать итоги только после закрытия
              </label>
              <p className="hint">Опрос не заменяет официальное голосование собственников.</p>
              <Button
                mode="primary"
                className="brand-button"
                type="submit"
                stretched
                disabled={busy}
              >
                Создать опрос
              </Button>
            </form>
            <div className="poll-list admin-poll-list">
              {polls.map((poll) => (
                <PollCard key={poll.id} poll={poll} />
              ))}
            </div>
          </Card>
        </div>
      )}
      <footer>
        Пульс дома <span>·</span> Кабинет администратора
      </footer>
    </main>
  );
}

function Dispatcher() {
  const [houses, setHouses] = useState([]),
    [houseId, setHouseId] = useState(""),
    [notice, setNotice] = useState("");
  useEffect(() => {
    api("dispatcher", "/v1/me/dispatcher-houses")
      .then((rows) => {
        setHouses(rows);
        setHouseId(rows[0]?.id || "");
      })
      .catch((e) => setNotice(e.message));
  }, []);
  return (
    <main className="shell">
      <Brand role="Диспетчеру" />
      <Hero dispatcher />
      <Notice message={notice} />
      <Field id="house" label="Дом">
        <select id="house" value={houseId} onChange={(e) => setHouseId(e.target.value)}>
          {houses.map((h) => (
            <option key={h.id} value={h.id}>
              {h.address}
            </option>
          ))}
        </select>
      </Field>
      {houseId && (
        <>
          <HouseTariffs houseId={houseId} role="dispatcher" api={api} headers={authHeaders} />
          <StaffCases houseId={houseId} role="dispatcher" api={api} headers={authHeaders} />
        </>
      )}
      <footer>Пульс дома · Кабинет диспетчера</footer>
    </main>
  );
}

const accessRoleLabels = {
  SYSTEM_ADMIN: "Системный администратор",
  UK_ADMIN: "Администратор УК",
  HOUSE_ADMIN: "Администратор дома",
  DISPATCHER: "Диспетчер",
  RESIDENT: "Житель",
};

function InvitationGate() {
  const [token, setToken] = useState(() => {
    const start =
      new URLSearchParams(location.search).get("invite") ||
      window.WebApp?.initDataUnsafe?.start_param ||
      "";
    return start.startsWith("demo_") ||
      start.startsWith("case_") ||
      start.startsWith("wf_") ||
      start.startsWith("xp_")
      ? ""
      : start;
  });
  const [dismissed, setDismissed] = useState(false);
  const [invitation, setInvitation] = useState(null);
  const [legacy, setLegacy] = useState(false);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const role =
    new URLSearchParams(location.search).get("demoSession") === "admin" ? "admin" : "true";
  useEffect(() => {
    if (!token) return;
    api(role, `/v1/access/invitations/${encodeURIComponent(token)}`)
      .then(setInvitation)
      .catch(async () => {
        try {
          const old = await api(role, `/v1/invitations/${encodeURIComponent(token)}`);
          setLegacy(true);
          setInvitation({
            role: "RESIDENT",
            inviter: "Администратор дома",
            organizationName: "",
            houses: [{ id: old.houseId, address: old.address }],
            expiresAt: old.expiresAt,
            acceptedByMe: false,
          });
        } catch (error) {
          setNotice(error.message || String(error));
        }
      });
  }, [token]);
  useEffect(() => {
    if (!token && !dismissed) {
      if (new URLSearchParams(location.search).get("view") === "resident") return;
      const lastMode = localStorage.getItem("pulse-last-mode");
      if (lastMode === "/exchange/index.html") {
        api(role, "/v1/exchange/inbox")
          .then((packets) => {
            if (packets.length) navigate(lastMode);
            else localStorage.removeItem("pulse-last-mode");
          })
          .catch(() => {});
        return;
      }
      if (lastMode === "/work/index.html") {
        api(role, "/v1/workflow/houses")
          .then((houses) => {
            if (houses.length) navigate(lastMode);
            else localStorage.removeItem("pulse-last-mode");
          })
          .catch(() => {});
        return;
      }
      if (lastMode && lastMode !== "/miniapp/index.html") {
        api(role, "/v1/access/me")
          .then((roles) => {
            const destination = rolePages.find(
              ([name, , path]) => path === lastMode && roles.some((item) => item.role === name),
            );
            if (destination) navigate(lastMode);
            else localStorage.removeItem("pulse-last-mode");
          })
          .catch(() => {});
      }
    }
  }, [token, dismissed]);
  if (!token) return <Resident />;
  function continueToRole() {
    if (invitation.role === "UK_ADMIN") {
      navigate("/uk/index.html");
      return;
    }
    if (invitation.role === "HOUSE_ADMIN") {
      navigate("/admin/index.html");
      return;
    }
    if (invitation.role === "DISPATCHER") {
      navigate("/dispatcher/index.html");
      return;
    }
    setDismissed(true);
    setToken("");
    localStorage.setItem("pulse-last-mode", "/miniapp/index.html");
    const url = new URL(location.href);
    url.searchParams.delete("invite");
    history.replaceState(null, "", url);
  }
  async function accept() {
    setBusy(true);
    setNotice("");
    try {
      await api(
        role,
        legacy
          ? `/v1/invitations/${encodeURIComponent(token)}/accept`
          : `/v1/access/invitations/${encodeURIComponent(token)}/accept`,
        { method: "POST" },
      );
      continueToRole();
    } catch (error) {
      setNotice(error.message || String(error));
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="shell">
      <Brand role="Активация" />
      <section className="hero">
        <div className="hero-copy">
          <h1>
            Приглашение
            <br />в Пульс дома
          </h1>
          <p>Проверьте роль и дома перед подтверждением доступа.</p>
        </div>
      </section>
      <Notice message={notice} />
      {invitation && (
        <Card>
          <SectionHeading
            title={accessRoleLabels[invitation.role]}
            subtitle={invitation.organizationName || "Доступ к дому"}
          />
          <p>Пригласил: {invitation.inviter}</p>
          <p>Дома: {invitation.houses.map((house) => house.address).join(", ")}</p>
          <p>Приглашение действует до {new Date(invitation.expiresAt).toLocaleString("ru-RU")}.</p>
          <Button
            mode="primary"
            type="button"
            stretched
            disabled={busy}
            onClick={invitation.acceptedByMe ? continueToRole : accept}
          >
            {invitation.acceptedByMe ? "Перейти в приложение" : "Принять приглашение"}
          </Button>
        </Card>
      )}
      {notice && (
        <Button
          mode="tertiary"
          type="button"
          onClick={() => {
            setDismissed(true);
            setToken("");
          }}
        >
          Открыть мои доступы
        </Button>
      )}
    </main>
  );
}

function HouseChecks({ houses, selected, onChange }) {
  return (
    <div className="house-checks">
      {houses.map((house) => (
        <label key={house.id}>
          <input
            type="checkbox"
            checked={selected.includes(house.id)}
            onChange={(event) =>
              onChange(
                event.target.checked
                  ? [...selected, house.id]
                  : selected.filter((id) => id !== house.id),
              )
            }
          />{" "}
          {house.address}
        </label>
      ))}
    </div>
  );
}

function IssuedInvitations({ items, onRevoke, busy }) {
  return (
    <div className="item-list admin-invitations">
      {items.map((item) => (
        <div className="admin-row" key={item.id}>
          <div>
            <strong>
              {accessRoleLabels[item.role]} ·{" "}
              {item.revokedAt
                ? "отозвано"
                : new Date(item.expiresAt) < new Date()
                  ? "истекло"
                  : "активно"}
            </strong>
            <small>
              {item.houses.map((house) => house.address).join(", ")} · использовано{" "}
              {item.activationCount} из {item.activationLimit}
            </small>
          </div>
          {!item.revokedAt && (
            <button type="button" disabled={busy} onClick={() => onRevoke(item.id)}>
              Отозвать
            </button>
          )}
        </div>
      ))}
    </div>
  );
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
  const refresh = async () => {
    const [orgs, homes] = await Promise.all([
      request("/v1/access/organizations"),
      request("/v1/access/houses"),
    ]);
    setOrganizations(orgs);
    setHouses(homes);
    if (!organizationId && orgs.length) setOrganizationId(orgs[0].id);
  };
  useEffect(() => {
    Promise.all([refresh(), request("/v1/access/invitations").then(setInvitations)]).catch(
      (error) => setNotice(error.message),
    );
  }, []);
  useEffect(() => {
    if (organizationId)
      request(`/v1/access/organizations/${organizationId}/admins`)
        .then(setAdmins)
        .catch((error) => setNotice(error.message));
  }, [organizationId]);
  const org = organizations.find((item) => item.id === organizationId);
  const [caseHouseId, setCaseHouseId] = useState("");
  useEffect(() => {
    setCaseHouseId(org?.houses?.[0]?.id || "");
  }, [organizationId, organizations]);
  async function run(action) {
    setBusy(true);
    setNotice("");
    try {
      await action();
      await refresh();
    } catch (error) {
      setNotice(error.message);
    } finally {
      setBusy(false);
    }
  }
  async function revokeInvitation(id) {
    await run(async () => {
      await request(`/v1/access/invitations/${id}/revoke`, { method: "POST" });
      setInvitations(await request("/v1/access/invitations"));
    });
  }
  return (
    <main className="shell">
      <Brand role="Системному администратору" />
      <section className="hero">
        <div className="hero-copy">
          <div className="hero-kicker">
            <span className="live-dot" /> Кабинет системного администратора
          </div>
          <h1>
            Системный
            <br />
            администратор
          </h1>
          <p>Создавайте организации, закрепляйте дома и приглашайте администраторов УК.</p>
        </div>
      </section>
      <Notice message={notice} />
      <div className="content-grid">
        <Card>
          <SectionHeading title="Управляющие компании" />
          <form
            className="system-form"
            onSubmit={(event) => {
              event.preventDefault();
              run(async () => {
                await request("/v1/access/organizations", {
                  method: "POST",
                  body: JSON.stringify({ name: organizationName.trim() }),
                });
                setOrganizationName("");
              });
            }}
          >
            <Field id="org-name" label="Название УК">
              <input
                id="org-name"
                value={organizationName}
                maxLength="200"
                required
                onChange={(event) => setOrganizationName(event.target.value)}
              />
            </Field>
            <Button className="system-action" mode="primary" type="submit" disabled={busy}>
              Создать УК
            </Button>
          </form>
          <Field id="org-select" label="УК">
            <select
              id="org-select"
              value={organizationId}
              onChange={(event) => {
                setOrganizationId(event.target.value);
                setSelected([]);
              }}
            >
              <option value="">Выберите УК</option>
              {organizations.map((item) => (
                <option value={item.id} key={item.id}>
                  {item.name}
                </option>
              ))}
            </select>
          </Field>
          <form
            className="system-form"
            onSubmit={(event) => {
              event.preventDefault();
              run(async () => {
                await request("/v1/access/houses", {
                  method: "POST",
                  body: JSON.stringify({ address: houseAddress.trim() }),
                });
                setHouseAddress("");
              });
            }}
          >
            <Field id="house-address" label="Новый дом">
              <input
                id="house-address"
                value={houseAddress}
                maxLength="300"
                required
                onChange={(event) => setHouseAddress(event.target.value)}
              />
            </Field>
            <Button className="system-action" mode="secondary" type="submit" disabled={busy}>
              Добавить дом
            </Button>
          </form>
          {org && (
            <>
              <Field id="house-link" label="Закрепить дом за УК">
                <select
                  id="house-link"
                  value={houseId}
                  onChange={(event) => setHouseId(event.target.value)}
                >
                  <option value="">Выберите дом</option>
                  {houses.map((house) => (
                    <option value={house.id} key={house.id}>
                      {house.address}
                    </option>
                  ))}
                </select>
              </Field>
              <Button
                className="system-action"
                mode="secondary"
                type="button"
                disabled={busy || !houseId}
                onClick={() =>
                  run(() =>
                    request(`/v1/access/organizations/${organizationId}/houses/${houseId}`, {
                      method: "PUT",
                      body: JSON.stringify({ active: true }),
                    }),
                  )
                }
              >
                Закрепить
              </Button>
              <div className="item-list linked-house-list">
                {org.houses.map((house) => (
                  <div className="admin-row linked-house-row" key={house.id}>
                    <span>{house.address}</span>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => {
                        if (window.confirm("Приостановить доступ УК и её сотрудников к дому?"))
                          run(() =>
                            request(
                              `/v1/access/organizations/${organizationId}/houses/${house.id}`,
                              { method: "PUT", body: JSON.stringify({ active: false }) },
                            ),
                          );
                      }}
                    >
                      Приостановить
                    </button>
                  </div>
                ))}
              </div>
            </>
          )}
        </Card>
        <Card>
          <SectionHeading
            title="Пригласить администратора УК"
            subtitle="Одноразовая ссылка на 72 часа"
          />
          {org && (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                run(async () => {
                  const result = await request("/v1/access/invitations", {
                    method: "POST",
                    body: JSON.stringify({
                      role: "UK_ADMIN",
                      organizationId,
                      houseIds: selected,
                      days: 3,
                      activationLimit: 1,
                    }),
                  });
                  setToken(result.token);
                  setInvitations(await request("/v1/access/invitations"));
                });
              }}
            >
              <HouseChecks houses={org.houses} selected={selected} onChange={setSelected} />
              <Button mode="primary" type="submit" disabled={busy || !selected.length}>
                Создать приглашение
              </Button>
            </form>
          )}
          <InvitationToken token={token} />
          <IssuedInvitations
            items={invitations.filter(
              (item) => item.role === "UK_ADMIN" && item.organizationId === organizationId,
            )}
            onRevoke={revokeInvitation}
            busy={busy}
          />
          <h3>Администраторы УК</h3>
          <div className="item-list">
            {admins.map((item) => (
              <div className="admin-row" key={`${item.userId}-${item.houseId}`}>
                <div>
                  <strong>{item.displayName}</strong>
                  <small>
                    {item.houseAddress || "Без домов"} · {item.status}
                  </small>
                </div>
                {item.status !== "REVOKED" && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() =>
                      run(async () => {
                        await request("/v1/access/assignments/revoke", {
                          method: "POST",
                          body: JSON.stringify({
                            role: "UK_ADMIN",
                            userId: item.userId,
                            organizationId,
                          }),
                        });
                        setAdmins(
                          await request(`/v1/access/organizations/${organizationId}/admins`),
                        );
                      })
                    }
                  >
                    Отозвать роль
                  </button>
                )}
              </div>
            ))}
          </div>
        </Card>
      </div>
    </main>
  );
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
  useEffect(() => {
    Promise.all([request("/v1/access/my-organizations"), request("/v1/access/invitations")])
      .then(([list, issued]) => {
        setOrganizations(list);
        setInvitations(issued);
        if (list.length) setOrganizationId(list[0].id);
        else setNotice("Нет доступа администратора УК.");
      })
      .catch((error) => setNotice(error.message));
  }, []);
  useEffect(() => {
    if (organizationId)
      request(`/v1/access/organizations/${organizationId}/assignments`)
        .then(setAssignments)
        .catch((error) => setNotice(error.message));
  }, [organizationId]);
  const org = organizations.find((item) => item.id === organizationId);
  const [caseHouseId, setCaseHouseId] = useState("");
  useEffect(() => {
    setCaseHouseId(org?.houses?.[0]?.id || "");
  }, [organizationId, organizations]);
  async function create(event) {
    event.preventDefault();
    setBusy(true);
    setNotice("");
    try {
      const result = await request("/v1/access/invitations", {
        method: "POST",
        body: JSON.stringify({
          role,
          organizationId,
          houseIds: selected,
          days: 3,
          activationLimit: 1,
        }),
      });
      setToken(result.token);
      setInvitations(await request("/v1/access/invitations"));
    } catch (error) {
      setNotice(error.message);
    } finally {
      setBusy(false);
    }
  }
  async function revoke(assignment) {
    setBusy(true);
    setNotice("");
    try {
      await request("/v1/access/assignments/revoke", {
        method: "POST",
        body: JSON.stringify({
          role: assignment.role,
          userId: assignment.userId,
          organizationId,
          houseId: assignment.houseId,
        }),
      });
      setAssignments(await request(`/v1/access/organizations/${organizationId}/assignments`));
    } catch (error) {
      setNotice(error.message);
    } finally {
      setBusy(false);
    }
  }
  async function revokeInvitation(id) {
    setBusy(true);
    setNotice("");
    try {
      await request(`/v1/access/invitations/${id}/revoke`, { method: "POST" });
      setInvitations(await request("/v1/access/invitations"));
    } catch (error) {
      setNotice(error.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="shell">
      <Brand role="Администратору УК" />
      <section className="hero">
        <div className="hero-copy">
          <div className="hero-kicker">
            <span className="live-dot" /> Кабинет администратора УК
          </div>
          <h1>Команда УК</h1>
          <p>Приглашайте диспетчеров и администраторов назначенных домов.</p>
        </div>
      </section>
      <Notice message={notice} />
      {org?.houses?.length > 0 && caseHouseId && (
        <section className="case-panel case-workspace">
          <label>
            Дом для рассмотрения обращений
            <select value={caseHouseId} onChange={(e) => setCaseHouseId(e.target.value)}>
              {org.houses.map((h) => (
                <option key={h.id} value={h.id}>
                  {h.address}
                </option>
              ))}
            </select>
          </label>
          <HouseTariffs
            key={`tariffs:${caseHouseId}`}
            houseId={caseHouseId}
            role="admin"
            api={api}
            headers={authHeaders}
          />
          <StaffCases houseId={caseHouseId} role="admin" api={api} headers={authHeaders} />
          <WorkflowStaff key={caseHouseId} houseId={caseHouseId} role="admin" api={api} />
          <WarrantyRegistry
            key={caseHouseId}
            houseId={caseHouseId}
            role="admin"
            api={api}
            headers={authHeaders}
            canManage
          />
        </section>
      )}
      <div className="content-grid">
        <Card>
          <SectionHeading title="Пригласить сотрудника" />
          <Field id="uk-org" label="Управляющая компания">
            <select
              id="uk-org"
              value={organizationId}
              onChange={(event) => {
                setOrganizationId(event.target.value);
                setSelected([]);
              }}
            >
              <option value="">Выберите УК</option>
              {organizations.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                </option>
              ))}
            </select>
          </Field>
          <form onSubmit={create}>
            <Field id="staff-role" label="Роль">
              <select
                id="staff-role"
                value={role}
                onChange={(event) => {
                  setRole(event.target.value);
                  setSelected([]);
                }}
              >
                <option value="DISPATCHER">Диспетчер</option>
                <option value="HOUSE_ADMIN">Администратор дома</option>
              </select>
            </Field>
            <HouseChecks houses={org?.houses || []} selected={selected} onChange={setSelected} />
            <Button
              mode="primary"
              type="submit"
              disabled={
                busy || !selected.length || (role === "HOUSE_ADMIN" && selected.length !== 1)
              }
            >
              Создать приглашение
            </Button>
          </form>
          <InvitationToken token={token} />
          <IssuedInvitations
            items={invitations.filter((item) => item.organizationId === organizationId)}
            onRevoke={revokeInvitation}
            busy={busy}
          />
        </Card>
        <Card>
          <SectionHeading title="Назначенные сотрудники" />
          <div className="item-list">
            {assignments.map((item) => (
              <div className="admin-row" key={`${item.userId}-${item.role}-${item.houseId}`}>
                <div>
                  <strong>{item.displayName}</strong>
                  <small>
                    {accessRoleLabels[item.role]} · {item.houseAddress} · {item.status}
                  </small>
                </div>
                {item.status === "ACTIVE" && (
                  <button type="button" disabled={busy} onClick={() => revoke(item)}>
                    Отозвать
                  </button>
                )}
              </div>
            ))}
          </div>
        </Card>
      </div>
    </main>
  );
}

function App() {
  const [revision, setRevision] = useState(0);
  const appearance = useAppearance();
  useEffect(() => {
    const refresh = () => setRevision((value) => value + 1);
    window.addEventListener("pulse-access-changed", refresh);
    return () => window.removeEventListener("pulse-access-changed", refresh);
  }, []);
  const path = location.pathname;
  const startParam =
    new URLSearchParams(location.search).get("invite") ||
    window.WebApp?.initDataUnsafe?.start_param ||
    new URLSearchParams(location.search).get("WebAppStartParam") ||
    "";
  if (
    path === "/exchange/index.html" ||
    (["/", "/miniapp/index.html"].includes(path) &&
      startParam.startsWith("xp_") &&
      new URLSearchParams(location.search).get("view") !== "resident")
  )
    return (
      <MaxUI key={revision} colorScheme={appearance.resolved}>
        <ExchangePortal
          api={api}
          headers={authHeaders}
          brand={<Brand role="Получателю материалов" />}
          inviteToken={startParam.startsWith("xp_") ? startParam : ""}
        />
      </MaxUI>
    );
  if (
    path === "/work/index.html" ||
    (startParam.startsWith("wf_") &&
      new URLSearchParams(location.search).get("view") !== "resident")
  )
    return (
      <MaxUI key={revision} colorScheme={appearance.resolved}>
        <WorkPortal
          api={api}
          headers={authHeaders}
          brand={<Brand role="Специалисту" />}
          inviteToken={startParam.startsWith("wf_") ? startParam : ""}
          renderCase={(props) => <CaseDetail {...props} api={api} headers={authHeaders} />}
        />
      </MaxUI>
    );
  return (
    <MaxUI key={revision} colorScheme={appearance.resolved}>
      {path === "/dispatcher/index.html" ? (
        <Dispatcher />
      ) : path === "/admin/index.html" ? (
        <Admin />
      ) : path === "/system/index.html" ? (
        <SystemAdmin />
      ) : path === "/uk/index.html" ? (
        <UkAdmin />
      ) : (
        <InvitationGate />
      )}
    </MaxUI>
  );
}

createRoot(document.getElementById("app")).render(<App />);
