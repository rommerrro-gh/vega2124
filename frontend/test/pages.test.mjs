import test from "node:test";
import assert from "node:assert/strict";
import { parseRoute, parentPath, routeUrl } from "../src/page-router.mjs";
test("direct case and visit addresses retain the correct parent", () => {
  const r = parseRoute("/miniapp/cases/a/visits?demoSession=true");
  assert.equal(r.id, "a");
  assert.equal(r.section, "visits");
  assert.equal(r.form, true);
  assert.equal(parentPath(r), "/miniapp/cases/a");
  assert.equal(parentPath(parseRoute("/miniapp/cases/a")), "/miniapp/cases");
});
test("theme and professional profile have real routes, legacy links still resolve", () => {
  assert.equal(parseRoute("/dispatcher/settings").account, true);
  assert.equal(parseRoute("/miniapp/profile").root, true);
  assert.equal(parseRoute("/miniapp/index.html?caseId=a").id, "a");
  assert.equal(parentPath(parseRoute("/miniapp/settings")), "/miniapp/profile");
});
test("navigation preserves MAX launch data and actor without carrying old page filters", () => {
  assert.equal(
    routeUrl(
      "/miniapp/profile",
      "https://pulsedoma.ru/miniapp/cases?demoSession=admin&filter=ACTIVE#WebAppData=abc",
    ),
    "/miniapp/profile?demoSession=admin#WebAppData=abc",
  );
  assert.throws(() => routeUrl("https://elsewhere.example", "https://pulsedoma.ru"));
});
