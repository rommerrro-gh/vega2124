import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { build } from "esbuild";
import { writeFile, unlink } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import React, { act } from "react";

const temp = new URL(`./.pages-${process.pid}.mjs`, import.meta.url);
await build({
  stdin: {
    contents:
      'export {OrganizationPortal,OrganizationNavigation} from "./src/organization-workspaces.jsx";export {ResidentCases,CaseDetail,StaffCases} from "./src/resident-cases.jsx";export {AccountPages} from "./src/account-pages.jsx";export {OperationsInbox} from "./src/operations.jsx";export {CommonProblems} from "./src/common-problems.jsx";export {CaseWorkflow} from "./src/warranty-workflow.jsx";export {useRoute,go} from "./src/navigation.jsx";',
    resolveDir: fileURLToPath(new URL("../", import.meta.url)),
    loader: "jsx",
  },
  bundle: true,
  platform: "node",
  format: "esm",
  external: ["react", "react-dom", "react-dom/client"],
  loader: { ".css": "empty" },
  outfile: fileURLToPath(temp),
  logLevel: "silent",
});

function env(path) {
  const dom = new JSDOM('<div id="root"></div>', { url: "https://pulsedoma.ru" + path });
  for (const k of [
    "window",
    "document",
    "location",
    "history",
    "sessionStorage",
    "localStorage",
    "Event",
    "CustomEvent",
    "HTMLElement",
    "FormData",
  ])
    globalThis[k] = dom.window[k];
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  globalThis.requestAnimationFrame = (f) => f();
  window.scrollTo = () => {};
  return dom;
}
const initialDom = env("/miniapp/home");
globalThis.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
const { createRoot } = await import("react-dom/client");
const {
  OrganizationPortal,
  OrganizationNavigation,
  ResidentCases,
  CaseDetail,
  StaffCases,
  AccountPages,
  OperationsInbox,
  CommonProblems,
  CaseWorkflow,
  useRoute,
  go,
} = await import(temp.href);
initialDom.window.close();
const house = { id: "h", address: "Казань, ул. Баумана, 12" };
const ctx = {
  actorId: "u",
  intakeAvailable: true,
  canReview: true,
  canManagePermissions: true,
  staff: [],
};
const flush = () => new Promise((r) => setTimeout(r, 0));
try {
  await test("specialist sees actual assigned tasks and documents with four navigation tabs", async () => {
    const dom = env("/organization/tasks?organizationId=o&workspaceRole=SPECIALIST");
    const calls = [];
    const api = async (role, path) => {
      calls.push(path);
      if (path === "/v1/organization-workspaces")
        return [
          {
            id: "o",
            name: "Стройсервис",
            kind: "GENERAL_CONTRACTOR",
            role: "SPECIALIST",
            department: "Бригада",
            functions: ["CONTRACTOR"],
          },
        ];
      if (path.endsWith("/houses")) return [house];
      if (path.includes("/cases?mode="))
        return [
          {
            id: "c",
            number: 1,
            description: "Дверь не закрывается",
            houseId: "h",
            documents: [{ id: "d", title: "Акт", versions: [] }],
          },
        ];
      if (path.includes("/tasks?mode="))
        return [
          {
            id: "w",
            caseId: "c",
            kind: "WORK",
            title: "Заменить доводчик",
            description: "Дверь не закрывается",
          },
        ];
      if (path.endsWith("case-context")) return ctx;
      if (path === "/v1/cases/c") return { id: "c", description: "Дверь не закрывается" };
      throw new Error(path);
    };
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(
        React.createElement(OrganizationPortal, {
          api,
          renderCase: ({ item }) => React.createElement("p", null, "Карточка: " + item.description),
        }),
      );
      await flush();
      await flush();
    });
    assert.match(document.body.textContent, /Заменить доводчик/);
    assert.equal(document.querySelectorAll("nav button").length, 4);
    await act(async () => {
      Array.from(document.querySelectorAll("button"))
        .find((b) => b.textContent.includes("Заменить доводчик"))
        .click();
      await flush();
    });
    assert.match(document.body.textContent, /Карточка: Дверь не закрывается/);
    assert.equal(new URLSearchParams(location.search).get("caseId"), "c");
    assert.ok(document.querySelector(".case-workspace"));
    assert.ok(calls.includes("/v1/organization-workspaces/o/tasks?mode=SPECIALIST"));
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("organization deep link survives remount and assignment filters staff by function", async () => {
    const dom = env("/organization/cases?organizationId=o&workspaceRole=COORDINATOR&caseId=c");
    const changes = [];
    const api = async (_role, path, options = {}) => {
      if (path === "/v1/organization-workspaces")
        return [{ id: "o", name: "УК", role: "COORDINATOR", functions: [] }];
      if (path.endsWith("/houses")) return [house];
      if (path.endsWith("/members"))
        return [
          { id: "e", name: "Инженер", role: "SPECIALIST", active: true, functions: ["ENGINEER"] },
          { id: "k", name: "Координатор", role: "COORDINATOR", active: true, functions: [] },
          { id: "a", name: "Администратор", role: "ADMIN", active: true, functions: [] },
        ];
      if (path.endsWith("/transfers") || path.endsWith("/partners") || path.includes("/tasks?"))
        return [];
      if (path.includes("/cases?")) return [];
      if (path === "/v1/cases/c")
        return {
          id: "c",
          number: 1,
          houseId: "h",
          kind: "PROBLEM",
          revision: 2,
          description: "Проблема",
        };
      if (path.endsWith("case-context")) return ctx;
      if (path.endsWith("/assignments")) {
        changes.push(JSON.parse(options.body));
        return {};
      }
      if (path.endsWith("/config")) return {};
      throw Error(path);
    };
    const props = {
      api,
      renderCase: ({ onClose }) => React.createElement("button", { onClick: onClose }, "К списку"),
    };
    let root = createRoot(document.getElementById("root"));
    const mount = async () =>
      act(async () => {
        root.render(React.createElement(OrganizationPortal, props));
        await flush();
        await flush();
      });
    await mount();
    assert.match(document.body.textContent, /Обращение №1/);
    assert.equal(document.querySelectorAll("button").length > 0, true);
    const people = () =>
      Array.from(document.querySelector('select[name="userId"]').options).map((o) => o.textContent);
    assert.deepEqual(people(), ["Инженер"]);
    await act(async () => {
      const sel = document.querySelector('select[name="function"]');
      sel.value = "COORDINATOR";
      sel.dispatchEvent(new Event("change", { bubbles: true }));
    });
    assert.deepEqual(people(), ["Координатор"]);
    await act(async () => {
      document
        .querySelector('select[name="userId"]')
        .closest("form")
        .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
      await flush();
      await flush();
    });
    assert.deepEqual(changes, [
      { userId: "k", function: "COORDINATOR", enabled: true, revision: 2 },
    ]);
    await act(async () => root.unmount());
    root = createRoot(document.getElementById("root"));
    await mount();
    assert.match(document.body.textContent, /Обращение №1/);
    await act(async () =>
      Array.from(document.querySelectorAll("button"))
        .find((b) => b.textContent === "К списку")
        .click(),
    );
    assert.equal(new URLSearchParams(location.search).has("caseId"), false);
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("organization navigation retains its organization and selected cabinet", async () => {
    const dom = env("/organization/team?organizationId=o&workspaceRole=ADMIN");
    const { parseRoute } = await import("../src/page-router.mjs");
    const root = createRoot(document.getElementById("root"));
    await act(async () =>
      root.render(
        React.createElement(OrganizationNavigation, { route: parseRoute(location.href) }),
      ),
    );
    const buttons = Array.from(document.querySelectorAll("button"));
    assert.deepEqual(
      buttons.map((b) => b.textContent),
      ["Обзор", "Команда", "Партнёры", "Профиль"],
    );
    await act(async () => buttons[3].click());
    assert.equal(location.pathname, "/organization/profile");
    assert.equal(new URLSearchParams(location.search).get("organizationId"), "o");
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("notification controls toggle all four settings and retain server values after remount", async () => {
    const dom = env("/miniapp/notifications");
    let prefs = { caseUpdates: false },
      subscription = { mass_problems: false, notices: false, payments: false };
    const api = async (role, path, options = {}) => {
      if (path === "/v1/me/preferences") {
        if (options.method === "PUT") prefs = JSON.parse(options.body);
        return prefs;
      }
      if (path.endsWith("/operations/subscription")) {
        const body = JSON.parse(options.body);
        subscription = {
          mass_problems: body.massProblems,
          notices: body.notices,
          payments: body.payments,
        };
        return subscription;
      }
      if (path.endsWith("/operations")) return { subscription };
      return {
        "/v1/me/profile": { displayName: "Житель" },
        "/v1/access/me": [{ role: "RESIDENT" }],
        "/v1/access/config": { demoMode: false },
        "/v1/guided-demo": { active: false },
        "/v1/workflow/houses": [],
        "/v1/exchange/inbox": [],
      }[path];
    };
    function Harness() {
      return React.createElement(AccountPages, {
        route: useRoute(),
        api,
        role: "true",
        houses: [house],
        selectedHouseId: "h",
      });
    }
    const render = async () => {
      const root = createRoot(document.getElementById("root"));
      await act(async () => {
        root.render(React.createElement(Harness));
        await flush();
      });
      return root;
    };
    let root = await render();
    assert.equal(document.querySelectorAll('[role="switch"]').length, 4);
    for (const control of document.querySelectorAll('[role="switch"]')) {
      assert.equal(control.tagName, "BUTTON");
      await act(async () => {
        control.click();
        await flush();
      });
      assert.equal(control.getAttribute("aria-checked"), "true");
    }
    assert.deepEqual(prefs, { caseUpdates: true });
    assert.deepEqual(subscription, { mass_problems: true, notices: true, payments: true });
    await act(async () => root.unmount());
    root = await render();
    assert.equal(document.querySelectorAll('[role="switch"][aria-checked="true"]').length, 4);
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("staff reminders close on outside pointer and Escape, but not a click inside", async () => {
    const dom = env("/dispatcher/queue");
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(
        React.createElement(OperationsInbox, {
          api: async () => [],
          role: "dispatcher",
          onOpen: () => {},
        }),
      );
      await flush();
    });
    const popup = document.querySelector("details");
    popup.open = true;
    popup.querySelector("p").dispatchEvent(new dom.window.Event("pointerdown", { bubbles: true }));
    assert.equal(popup.open, true);
    document.body.dispatchEvent(new dom.window.Event("pointerdown", { bubbles: true }));
    assert.equal(popup.open, false);
    popup.open = true;
    document.dispatchEvent(
      new dom.window.KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
    );
    assert.equal(popup.open, false);
    assert.equal(document.activeElement, popup.querySelector("summary"));
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("resident visit page shows warranty inspections and confirms time without exposing staff controls", async () => {
    const dom = env("/miniapp/cases/c/visits");
    const item = {
      id: "c",
      number: 42,
      houseId: "h",
      status: "WAITING_RESIDENT",
      kind: "WARRANTY",
      revision: 7,
      documents: [],
      events: [],
      isAuthor: true,
      fullAccess: true,
      description: "Мокнет стена",
    };
    const inspection = {
      id: "i",
      state: "PROPOSED",
      startsAt: new Date(Date.now() + 86400000).toISOString(),
      endsAt: new Date(Date.now() + 93600000).toISOString(),
      inspectorId: "engineer",
      inspector: "Инженер",
      location: "Подъезд 1",
      participants: "Житель и инженер",
      revision: 0,
      residentAvailability: "UNKNOWN",
      acts: [],
      responses: [],
    };
    const workspace = {
      revision: 7,
      isAuthor: true,
      canCoordinate: true,
      canInspect: true,
      canReviewWarranty: true,
      staff: [{ id: "engineer", name: "Инженер" }],
      reviews: [],
      inspections: [inspection],
      orders: [],
      handoffs: [],
      history: [],
    };
    let sent;
    const api = async (role, path, options = {}) => {
      if (path.endsWith("/workflow")) return workspace;
      if (path.endsWith("/visits")) return { visits: [] };
      if (path === "/v1/inspections/i/response") {
        sent = JSON.parse(options.body);
        return {
          ...workspace,
          inspections: [{ ...inspection, residentAvailability: "CONFIRMED" }],
        };
      }
      if (path === "/v1/cases/c") return item;
      throw Error(path);
    };
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(
        React.createElement(CaseDetail, {
          item,
          api,
          role: "true",
          headers: () => ({}),
          onChange: () => {},
          residentOnly: true,
          section: "visits",
        }),
      );
      await flush();
    });
    assert.match(document.body.textContent, /Осмотры и акты · 1/);
    assert.equal(document.body.textContent.includes("Перенести осмотр"), false);
    assert.equal(document.body.textContent.includes("Назначить инженера"), false);
    assert.equal(document.body.textContent.includes("Предложить осмотр"), false);
    await act(async () => {
      [...document.querySelectorAll("button")]
        .find((b) => b.textContent === "Это время подходит")
        .click();
      await flush();
    });
    assert.deepEqual(sent, { action: "CONFIRM_TIME", revision: 0, caseRevision: 7 });
    assert.equal(document.body.textContent.includes("Это время подходит"), false);
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("dispatcher follows the merged common case instead of restoring its retired source", async () => {
    const dom = env("/dispatcher/queue?caseId=source");
    const source = {
      id: "source",
      workflowCaseId: "source",
      sharedRequestId: "g1",
      houseId: "h",
      number: 1,
      description: "Уборка подъезда",
      kind: "PROBLEM",
      status: "IN_PROGRESS",
      revision: 1,
      documents: [],
      fullAccess: false,
    };
    const target = {
      ...source,
      id: "target",
      workflowCaseId: "target",
      sharedRequestId: "g2",
      number: 2,
    };
    const group = (id, workflowCaseId) => ({
      id,
      workflowCaseId,
      title: "Уборка подъезда",
      category: "ENTRANCE_CLEANING",
      status: "IN_PROGRESS",
      canManage: true,
      participants: 1,
      confirmedCount: 0,
      revision: 1,
      members: [],
      history: [],
      work: [],
    });
    let merged = false;
    const api = async (role, path) => {
      if (path.endsWith("/case-queue")) return merged ? [target] : [source];
      if (path.endsWith("/case-context"))
        return { ...ctx, canReview: false, canManagePermissions: false };
      if (path === "/v1/shared-requests/g1/merge") {
        merged = true;
        return group("g2", "target");
      }
      if (path === "/v1/shared-requests/g1") {
        assert.equal(merged, false, "retired source must not be reopened");
        return group("g1", "source");
      }
      if (path === "/v1/shared-requests/g2") return group("g2", "target");
      if (path.endsWith("/shared-requests")) return [group("g1", "source"), group("g2", "target")];
      if (path === "/v1/cases/target") return target;
      if (path === "/v1/cases/source") return source;
      throw Error(path);
    };
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(
        React.createElement(StaffCases, {
          houseId: "h",
          role: "dispatcher",
          api,
          headers: () => ({}),
        }),
      );
      await flush();
    });
    const section = document.querySelector(".shared-request-panel");
    await act(async () => {
      const reason = [...section.querySelectorAll("textarea")][0];
      Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype, "value").set.call(
        reason,
        "Подтверждена одна проблема",
      );
      reason.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
      reason.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
      const select = [...section.querySelectorAll("select")].find((e) =>
        [...e.options].some((o) => o.value === "g2"),
      );
      select.value = "g2";
      select.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
      await flush();
    });
    const merge = [...section.querySelectorAll("button")].find(
      (b) => b.textContent === "Объединить с сохранением истории",
    );
    assert.equal(merge.disabled, false);
    await act(async () => {
      merge.click();
      await flush();
      await flush();
    });
    assert.equal(merged, true);
    assert.equal(new URL(location.href).searchParams.get("caseId"), "target");
    assert.ok(document.body.textContent.includes("ОБРАЩЕНИЕ № 2"));
    assert.ok(!document.body.textContent.includes("Общая заявка недоступна"));
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("dispatcher restores a permitted personal case absent from the common queue", async () => {
    const dom = env("/dispatcher/queue?caseId=personal");
    const item = {
      id: "personal",
      houseId: "h",
      number: 17,
      description: "Материалы жителя",
      kind: "PROBLEM",
      status: "IN_PROGRESS",
      revision: 1,
      documents: [],
      fullAccess: false,
    };
    const api = async (role, path) => {
      if (path.endsWith("/case-queue")) return [];
      if (path.endsWith("/case-context"))
        return { ...ctx, canReview: false, canManagePermissions: false };
      if (path === "/v1/cases/personal") return item;
      throw Error(path);
    };
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(
        React.createElement(StaffCases, {
          houseId: "h",
          role: "dispatcher",
          api,
          headers: () => ({}),
        }),
      );
      await flush();
      await flush();
    });
    assert.ok(document.body.textContent.includes("ОБРАЩЕНИЕ № 17"));
    assert.ok(document.body.textContent.includes("Материалы жителя"));
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("inspection form sends native date input values and the selected engineer", async () => {
    const dom = env("/dispatcher/queue");
    const item = { id: "c", houseId: "h", status: "IN_PROGRESS", revision: 7, documents: [] };
    const workspace = {
      revision: 7,
      isAuthor: false,
      canCoordinate: true,
      canInspect: false,
      canReviewWarranty: false,
      staff: [
        { id: "engineer", name: "Инженер" },
        { id: "other", name: "Не назначен" },
      ],
      inspectors: [{ id: "engineer", name: "Инженер" }],
      executors: [],
      commonWork: true,
      reviews: [],
      inspections: [],
      orders: [],
      handoffs: [],
      history: [],
    };
    let sent;
    const api = async (role, path, options = {}) => {
      if (path.endsWith("/inspections")) {
        sent = JSON.parse(options.body);
        return workspace;
      }
      if (path.endsWith("/workflow")) return workspace;
      if (path.endsWith("/warranties")) return [];
      if (path === "/v1/cases/c") return item;
      throw Error(path);
    };
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(
        React.createElement(CaseWorkflow, { item, role: "dispatcher", api, onChange: () => {} }),
      );
      await flush();
    });
    const form = [...document.querySelectorAll("form")].find((f) =>
      f.textContent.includes("Начало интервала"),
    );
    assert.ok(form.textContent.includes("Запланировать осмотр"));
    assert.ok(!form.textContent.includes("Не назначен"));
    const fields = [...form.querySelectorAll("input,textarea,select")];
    const input = async (label, value) => {
      const field = fields.find((f) => f.parentElement.textContent.startsWith(label));
      await act(async () => {
        field.value = value;
        field.dispatchEvent(
          new dom.window.Event(field.tagName === "SELECT" ? "change" : "input", { bubbles: true }),
        );
        await flush();
      });
    };
    await input("Инженер — участник обращения", "engineer");
    await input("Где провести осмотр", "Подъезд 1");
    await input("Планируемые участники", "Житель и инженер");
    await input("Начало интервала", "2026-10-25T14:00");
    await input("Конец интервала", "2026-10-25T16:00");
    await act(async () => {
      form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
      await flush();
    });
    assert.equal(sent.inspectorId, "engineer");
    assert.equal(sent.location, "Подъезд 1");
    assert.equal(sent.participants, "Житель и инженер");
    assert.equal(sent.startsAt, new Date("2026-10-25T14:00").toISOString());
    assert.equal(sent.endsAt, new Date("2026-10-25T16:00").toISOString());
    assert.equal(sent.caseRevision, 7);
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("joining a known common problem needs confirmation, preserves another draft and safely retries after reload", async () => {
    const dom = env("/miniapp/shared/shared-1?demoSession=admin");
    const savedDraft = JSON.stringify({ description: "Другой незавершённый вопрос" });
    sessionStorage.setItem("pulse-case-v2:u:h", savedDraft);
    const calls = [];
    const shared = {
      id: "shared-1",
      houseId: "h",
      title: "Сломаны качели",
      category: "PLAYGROUND",
      location: "Детская площадка",
      status: "IN_PROGRESS",
      participants: 1,
      history: [],
    };
    const api = async (role, path, options = {}) => {
      if (path === "/v1/shared-problems/shared-1") return shared;
      if (path.endsWith("/case-context")) return { actorId: "u" };
      if (path.endsWith("/join")) {
        calls.push(JSON.parse(options.body));
        if (calls.length === 1) throw Error("Соединение прервано");
        return { id: "joined" };
      }
      throw Error(path);
    };
    const render = async () => {
      const root = createRoot(document.getElementById("root"));
      await act(async () => {
        root.render(
          React.createElement(CommonProblems, { id: "shared-1", houseId: "h", role: "admin", api }),
        );
        await flush();
      });
      return root;
    };
    const click = async (text) => {
      await act(async () => {
        [...document.querySelectorAll("button")].find((b) => b.textContent === text).click();
        await flush();
      });
    };
    let root = await render();
    await click("У меня такая же проблема");
    assert.equal(calls.length, 0);
    assert.match(document.body.textContent, /Повторять описание не нужно/);
    await click("Да, присоединиться");
    assert.match(document.querySelector('[role="alert"]').textContent, /копия не создастся/);
    await act(async () => root.unmount());
    root = await render();
    await click("У меня такая же проблема");
    await click("Да, присоединиться");
    assert.equal(calls.length, 2);
    assert.equal(calls[0].clientRequestId, calls[1].clientRequestId);
    assert.deepEqual(calls[1].problem, {
      houseId: "h",
      description: "Сломаны качели",
      category: "PLAYGROUND",
      location: "Детская площадка",
    });
    assert.equal(sessionStorage.getItem("pulse-case-v2:u:h"), savedDraft);
    assert.equal(sessionStorage.getItem("pulse-common-join:u:h:shared-1"), null);
    assert.equal(location.pathname, "/miniapp/cases/joined");
    assert.equal(location.search, "?demoSession=admin");
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("resident form saves a draft across navigation; required category and actual matching/join flow", async () => {
    const dom = env("/miniapp/home");
    const calls = [];
    let created = 0;
    const item = {
      id: "c",
      houseId: "h",
      number: 1,
      description: "Не горит свет",
      status: "RECEIVED",
      kind: "PROBLEM",
      isAuthor: true,
      fullAccess: false,
      nextStep: "Проверяем",
      responsibleName: "Анна",
      participants: 2,
      documents: [],
      events: [],
      revision: 0,
    };
    const api = async (role, path, options = {}) => {
      calls.push([path, options.body]);
      if (path === "/v1/cases") return options.method === "POST" ? (created++, item) : [];
      if (path.endsWith("/case-context")) return ctx;
      if (path === "/v1/cases/similar-problems")
        return [
          {
            id: "g",
            source: "SHARED",
            description: "Не горит свет",
            participants: 1,
            reasons: [],
            score: 0.9,
          },
        ];
      if (path === "/v1/shared-requests/g/join") {
        created++;
        return item;
      }
      if (path === "/v1/cases/c") return item;
      throw Error(path);
    };
    function Harness() {
      const route = useRoute();
      return React.createElement(ResidentCases, {
        house,
        role: "true",
        api,
        headers: () => ({}),
        canSubmit: true,
        view: route.page === "home" ? "home" : "cases",
        route,
      });
    }
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(React.createElement(Harness));
      await flush();
    });
    await act(async () => {
      document.querySelector(".case-start button").click();
      await flush();
    });
    assert.equal(location.pathname, "/miniapp/new");
    const textarea = document.querySelector("textarea");
    await act(async () => {
      Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype, "value").set.call(
        textarea,
        "Не горит свет",
      );
      textarea.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
      textarea.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
      await flush();
    });
    // A real persisted draft exercises reload recovery without relying on React's input polyfill.
    const saved = {
      kind: "PROBLEM",
      description: "Не горит свет",
      category: "LIGHTING",
      location: "Подъезд 1",
      clientRequestId: "request-1",
      savedAt: Date.now(),
    };
    sessionStorage.setItem("pulse-case-v2:u:h", JSON.stringify(saved));
    await act(async () => root.unmount());
    const second = createRoot(document.getElementById("root"));
    await act(async () => {
      second.render(React.createElement(Harness));
      await flush();
    });
    assert.equal(document.querySelector("textarea").value, saved.description);
    assert.equal(document.querySelector("select").value, "LIGHTING");
    assert.equal(
      document.body.textContent.includes("Рабочие обращения — доступ сотрудника"),
      false,
    );
    await act(async () => {
      document
        .querySelector("form")
        .dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
      await flush();
    });
    assert.equal(location.pathname, "/miniapp/similar");
    assert.ok(document.querySelector('[aria-label="Похожие проблемы"]'));
    assert.equal(document.body.textContent.includes("Оценка сходства"), false);
    const join = [...document.querySelectorAll("button")].find((b) =>
      b.textContent.includes("Это та же проблема"),
    );
    await act(async () => {
      join.click();
      await flush();
    });
    assert.equal(location.pathname, "/miniapp/cases/c");
    assert.equal(created, 1);
    assert.equal(sessionStorage.getItem("pulse-case-v2:u:h"), null);
    const body = JSON.parse(calls.find((c) => c[0].endsWith("/join"))[1]);
    assert.equal(body.clientRequestId, "request-1");
    assert.equal(body.problem.category, "LIGHTING");
    await act(async () => second.unmount());
    dom.window.close();
  });
  await test("failed matching allows explicit fallback without losing the draft", async () => {
    const dom = env("/miniapp/new");
    const saved = {
      kind: "PROBLEM",
      description: "Не горит свет",
      category: "OTHER",
      location: "",
      clientRequestId: "retry-1",
      savedAt: Date.now(),
    };
    sessionStorage.setItem("pulse-case-v2:u:h", JSON.stringify(saved));
    const api = async (r, p) => {
      if (p === "/v1/cases") return [];
      if (p.endsWith("/case-context")) return ctx;
      if (p.endsWith("similar-problems")) throw Error("Нет соединения");
      throw Error(p);
    };
    function Harness() {
      const route = useRoute();
      return React.createElement(ResidentCases, {
        house,
        role: "true",
        api,
        headers: () => ({}),
        canSubmit: true,
        view: "cases",
        route,
      });
    }
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(React.createElement(Harness));
      await flush();
    });
    await act(async () => {
      document
        .querySelector("form")
        .dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
      await flush();
    });
    const fallback = [...document.querySelectorAll("button")].find((b) =>
      b.textContent.includes("Продолжить без проверки"),
    );
    assert.ok(fallback);
    await act(async () => {
      fallback.click();
      await flush();
    });
    assert.equal(location.pathname, "/miniapp/similar");
    assert.equal(new URLSearchParams(location.search).get("withoutSearch"), "1");
    assert.ok(
      [...document.querySelectorAll("button")].some((b) => b.textContent === "Отправить обращение"),
    );
    assert.equal(
      JSON.parse(sessionStorage.getItem("pulse-case-v2:u:h")).clientRequestId,
      "retry-1",
    );
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("optional payment month survives input, preview and submission", async () => {
    const dom = env("/miniapp/payment");
    let sent;
    sessionStorage.setItem(
      "pulse-case-v2:u:h",
      JSON.stringify({
        kind: "PAYMENT",
        description: "Поясните начисление",
        location: "",
        clientRequestId: "payment-1",
        savedAt: Date.now(),
      }),
    );
    const api = async (r, p, o = {}) => {
      if (p === "/v1/cases" && !o.method) return [];
      if (p.endsWith("/case-context")) return ctx;
      if (p === "/v1/cases" && o.method === "POST") {
        sent = JSON.parse(o.body);
        return {
          id: "p",
          houseId: "h",
          isAuthor: true,
          number: 1,
          status: "RECEIVED",
          kind: "PAYMENT",
          description: sent.description,
          documents: [],
          events: [],
          fullAccess: false,
        };
      }
      if (p === "/v1/cases/p")
        return {
          id: "p",
          houseId: "h",
          isAuthor: true,
          number: 1,
          status: "RECEIVED",
          kind: "PAYMENT",
          description: sent.description,
          documents: [],
          events: [],
          fullAccess: false,
        };
      throw Error(p);
    };
    function Harness() {
      const route = useRoute();
      return React.createElement(ResidentCases, {
        house,
        role: "true",
        api,
        headers: () => ({}),
        canSubmit: true,
        view: "cases",
        route,
      });
    }
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(React.createElement(Harness));
      await flush();
    });
    const month = document.querySelector('input[type="month"]');
    await act(async () => {
      month.value = "2026-10";
      month.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
      await flush();
    });
    assert.equal(JSON.parse(sessionStorage.getItem("pulse-case-v2:u:h")).period, "2026-10");
    await act(async () => {
      document
        .querySelector("form")
        .dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
      await flush();
    });
    assert.match(document.body.textContent, /Период: 2026-10/);
    const send = [...document.querySelectorAll("button")].find(
      (b) => b.textContent === "Отправить обращение",
    );
    await act(async () => {
      send.click();
      await flush();
    });
    assert.equal(sent.description, "Поясните начисление\nПериод: 2026-10");
    assert.equal(sent.clientRequestId, "payment-1");
    await act(async () => root.unmount());
    dom.window.close();
  });
  await test("profile uses authenticated name; roles are limited to actual access", async () => {
    const dom = env("/miniapp/roles");
    const api = async (r, p) =>
      ({
        "/v1/me/profile": { displayName: "Александр", fromMax: true },
        "/v1/access/me": [{ role: "RESIDENT" }],
        "/v1/access/config": { demoMode: false },
        "/v1/guided-demo": { active: false },
        "/v1/workflow/houses": [],
        "/v1/exchange/inbox": [],
      })[p];
    function Harness() {
      const route = useRoute();
      return React.createElement(AccountPages, {
        route,
        api,
        role: "true",
        houses: [house],
        selectedHouseId: "h",
      });
    }
    const root = createRoot(document.getElementById("root"));
    await act(async () => {
      root.render(React.createElement(Harness));
      await flush();
    });
    assert.match(document.body.textContent, /Александр/);
    assert.equal(document.body.textContent.includes("Диспетчер"), false);
    assert.equal(document.querySelectorAll(".page-row").length, 1);
    await act(async () => root.unmount());
    dom.window.close();
  });
} finally {
  await unlink(temp).catch(() => {});
}
