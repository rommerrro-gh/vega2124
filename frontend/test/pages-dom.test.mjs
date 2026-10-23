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
      'export {ResidentCases} from "./src/resident-cases.jsx";export {AccountPages} from "./src/account-pages.jsx";export {useRoute,go} from "./src/navigation.jsx";',
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
const { ResidentCases, AccountPages, useRoute, go } = await import(temp.href);
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
