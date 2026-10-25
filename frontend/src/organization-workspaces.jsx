import React, { useEffect, useState } from "react";
import {
  Buildings,
  Users,
  ListBullets,
  ChartBar,
  UserCircle,
  Files,
  Wrench,
} from "@phosphor-icons/react";
import { go, useRoute } from "./navigation.jsx";
import { PageRow } from "./account-pages.jsx";
export const teamRoles = {
  ADMIN: "Администратор организации",
  COORDINATOR: "Координатор обращений",
  SPECIALIST: "Специалист",
};
export const teamFunctions = {
  ENGINEER: "Инженер — осмотры и акты",
  WARRANTY: "Гарантийное рассмотрение",
  CONTRACTOR: "Выполнение работ",
};
const kinds = {
  MANAGEMENT_COMPANY: "Управляющая компания",
  DEVELOPER: "Застройщик",
  GENERAL_CONTRACTOR: "Генподрядчик",
  CONTRACTOR: "Подрядная организация",
  WARRANTY_SERVICE: "Гарантийная служба",
};
const states = {
  OFFERED: "Ожидает приёма",
  ACCEPTED: "Принято",
  RETURNED: "Нужны уточнения",
  REFUSED: "Отказано",
};
const root = "/v1/organization-workspaces";
export function organizationPath(org, role, page) {
  return `/organization/${page || (role === "ADMIN" ? "overview" : "cases")}?organizationId=${encodeURIComponent(org)}&workspaceRole=${role}`;
}
export function OrganizationNavigation({ route, system = false }) {
  const org = route.query.get("organizationId"),
    role = route.query.get("workspaceRole") || "ADMIN";
  const tabs = system
    ? [
        ["queue", "Организации", Buildings],
        ["houses", "Дома", Buildings],
        ["access", "Доступы", Users],
        ["profile", "Профиль", UserCircle],
      ]
    : role === "ADMIN"
      ? [
          ["overview", "Обзор", ChartBar],
          ["team", "Команда", Users],
          ["partners", "Партнёры", Buildings],
          ["profile", "Профиль", UserCircle],
        ]
      : role === "COORDINATOR"
        ? [
            ["cases", "Обращения", ListBullets],
            ["tasks", "Задачи", Wrench],
            ["control", "Контроль", ChartBar],
            ["profile", "Профиль", UserCircle],
          ]
        : [
            ["tasks", "Мои задачи", Wrench],
            ["cases", "Обращения", ListBullets],
            ["documents", "Документы", Files],
            ["profile", "Профиль", UserCircle],
          ];
  return (
    <nav className="resident-navigation" aria-label="Рабочие разделы">
      {tabs.map(([page, label, Icon]) => (
        <button
          key={page}
          aria-current={route.page === page ? "page" : undefined}
          onClick={() => go(system ? `/system/${page}` : organizationPath(org, role, page))}
        >
          <Icon size={23} weight={route.page === page ? "fill" : "regular"} />
          <span>{label}</span>
        </button>
      ))}
    </nav>
  );
}
function InvitationResult({ token, botUsername }) {
  const [copied, setCopied] = useState(false);
  if (!token) return null;
  const link = botUsername
    ? `https://max.ru/${botUsername}?startapp=${encodeURIComponent(token)}`
    : `${location.origin}/miniapp/index.html?invite=${encodeURIComponent(token)}`;
  return (
    <div className="invite-token">
      <strong>Одноразовое приглашение на 7 дней</strong>
      <p>Передайте ссылку нужному сотруднику.</p>
      <code>{link}</code>
      <button
        onClick={async () => {
          await navigator.clipboard.writeText(link);
          setCopied(true);
        }}
      >
        {copied ? "Скопировано" : "Скопировать приглашение"}
      </button>
    </div>
  );
}
export function OrganizationPortal({
  api,
  brand,
  renderCase,
  legacyTeam,
  legacyCases,
  inviteToken = "",
  system = false,
}) {
  const route = useRoute(),
    session = route.query.get("demoSession") || (system ? "admin" : "true"),
    org = route.query.get("organizationId") || "",
    mode = route.query.get("workspaceRole") || "ADMIN";
  const [workspaces, setWorkspaces] = useState([]),
    [invitation, setInvitation] = useState(null),
    [botUsername, setBotUsername] = useState(""),
    [organizations, setOrganizations] = useState([]),
    [members, setMembers] = useState([]),
    [invitations, setInvitations] = useState([]),
    [houses, setHouses] = useState([]),
    [allHouses, setAllHouses] = useState([]),
    [partners, setPartners] = useState([]),
    [transfers, setTransfers] = useState([]),
    [items, setItems] = useState([]),
    [tasks, setTasks] = useState([]),
    [detail, setDetail] = useState(null),
    [context, setContext] = useState(null),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [token, setToken] = useState(""),
    [generation, setGeneration] = useState(0);
  const request = (path, options) => api(session, path, options),
    current = workspaces.find((w) => w.id === org && w.role === mode),
    invited = route.query.get("invite") || inviteToken;
  async function action(path, body, method = "POST") {
    setBusy(true);
    setNotice("");
    try {
      const result = await request(path, {
        method,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (result?.token) setToken(result.token);
      setGeneration((n) => n + 1);
      return result ?? { ok: true };
    } catch (e) {
      setNotice(e.message);
      return null;
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    setToken("");
  }, [org, mode, session]);
  useEffect(() => {
    let active = true;
    setNotice("");
    setItems([]);
    setDetail(null);
    setContext(null);
    setMembers([]);
    setTransfers([]);
    setHouses([]);
    async function load() {
      if (invited) {
        const preview = await request(
          root + "/invitations/preview?token=" + encodeURIComponent(invited),
        );
        if (active) setInvitation(preview);
      }
      const config = await request("/v1/access/config").catch(() => ({}));
      if (active) setBotUsername(config.botUsername || "");
      const mine = await request(root);
      if (!active) return;
      setWorkspaces(mine);
      if (system) {
        const [o, h] = await Promise.all([
          request(root + "/organizations"),
          request("/v1/access/houses"),
        ]);
        if (!active) return;
        setOrganizations(o);
        setAllHouses(h);
        if (org) {
          const [scopedHouses, scopedInvitations] = await Promise.all([
            request(`${root}/${org}/houses`),
            request(`${root}/${org}/invitations`),
          ]);
          if (!active) return;
          setHouses(scopedHouses);
          setInvitations(scopedInvitations);
        }
        return;
      }
      if (!org && !invited && mine.length) {
        go(organizationPath(mine[0].id, mine[0].role), { replace: true });
        return;
      }
      if (!org) return;
      const h = await request(`${root}/${org}/houses`);
      if (!active) return;
      setHouses(h);
      if (mode === "ADMIN") {
        const [m, i, p] = await Promise.all([
          request(`${root}/${org}/members`),
          request(`${root}/${org}/invitations`),
          request(`${root}/${org}/partners`),
        ]);
        if (!active) return;
        setMembers(m);
        setInvitations(i);
        setPartners(p);
      } else {
        if (mode === "COORDINATOR") {
          const [m, t, p] = await Promise.all([
            request(`${root}/${org}/members`),
            request(`${root}/${org}/transfers`),
            request(`${root}/${org}/partners`),
          ]);
          if (!active) return;
          setMembers(m);
          setTransfers(t);
          setPartners(p);
        }
        const [list, tasks] = await Promise.all([
          request(`${root}/${org}/cases?mode=${mode}`),
          request(`${root}/${org}/tasks?mode=${mode}`),
        ]);
        if (!active) return;
        setItems(list);
        setTasks(tasks);
      }
    }
    load().catch((e) => active && setNotice(e.message));
    return () => {
      active = false;
    };
  }, [org, mode, system, generation, session, route.page]);
  const chosen = system ? organizations.find((o) => o.id === org) : current;
  const activeHouse = houses.find((h) => h.id === route.query.get("houseId")) || houses[0];
  const titles = {
    queue: "Организации",
    overview: "Обзор организации",
    team: "Команда",
    partners: "Партнёры",
    cases: "Обращения",
    tasks: mode === "SPECIALIST" ? "Мои задачи" : "Задачи",
    documents: "Документы",
    control: "Контроль",
    houses: "Дома",
    access: "Доступы",
  };
  async function open(c) {
    try {
      const ctx = await request(`/v1/houses/${c.houseId}/case-context`);
      setContext(ctx);
      setDetail(await request(`/v1/cases/${c.id}`));
    } catch (e) {
      setNotice(e.message);
    }
  }
  function formSubmit(event, callback) {
    event.preventDefault();
    callback(Object.fromEntries(new FormData(event.currentTarget)));
  }
  function invitationForm() {
    return (
      <>
        <h2>Пригласить сотрудника</h2>
        <form
          className="workflow-form"
          onSubmit={(e) => {
            e.preventDefault();
            const d = new FormData(e.currentTarget);
            action(`${root}/${org}/invitations`, {
              role: system ? "ADMIN" : d.get("role"),
              department: d.get("department") || "",
              functions: d.getAll("functions"),
            });
          }}
        >
          {!system && (
            <label>
              Роль
              <select name="role">
                <option value="SPECIALIST">Специалист</option>
                <option value="COORDINATOR">Координатор обращений</option>
                <option value="ADMIN">Администратор организации</option>
              </select>
            </label>
          )}
          <label>
            Подразделение
            <input name="department" maxLength={160} placeholder="Например, гарантийная служба" />
          </label>
          {!system && (
            <fieldset>
              <legend>Функции специалиста</legend>
              {Object.entries(teamFunctions).map(([key, label]) => (
                <label key={key}>
                  <input type="checkbox" name="functions" value={key} />
                  {label}
                </label>
              ))}
            </fieldset>
          )}
          <button disabled={busy}>Создать приглашение {system ? "администратора" : ""}</button>
        </form>
        <InvitationResult token={token} botUsername={botUsername} />
        <h3>Приглашения</h3>
        {invitations.map((i) => (
          <div className="case-permission" key={i.id}>
            <span>
              {teamRoles[i.role]} · {i.department}
              <small>
                {i.accepted
                  ? "Принято"
                  : i.revoked
                    ? "Отозвано"
                    : `До ${new Date(i.expiresAt).toLocaleDateString("ru-RU")}`}
              </small>
            </span>
            {!i.accepted && !i.revoked && (
              <button
                disabled={busy}
                onClick={() => action(`${root}/${org}/invitations/${i.id}`, undefined, "DELETE")}
              >
                Отозвать
              </button>
            )}
          </div>
        ))}
      </>
    );
  }
  const visible =
    route.page === "control"
      ? items.filter(
          (c) =>
            c.status !== "CLOSED" &&
            (c.overdueWorkCount > 0 || (c.dueAt && new Date(c.dueAt) < new Date())),
        )
      : items;
  return (
    <main className="shell staff-shell organization-shell">
      {brand}
      <h1>{titles[route.page] || "Рабочий кабинет"}</h1>
      {chosen && (
        <p className="workspace-caption">
          {chosen.name} · {system ? kinds[chosen.kind] : teamRoles[mode]}
          {current?.department ? ` · ${current.department}` : ""}
        </p>
      )}
      {notice && <p role="alert">{notice}</p>}
      {invited && (
        <section className="case-panel">
          <h2>Подключение к организации</h2>
          {invitation && (
            <>
              <h3>{invitation.organizationName}</h3>
              <p>
                {teamRoles[invitation.role]} · {invitation.department}
              </p>
            </>
          )}
          <p>
            Приглашение подключит ваш аккаунт MAX. Материалы обращений откроются после назначения.
          </p>
          <button
            disabled={busy || !invitation}
            onClick={async () => {
              const accepted = await action(root + "/accept", { token: invited });
              if (accepted !== null) go("/miniapp/roles");
              window.dispatchEvent(new Event("pulse-access-changed"));
            }}
          >
            Принять приглашение
          </button>
        </section>
      )}
      {system && (
        <>
          {route.page === "queue" && (
            <section className="case-panel">
              <h2>Подключить организацию</h2>
              <form
                className="workflow-form"
                onSubmit={(e) =>
                  formSubmit(e, (d) =>
                    action(root + "/organizations", { name: d.name, kind: d.kind }),
                  )
                }
              >
                <label>
                  Название
                  <input name="name" required maxLength={160} />
                </label>
                <label>
                  Тип
                  <select name="kind">
                    {Object.entries(kinds).map(([v, label]) => (
                      <option key={v} value={v}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <button disabled={busy}>Создать организацию</button>
              </form>
              {organizations.map((o) => (
                <PageRow
                  key={o.id}
                  icon={Buildings}
                  title={o.name}
                  subtitle={kinds[o.kind]}
                  onClick={() => go(`/system/access?organizationId=${o.id}`)}
                />
              ))}
            </section>
          )}
          {["houses", "access"].includes(route.page) && (
            <section className="case-panel">
              <label>
                Организация
                <select
                  value={org}
                  onChange={(e) => go(`/system/${route.page}?organizationId=${e.target.value}`)}
                >
                  <option value="">Выберите организацию</option>
                  {organizations.map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.name}
                    </option>
                  ))}
                </select>
              </label>
              {route.page === "houses" && (
                <>
                  <h2>Добавить дом</h2>
                  <form
                    className="workflow-form"
                    onSubmit={(e) =>
                      formSubmit(e, (d) => action("/v1/access/houses", { address: d.address }))
                    }
                  >
                    <label>
                      Адрес
                      <input name="address" required maxLength={300} />
                    </label>
                    <button disabled={busy}>Добавить дом</button>
                  </form>
                  {org && (
                    <>
                      <h2>Подключённые дома</h2>
                      <form
                        className="workflow-form"
                        onSubmit={(e) =>
                          formSubmit(e, (d) =>
                            action(`${root}/${org}/houses/${d.house}`, { active: true }, "PUT"),
                          )
                        }
                      >
                        <label>
                          Дом
                          <select name="house">
                            {allHouses.map((h) => (
                              <option key={h.id} value={h.id}>
                                {h.address}
                              </option>
                            ))}
                          </select>
                        </label>
                        <button disabled={busy || !allHouses.length}>Подключить дом</button>
                      </form>
                      {houses.map((h) => (
                        <div className="case-permission" key={h.id}>
                          <span>{h.address}</span>
                          <button
                            disabled={busy}
                            onClick={() =>
                              action(`${root}/${org}/houses/${h.id}`, { active: false }, "PUT")
                            }
                          >
                            Отключить
                          </button>
                        </div>
                      ))}
                    </>
                  )}
                </>
              )}
              {route.page === "access" && org && invitationForm()}
            </section>
          )}
        </>
      )}
      {!system && current && (
        <>
          {route.page === "overview" && (
            <>
              <section className="case-panel">
                <h2>{current.name}</h2>
                <p>{kinds[current.kind]}</p>
                <h3>Подключённые дома</h3>
                {houses.map((h) => (
                  <p key={h.id}>{h.address}</p>
                ))}
                {!houses.length && (
                  <p>Дома пока не подключены. Это делает системный администратор.</p>
                )}
                <PageRow
                  icon={Users}
                  title="Команда"
                  subtitle="Сотрудники, функции и приглашения"
                  onClick={() => go(organizationPath(org, mode, "team"))}
                />
              </section>
              {current.kind === "MANAGEMENT_COMPANY" && (
                <>
                  {activeHouse && (
                    <>
                      <label>
                        Дом для управления сведениями
                        <select
                          value={activeHouse.id}
                          onChange={(e) =>
                            go(
                              organizationPath(org, mode, "overview") +
                                "&houseId=" +
                                encodeURIComponent(e.target.value),
                            )
                          }
                        >
                          {houses.map((h) => (
                            <option key={h.id} value={h.id}>
                              {h.address}
                            </option>
                          ))}
                        </select>
                      </label>
                      <React.Fragment key={activeHouse.id}>
                        {legacyCases?.(activeHouse.id)}
                      </React.Fragment>
                    </>
                  )}
                </>
              )}
            </>
          )}
          {route.page === "team" && (
            <section className="case-panel">
              <h2>Сотрудники организации</h2>
              {members.map((m) => (
                <div className="case-permission" key={m.id + m.role}>
                  <span>
                    <strong>{m.name}</strong>
                    <small>
                      {teamRoles[m.role]} · {m.department}
                      {m.functions.length
                        ? ` · ${m.functions.map((f) => teamFunctions[f]).join(", ")}`
                        : ""}{" "}
                      · {m.active ? "Доступ открыт" : "Доступ отозван"}
                    </small>
                  </span>
                  {m.active && m.role === "SPECIALIST" && (
                    <details>
                      <summary>Функции специалиста</summary>
                      <form
                        onSubmit={(e) => {
                          e.preventDefault();
                          action(
                            `${root}/${org}/members/${m.id}/functions`,
                            { functions: new FormData(e.currentTarget).getAll("functions") },
                            "PUT",
                          );
                        }}
                      >
                        {Object.entries(teamFunctions).map(([key, label]) => (
                          <label key={key}>
                            <input
                              type="checkbox"
                              name="functions"
                              value={key}
                              defaultChecked={m.functions.includes(key)}
                            />
                            {label}
                          </label>
                        ))}
                        <button disabled={busy}>Сохранить функции</button>
                      </form>
                    </details>
                  )}
                  {m.active && (
                    <button
                      disabled={busy}
                      onClick={() =>
                        action(
                          `${root}/${org}/members/${m.id}`,
                          { role: m.role, active: false },
                          "PUT",
                        )
                      }
                    >
                      Отозвать
                    </button>
                  )}
                </div>
              ))}
              <h3>Совмещение функций</h3>
              <p>
                Координатор принимает обращения и назначает специалистов. Доступ к конкретному делу
                выдаётся отдельно.
              </p>
              <button
                disabled={busy}
                onClick={() =>
                  action(
                    `${root}/${org}/coordinator`,
                    { active: !workspaces.some((w) => w.id === org && w.role === "COORDINATOR") },
                    "PUT",
                  )
                }
              >
                {workspaces.some((w) => w.id === org && w.role === "COORDINATOR")
                  ? "Снять с себя функцию координатора"
                  : "Назначить себя координатором"}
              </button>
              {invitationForm()}
              {current.kind === "MANAGEMENT_COMPANY" && legacyTeam?.(org)}
            </section>
          )}
          {route.page === "partners" && (
            <section className="case-panel">
              <h2>Организации, подключённые к тем же домам</h2>
              <p>
                Состав партнёров и область домов подтверждает системный администратор. Партнёр не
                получает доступ к обращениям до принятия передачи.
              </p>
              {partners.map((p) => (
                <p key={p.id}>
                  <strong>{p.name}</strong> · {kinds[p.kind]}
                </p>
              ))}
              {!partners.length && <p>Подключённых партнёров пока нет.</p>}
            </section>
          )}
          {mode !== "ADMIN" && ["cases", "tasks", "documents", "control"].includes(route.page) && (
            <>
              {detail ? (
                <>
                  <button className="case-link" onClick={() => setDetail(null)}>
                    К списку
                  </button>
                  {renderCase({
                    item: detail,
                    context,
                    role: session,
                    onClose: () => setDetail(null),
                    onChange: (c) => {
                      setDetail(c);
                      setItems((list) => list.map((i) => (i.id === c.id ? c : i)));
                    },
                  })}
                  {mode === "COORDINATOR" && (
                    <section className="case-panel">
                      <h2>Команда обращения</h2>
                      <form
                        className="workflow-form"
                        onSubmit={(e) =>
                          formSubmit(e, (d) =>
                            action(
                              `${root}/${org}/cases/${detail.id}/assignments`,
                              {
                                userId: d.userId,
                                function: d.function,
                                enabled: d.enabled === "true",
                                revision: detail.revision,
                              },
                              "PUT",
                            ).then(() => open(detail)),
                          )
                        }
                      >
                        <label>
                          Сотрудник
                          <select name="userId">
                            {members
                              .filter((m) => m.active)
                              .map((m) => (
                                <option key={m.id + m.role} value={m.id}>
                                  {m.name} · {teamRoles[m.role]}
                                </option>
                              ))}
                          </select>
                        </label>
                        <label>
                          Функция
                          <select name="function">
                            <option value="COORDINATOR">Координатор</option>
                            {Object.entries(teamFunctions).map(([v, label]) => (
                              <option key={v} value={v}>
                                {label}
                              </option>
                            ))}
                          </select>
                        </label>
                        <label>
                          Действие
                          <select name="enabled">
                            <option value="true">Назначить</option>
                            <option value="false">Снять назначение</option>
                          </select>
                        </label>
                        <button disabled={busy}>Применить назначение</button>
                      </form>
                      <h2>Направить организации</h2>
                      <p>
                        После принятия координатор получателя получит доступ к техническому
                        обращению, его истории и документам. Личные финансовые дела не передаются.
                        До приёма текущий ответственный сохраняется.
                      </p>
                      <form
                        className="workflow-form"
                        onSubmit={(e) =>
                          formSubmit(e, (d) =>
                            action(`${root}/${org}/transfers`, {
                              caseId: detail.id,
                              target: d.target,
                              reason: d.reason,
                              revision: detail.revision,
                            }).then(() => open(detail)),
                          )
                        }
                      >
                        <label>
                          Организация
                          <select name="target">
                            {partners.map((p) => (
                              <option key={p.id} value={p.id}>
                                {p.name}
                              </option>
                            ))}
                          </select>
                        </label>
                        <label>
                          Причина и необходимые действия
                          <textarea name="reason" required maxLength={3000} />
                        </label>
                        <button disabled={busy || !partners.length}>Направить обращение</button>
                      </form>
                    </section>
                  )}
                </>
              ) : (
                <section className="case-panel">
                  <h2>
                    {route.page === "documents"
                      ? "Документы назначенных обращений"
                      : route.page === "tasks"
                        ? "Назначенная работа"
                        : route.page === "control"
                          ? "Обращения, требующие внимания"
                          : "Доступные обращения"}
                  </h2>
                  <div className="case-list">
                    {route.page === "tasks"
                      ? tasks.map((t) => (
                          <button
                            className="case-list-item"
                            key={t.id}
                            onClick={() => {
                              const c = items.find((c) => c.id === t.caseId);
                              if (c) open(c);
                            }}
                          >
                            <small>
                              {
                                {
                                  INSPECTION: "Осмотр",
                                  WORK: "Работы",
                                  WARRANTY: "Гарантийное рассмотрение",
                                }[t.kind]
                              }
                            </small>
                            <strong>{t.title}</strong>
                            <span>{t.description}</span>
                            {t.dueAt && <small>{new Date(t.dueAt).toLocaleString("ru-RU")}</small>}
                          </button>
                        ))
                      : route.page === "documents"
                        ? visible.flatMap((c) =>
                            (c.documents || []).map((d) => (
                              <button className="case-list-item" key={d.id} onClick={() => open(c)}>
                                <small>Обращение №{c.number}</small>
                                <strong>{d.title}</strong>
                                <span>Версий: {d.versions?.length || 0}</span>
                              </button>
                            )),
                          )
                        : visible.map((c) => (
                            <button className="case-list-item" key={c.id} onClick={() => open(c)}>
                              <small>
                                Обращение №{c.number} · {c.address}
                              </small>
                              <strong>{c.description}</strong>
                              <span>{c.nextStep}</span>
                              {route.page === "documents" ? (
                                <small>Документов: {c.documents?.length || 0}</small>
                              ) : (
                                <small>Открытых заданий: {c.openWorkCount || 0}</small>
                              )}
                            </button>
                          ))}
                  </div>
                  {!(route.page === "tasks"
                    ? tasks.length
                    : route.page === "documents"
                      ? visible.flatMap((c) => c.documents || []).length
                      : visible.length) && (
                    <p>
                      Пока нет назначенной работы. Обращения появятся после назначения
                      координатором.
                    </p>
                  )}
                </section>
              )}
              {mode === "COORDINATOR" && route.page === "cases" && !detail && (
                <section className="case-panel">
                  <h2>Передача между организациями</h2>
                  {transfers.map((t) => (
                    <article className="workflow-card" key={t.id}>
                      <small>{states[t.state]}</small>
                      <h3>{t.description}</h3>
                      <p>
                        {t.fromName} → {t.toName}
                      </p>
                      <p>{t.reason}</p>
                      {t.reply && <p>{t.reply}</p>}
                      {t.history.map((h, i) => (
                        <p className="muted" key={i}>
                          {states[h.state]} · {h.display_name}: {h.note}
                        </p>
                      ))}
                      {t.toId === org && t.state === "OFFERED" && (
                        <form
                          className="workflow-form"
                          onSubmit={(e) =>
                            formSubmit(e, (d) =>
                              action(`${root}/transfers/${t.id}/reply`, {
                                state: d.state,
                                note: d.note,
                                revision: t.revision,
                              }),
                            )
                          }
                        >
                          <label>
                            Ответ
                            <select name="state">
                              <option value="ACCEPTED">Принять в работу</option>
                              <option value="RETURNED">Вернуть на уточнение</option>
                              <option value="REFUSED">Отказать с объяснением</option>
                            </select>
                          </label>
                          <label>
                            Комментарий
                            <textarea name="note" required maxLength={3000} />
                          </label>
                          <button disabled={busy}>Подтвердить ответ</button>
                        </form>
                      )}
                    </article>
                  ))}
                  {!transfers.length && <p>Передач пока нет.</p>}
                </section>
              )}
            </>
          )}
        </>
      )}
      {!system && !current && !invited && (
        <section className="case-panel">
          <p>Этот кабинет недоступен. Выберите действующий кабинет в профиле.</p>
          <button onClick={() => go("/miniapp/roles")}>Мои кабинеты</button>
        </section>
      )}
      <OrganizationNavigation route={route} system={system} />
    </main>
  );
}
