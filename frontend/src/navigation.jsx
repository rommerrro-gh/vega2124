import { useEffect, useState } from "react";
import { parseRoute, parentPath, routeUrl } from "./page-router.mjs";
const positions = new Map();
let serial = 0;
function tabKey(path) {
  return (
    "pulse-page:" +
    path +
    ":" +
    (window.WebApp?.initDataUnsafe?.user?.id ||
      new URLSearchParams(location.search).get("demoSession") ||
      "default")
  );
}
export function goTab(path) {
  let query = "";
  try {
    query = sessionStorage.getItem(tabKey(path)) || "";
  } catch {}
  go(path + query);
}
export function go(path, { replace = false } = {}) {
  positions.set(location.pathname + location.search, window.scrollY);
  const current = parseRoute(location.href);
  if (current.root || (current.workspace !== "miniapp" && current.page === "queue")) {
    const query = new URLSearchParams(location.search);
    query.delete("caseId");
    query.delete("demoSession");
    try {
      sessionStorage.setItem(tabKey(location.pathname), query.size ? "?" + query.toString() : "");
    } catch {}
  }
  const url = routeUrl(path, location.href);
  if (url === location.pathname + location.search + location.hash) return;
  const state = {
    ...history.state,
    pulseNavigation: replace ? history.state?.pulseNavigation || 0 : ++serial,
  };
  history[replace ? "replaceState" : "pushState"](state, "", url);
  window.dispatchEvent(new Event("pulse-route"));
  requestAnimationFrame(() => {
    window.scrollTo(
      0,
      replace ? window.scrollY : positions.get(location.pathname + location.search) || 0,
    );
    if (!replace) document.querySelector("h1")?.focus({ preventScroll: true });
  });
}
export function back() {
  if (history.state?.pulseNavigation > 0) history.back();
  else go(parentPath(parseRoute(location.href)), { replace: true });
}
export function useRoute() {
  const [route, setRoute] = useState(() => parseRoute(location.href));
  useEffect(() => {
    const update = () => setRoute(parseRoute(location.href));
    const pop = () => {
      update();
      requestAnimationFrame(() =>
        window.scrollTo(0, positions.get(location.pathname + location.search) || 0),
      );
    };
    window.addEventListener("pulse-route", update);
    window.addEventListener("popstate", pop);
    return () => {
      window.removeEventListener("pulse-route", update);
      window.removeEventListener("popstate", pop);
    };
  }, []);
  return route;
}

export function useMaxBack(route) {
  useEffect(() => {
    const button = window.WebApp?.BackButton;
    if (!button) return;
    const show =
      route.workspace === "miniapp"
        ? !route.root
        : !["queue", "work", "control", "profile"].includes(route.page);
    show ? button.show?.() : button.hide?.();
    button.onClick?.(back);
    return () => button.offClick?.(back);
  }, [route.key]);
}
