const residentRoots = new Set(["home", "cases", "events", "profile"]);
const accountPages = new Set([
  "profile",
  "settings",
  "houses",
  "roles",
  "notifications",
  "help",
  "about",
]);
export function parseRoute(url) {
  const u = new URL(url, "https://pulsedoma.ru");
  const parts = u.pathname.split("/").filter(Boolean);
  const workspace = [
    "dispatcher",
    "work",
    "admin",
    "uk",
    "system",
    "exchange",
    "organization",
  ].includes(parts[0])
    ? parts[0]
    : "miniapp";
  let page = parts[1] || (workspace === "miniapp" ? "home" : "queue");
  if (page === "index.html")
    page = workspace === "miniapp" ? (u.searchParams.has("caseId") ? "cases" : "home") : "queue";
  const id = parts[2] || u.searchParams.get("caseId") || null;
  return {
    workspace,
    page,
    id,
    section: parts[3] || "overview",
    account: accountPages.has(page),
    root: workspace === "miniapp" && residentRoots.has(page) && !id,
    form: ["new", "payment", "similar"].includes(page) || parts[3] === "visits",
    key: u.pathname + u.search,
    query: u.searchParams,
  };
}
export function isWorkspaceRoot(route) {
  const pages = {
    organization: [
      "overview",
      "team",
      "partners",
      "cases",
      "tasks",
      "documents",
      "control",
      "profile",
    ],
    system: ["queue", "houses", "access", "profile"],
    admin: ["queue", "residents", "events", "profile"],
  };
  return (
    !route.id &&
    (pages[route.workspace] || ["queue", "work", "control", "profile"]).includes(route.page)
  );
}
export function parentPath(route) {
  const base = `/${route.workspace}`;
  if (route.workspace !== "miniapp" && route.page === "queue" && route.query.has("caseId"))
    return `${base}/queue`;
  if (route.id)
    return route.section !== "overview"
      ? `${base}/${route.page}/${encodeURIComponent(route.id)}`
      : `${base}/${route.page === "shared" ? "cases" : route.page === "polls" || route.page === "notices" ? "events" : "cases"}`;
  if (route.page === "similar") return `${base}/new`;
  if (route.account && route.page !== "profile") return `${base}/profile`;
  if (route.workspace === "organization")
    return `${base}/${(route.query.get("workspaceRole") || "ADMIN") === "ADMIN" ? "overview" : "cases"}`;
  return `${base}/${route.workspace === "miniapp" ? "home" : "queue"}`;
}
export function routeUrl(path, current) {
  const old = new URL(current, "https://pulsedoma.ru"),
    next = new URL(path, old);
  if (next.origin !== old.origin) throw new Error("Разрешены только страницы приложения");
  if (!next.searchParams.has("demoSession") && old.searchParams.has("demoSession"))
    next.searchParams.set("demoSession", old.searchParams.get("demoSession"));
  if (next.pathname.startsWith("/organization/") && old.pathname.startsWith("/organization/")) {
    for (const key of ["organizationId", "workspaceRole"])
      if (!next.searchParams.has(key) && old.searchParams.has(key))
        next.searchParams.set(key, old.searchParams.get(key));
  }
  if (!next.hash) next.hash = old.hash;
  return next.pathname + next.search + next.hash;
}
