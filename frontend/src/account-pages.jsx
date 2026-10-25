import { organizationPath, teamRoles, teamFunctions } from "./organization-workspaces.jsx";
import React, { useEffect, useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  Bell,
  Buildings,
  CheckCircle,
  GearSix,
  House,
  Info,
  Question,
  UserCircle,
  Users,
  Phone,
  SignIn,
} from "@phosphor-icons/react";
import { AppearancePicker, housePresentation } from "./design.jsx";
import { go, back } from "./navigation.jsx";
function NotificationSwitch({ label, checked, disabled, onChange }) {
  return (
    <button
      className="notification-switch"
      type="button"
      role="switch"
      aria-label={label}
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <span className="notification-switch-track" aria-hidden="true">
        <span />
      </span>
    </button>
  );
}
export function PageTitle({ children, subtitle, root = false }) {
  return (
    <>
      <div className={`page-heading ${root ? "page-heading-root" : ""}`}>
        {!root && (
          <button className="icon-button" type="button" onClick={back} aria-label="Назад">
            <ArrowLeft size={24} />
          </button>
        )}
        <h1 tabIndex={-1}>{children}</h1>
      </div>
      {subtitle && <p className="page-subtitle">{subtitle}</p>}
    </>
  );
}
export function PageRow({ icon: Icon = null, title, subtitle, onClick, selected, children }) {
  const content = (
    <>
      {Icon && <Icon className="row-icon" size={26} />}
      <span className="row-copy">
        <strong>{title}</strong>
        {subtitle && <small>{subtitle}</small>}
      </span>
      {children ||
        (selected ? (
          <CheckCircle size={24} weight="fill" className="emerald" />
        ) : (
          <ArrowRight size={20} />
        ))}
    </>
  );
  return onClick ? (
    <button type="button" className={`page-row ${selected ? "selected" : ""}`} onClick={onClick}>
      {content}
    </button>
  ) : (
    <div className="page-row">{content}</div>
  );
}
const rolePages = [
  ["RESIDENT", "Житель", "/miniapp/home", "true"],
  ["DISPATCHER", "Диспетчер", "/dispatcher/queue", "dispatcher"],
  ["HOUSE_ADMIN", "Администратор дома", "/admin/queue", "admin"],
  ["UK_ADMIN", "Администратор организации", "/uk/queue", "admin"],
  ["SYSTEM_ADMIN", "Системный администратор", "/system/queue", "admin"],
];
const faq = [
  [
    "Как создать обращение?",
    "Опишите ситуацию своими словами и выберите категорию. Если не уверены, выберите «Другое». Получателя определят сотрудники. Фото и точное место можно добавить по желанию.",
  ],
  [
    "Как присоединиться к общей проблеме?",
    "При создании обращения приложение найдёт похожие проблемы. Проверьте описание и место, затем нажмите «Это та же проблема». Общая работа и сроки будут едиными, а ваши документы и переписка останутся отдельными.",
  ],
  [
    "Как задать вопрос по квитанции?",
    "На главной выберите «Вопрос по квитанции». Напишите, что вас смущает. Знать тариф, формулу или организацию не нужно. Квитанцию можно приложить по желанию.",
  ],
  [
    "Как сменить дом?",
    "Откройте «Профиль → Мои дома». После выбора изменятся обращения, тарифы и события. Черновики сохраняются отдельно для каждого дома.",
  ],
  [
    "Что означает подтверждение результата?",
    "После выполнения работы проверьте результат. Если проблема осталась, сообщите об этом в обращении. Согласие за вас никто не проставляет.",
  ],
];
export function AccountPages({
  route,
  api,
  role,
  houses = [],
  selectedHouseId,
  onHouse,
  guidedEntry,
}) {
  const [profile, setProfile] = useState(null),
    [access, setAccess] = useState([]),
    [config, setConfig] = useState(null),
    [demo, setDemo] = useState(null),
    [work, setWork] = useState(false),
    [workspaces, setWorkspaces] = useState([]),
    [exchange, setExchange] = useState(false),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [prefs, setPrefs] = useState(null),
    [subscription, setSubscription] = useState(null),
    [query, setQuery] = useState(""),
    [invite, setInvite] = useState("");
  const request = (path, opts) => api(role, path, opts),
    base = `/${route.workspace}`,
    link = (page) => go(`${base}/${page}`);
  useEffect(() => {
    let active = true;
    Promise.all([
      request("/v1/me/profile"),
      request("/v1/access/me"),
      request("/v1/access/config"),
      request("/v1/guided-demo"),
      request("/v1/workflow/houses").catch(() => []),
      request("/v1/exchange/inbox").catch(() => []),
      request("/v1/organization-workspaces").catch(() => []),
    ])
      .then(([p, a, c, d, w, x, spaces]) => {
        if (active) {
          setProfile(p);
          setAccess(a);
          setConfig(c);
          setDemo(d);
          setWork(w.length > 0);
          setExchange(x.length > 0);
          setWorkspaces(spaces || []);
        }
      })
      .catch((e) => active && setNotice(e.message));
    return () => {
      active = false;
    };
  }, [role]);
  useEffect(() => {
    if (route.page !== "notifications") return;
    let active = true;
    setSubscription(null);
    setPrefs(null);
    Promise.all([
      request("/v1/me/preferences"),
      selectedHouseId ? request(`/v1/houses/${selectedHouseId}/operations`) : Promise.resolve(null),
    ])
      .then(([p, s]) => {
        if (active) {
          setPrefs(p);
          setSubscription(s?.subscription);
        }
      })
      .catch((e) => active && setNotice(e.message));
    return () => {
      active = false;
    };
  }, [route.page, selectedHouseId, role]);
  async function update(path, body, apply) {
    setBusy(true);
    setNotice("");
    try {
      const value = await request(path, { method: "PUT", body: JSON.stringify(body) });
      apply(value);
    } catch (e) {
      setNotice(e.message);
    } finally {
      setBusy(false);
    }
  }
  const selected = houses.find((h) => h.id === selectedHouseId),
    address = housePresentation(selected),
    available = config?.demoMode
      ? rolePages
      : rolePages.filter(([name]) => access.some((a) => a.role === name));
  return (
    <section className="account-page">
      {notice && (
        <p className="notice" role="alert">
          {notice}
        </p>
      )}
      {route.page === "profile" && (
        <>
          <PageTitle root>Профиль</PageTitle>
          <div className="profile-summary">
            <UserCircle size={60} />
            <div>
              <h2>{profile?.displayName || "Загружаем профиль…"}</h2>
              <p className="muted">
                {profile?.fromMax
                  ? "Профиль из MAX"
                  : route.workspace === "miniapp"
                    ? "Личные обращения и события дома"
                    : "Рабочий профиль"}
              </p>
            </div>
          </div>
          <div className="page-list">
            <PageRow
              icon={House}
              title="Мои дома"
              subtitle={selected?.address || "Подключение по приглашению"}
              onClick={() => link("houses")}
            />
            <PageRow
              icon={Users}
              title="Мои кабинеты"
              subtitle={
                (route.workspace === "organization"
                  ? teamRoles[route.query.get("workspaceRole")]
                  : available.find((r) => r[2].startsWith(base + "/"))?.[1]) || "Доступные кабинеты"
              }
              onClick={() => link("roles")}
            />
            <PageRow
              icon={GearSix}
              title="Настройки"
              subtitle="Тема и уведомления"
              onClick={() => link("settings")}
            />
            <PageRow icon={Question} title="Помощь" onClick={() => link("help")} />
            <PageRow icon={Info} title="О приложении" onClick={() => link("about")} />
          </div>
        </>
      )}
      {route.page === "settings" && (
        <>
          <PageTitle>Настройки</PageTitle>

          <section className="settings-section">
            <h3>Оформление</h3>
            <AppearancePicker variant="cards" />
            <p className="muted">
              Изменения применяются сразу. Системный вариант следует теме MAX или устройства.
            </p>
          </section>
          <h3>Уведомления</h3>
          <PageRow
            icon={Bell}
            title="Сообщения в боте"
            subtitle="Подписки на события и работы дома"
            onClick={() => link("notifications")}
          />
          <p className="info-note">Статус ваших обращений всегда доступен в приложении.</p>
        </>
      )}
      {route.page === "houses" && (
        <>
          <PageTitle>Мои дома</PageTitle>
          <h2>Ваши дома</h2>
          <p className="muted">{profile?.displayName}</p>
          {houses.map((h) => {
            const view = housePresentation(h);
            return (
              <PageRow
                key={h.id}
                icon={House}
                title={view.street}
                subtitle={view.city}
                selected={h.id === selectedHouseId}
                onClick={async () => {
                  setNotice("");
                  try {
                    await onHouse?.(h.id);
                    go(
                      route.workspace === "miniapp"
                        ? "/miniapp/home"
                        : `${base}/queue?houseId=${encodeURIComponent(h.id)}`,
                    );
                  } catch (e) {
                    setNotice(e.message);
                  }
                }}
              />
            );
          })}
          {!houses.length && <p>Пока нет подключённых домов.</p>}
          <p className="info-note">При смене дома покажем его обращения, тарифы и события.</p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const value = invite.trim();
              let token = value;
              try {
                const u = new URL(value);
                token =
                  u.searchParams.get("invite") ||
                  u.searchParams.get("startapp") ||
                  u.searchParams.get("start") ||
                  value;
              } catch {}
              go(`/miniapp/index.html?invite=${encodeURIComponent(token)}`);
              window.dispatchEvent(new Event("pulse-access-changed"));
            }}
          >
            <label>
              Приглашение от администратора
              <input
                value={invite}
                onChange={(e) => setInvite(e.target.value)}
                placeholder="Код или ссылка"
                required
                maxLength={2048}
              />
            </label>
            <button disabled={!invite.trim()}>
              <SignIn size={22} /> Подключить по приглашению
            </button>
          </form>
        </>
      )}
      {route.page === "roles" && (
        <>
          <PageTitle>Мои кабинеты</PageTitle>
          <h2>Выберите кабинет</h2>
          <p className="muted">{profile?.displayName}</p>
          {available
            .filter(([key]) => key !== "UK_ADMIN" || !workspaces.some((w) => w.role === "ADMIN"))
            .map(([key, label, path, session]) => (
              <PageRow
                key={key}
                icon={key === "RESIDENT" ? House : Users}
                title={label}
                selected={path.startsWith(base + "/")}
                onClick={() => go(path + (config?.demoMode ? `?demoSession=${session}` : ""))}
              />
            ))}
          {workspaces.map((w) => (
            <PageRow
              key={w.id + w.role}
              icon={Buildings}
              title={teamRoles[w.role]}
              subtitle={`${w.name}${w.department ? ` · ${w.department}` : ""}${w.role === "SPECIALIST" ? ` · ${w.functions.map((f) => teamFunctions[f]).join(", ")}` : ""}`}
              onClick={() => go(organizationPath(w.id, w.role))}
            />
          ))}
          {!workspaces.length && (work || config?.demoMode) && (
            <PageRow
              icon={Buildings}
              title="Кабинет специалиста"
              onClick={() => go("/work/queue" + (config?.demoMode ? "?demoSession=engineer" : ""))}
            />
          )}
          {(exchange || config?.demoMode) && (
            <PageRow
              icon={Buildings}
              title="Полученные материалы"
              onClick={() =>
                go("/exchange/queue" + (config?.demoMode ? "?demoSession=recipient" : ""))
              }
            />
          )}
          <p className="info-note">
            Здесь показаны только доступные роли. Смена режима сохраняет обращения и черновики.
          </p>
        </>
      )}
      {route.page === "notifications" && (
        <>
          <PageTitle>Уведомления</PageTitle>
          <h2>Сообщения в боте</h2>
          <p className="muted">{selected?.address}</p>
          <h3>Мои обращения</h3>
          <PageRow
            icon={Bell}
            title="Обновления по моим обращениям"
            subtitle="Ответы сотрудников и следующие шаги"
          >
            <NotificationSwitch
              label="Обновления по моим обращениям"
              checked={!!prefs?.caseUpdates}
              disabled={busy || !prefs}
              onChange={(checked) =>
                update("/v1/me/preferences", { caseUpdates: checked }, setPrefs)
              }
            />
          </PageRow>
          <h3>События дома</h3>
          {[
            ["mass_problems", "Общие проблемы дома"],
            ["notices", "Отключения и плановые работы"],
            ["payments", "Изменения тарифов"],
          ].map(([key, label]) => (
            <PageRow key={key} icon={Bell} title={label}>
              <NotificationSwitch
                label={label}
                checked={!!subscription?.[key]}
                disabled={busy || !subscription}
                onChange={(checked) => {
                  const next = { ...subscription, [key]: checked };
                  update(
                    `/v1/houses/${selectedHouseId}/operations/subscription`,
                    {
                      notices: !!next.notices,
                      payments: !!next.payments,
                      massProblems: !!next.mass_problems,
                    },
                    () => setSubscription(next),
                  );
                }}
              />
            </PageRow>
          ))}
          <p className="info-note">
            Статус обращений всегда доступен в приложении. Уведомления приходят в чат с ботом.
          </p>
        </>
      )}
      {route.page === "help" && (
        <>
          <PageTitle>Помощь</PageTitle>
          <h2>Чем помочь?</h2>
          <label className="search-field">
            Найти ответ
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Введите вопрос"
            />
          </label>
          {faq
            .filter(([q, a]) => (q + a).toLowerCase().includes(query.toLowerCase()))
            .map(([q, a]) => (
              <details className="faq-row" key={q}>
                <summary>{q}</summary>
                <p>{a}</p>
              </details>
            ))}
          {!faq.some(([q, a]) => (q + a).toLowerCase().includes(query.toLowerCase())) && (
            <p>Ответ не найден. Проверьте контакты своего дома.</p>
          )}
          <PageRow icon={Phone} title="Контакты дома" onClick={() => go("/miniapp/contacts")} />
        </>
      )}
      {route.page === "about" && (
        <>
          <PageTitle>О приложении</PageTitle>
          <h2>Пульс дома</h2>
          <p>
            Один путь от сообщения о проблеме до проверенного результата. Общая работа соседей,
            личная переписка, понятные сроки и события вашего дома.
          </p>
          <p>Команда VEGA2124</p>
          {(config?.demoMode || demo?.active) && (
            <>
              <p className="info-note">
                Вы просматриваете учебные данные. Имена, адреса и обращения используются для
                демонстрации сценариев.
              </p>
              {demo?.active && (
                <button
                  type="button"
                  className="case-secondary"
                  disabled={busy}
                  onClick={async () => {
                    setBusy(true);
                    try {
                      await request("/v1/guided-demo/exit", { method: "POST" });
                      go("/miniapp/home");
                      window.dispatchEvent(new Event("pulse-access-changed"));
                    } catch (e) {
                      setNotice(e.message);
                    } finally {
                      setBusy(false);
                    }
                  }}
                >
                  Выйти из учебного сценария
                </button>
              )}
            </>
          )}
          {guidedEntry}
        </>
      )}
    </section>
  );
}
