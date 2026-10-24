import React, { useEffect, useState } from "react";
import {
  House,
  UserCircle,
  User,
  ChatCircleDots,
  CalendarBlank,
  NotePencil,
  Receipt,
  ArrowRight,
  Question,
  CheckCircle,
  Clock,
  Circle,
} from "@phosphor-icons/react";
import { housePresentation, resolveAppearance } from "./design-model.mjs";
export { housePresentation } from "./design-model.mjs";
export { NotePencil, Receipt, ArrowRight, Question, CheckCircle, Clock, Circle, CalendarBlank };

const KEY = "pulse-appearance";
function savedTheme() {
  try {
    const value = localStorage.getItem(KEY);
    return ["light", "dark"].includes(value) ? value : "system";
  } catch {
    return "system";
  }
}
let themeChoice = savedTheme();
function resolvedTheme(value) {
  return resolveAppearance(
    value,
    window.WebApp?.colorScheme,
    matchMedia("(prefers-color-scheme: dark)").matches,
  );
}
function applyTheme() {
  const choice = themeChoice,
    resolved = resolvedTheme(choice);
  document.documentElement.dataset.theme = resolved;
  document.documentElement.style.colorScheme = resolved;
  return { choice, resolved };
}
applyTheme();
export function useAppearance() {
  const [theme, setTheme] = useState(applyTheme);
  useEffect(() => {
    const update = () => setTheme(applyTheme()),
      media = matchMedia("(prefers-color-scheme: dark)");
    const storageUpdate = () => {
      themeChoice = savedTheme();
      update();
    };
    media.addEventListener("change", update);
    window.addEventListener("pulse-theme", update);
    window.addEventListener("storage", storageUpdate);
    window.WebApp?.onEvent?.("themeChanged", update);
    return () => {
      media.removeEventListener("change", update);
      window.removeEventListener("pulse-theme", update);
      window.removeEventListener("storage", storageUpdate);
      window.WebApp?.offEvent?.("themeChanged", update);
    };
  }, []);
  return theme;
}
export function AppearancePicker({ variant = "select" }) {
  const { choice } = useAppearance();
  function change(value) {
    themeChoice = value;
    try {
      value === "system" ? localStorage.removeItem(KEY) : localStorage.setItem(KEY, value);
    } catch {}
    applyTheme();
    window.dispatchEvent(new Event("pulse-theme"));
  }
  const options = [
    ["system", "Как в MAX / системе"],
    ["light", "Светлая"],
    ["dark", "Тёмная"],
  ];
  if (variant === "cards")
    return (
      <fieldset className="appearance-options" aria-label="Оформление">
        {options.map(([value, label]) => (
          <label key={value} className={choice === value ? "selected" : ""}>
            <input
              type="radio"
              name="appearance"
              value={value}
              checked={choice === value}
              onChange={() => change(value)}
            />
            <span>{label}</span>
          </label>
        ))}
      </fieldset>
    );
  return (
    <label className="appearance-picker">
      Оформление
      <select aria-label="Оформление" value={choice} onChange={(e) => change(e.target.value)}>
        {options.map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>
    </label>
  );
}

export function ResidentHeader({ house, loading = false }) {
  const { resolved } = useAppearance(),
    view = housePresentation(house);
  return (
    <section
      className={`home-header ${view.street.length > 24 || view.city.length > 20 ? "home-header-long" : ""}`}
      aria-labelledby="home-address"
    >
      <img
        className="home-art"
        src={`/miniapp/art/${view.art}-${resolved}.webp`}
        alt=""
        fetchPriority="high"
      />
      <div className="home-address">
        <span className="eyebrow">Ваш дом</span>
        <h1 id="home-address">{loading ? "Загружаем дом…" : view.street || "Добро пожаловать"}</h1>
        {view.city && <p>{view.city}</p>}
        {!loading && !house && <p>Подключите свой дом, чтобы видеть обращения и события.</p>}
      </div>
    </section>
  );
}
export function ResidentNavigation({ value, onChange, disabled }) {
  return (
    <nav className="resident-navigation" aria-label="Разделы жителя">
      {[
        ["home", "Дом", House],
        ["cases", "Обращения", ChatCircleDots],
        ["events", "События", CalendarBlank],
        ["profile", "Профиль", User],
      ].map(([id, label, Icon]) => (
        <button
          type="button"
          key={id}
          disabled={disabled}
          aria-current={value === id ? "page" : undefined}
          onClick={() => onChange(id)}
        >
          <Icon size={25} weight={value === id ? "fill" : "regular"} aria-hidden="true" />
          <span>{label}</span>
        </button>
      ))}
    </nav>
  );
}
