import React from "react";
function Field({ label, children }) {
  return (
    <label>
      {label}
      {children}
    </label>
  );
}
const placeAreas = {
  ENTRANCE: "Подъезд",
  APARTMENT: "В квартире",
  BASEMENT: "Подвал",
  YARD: "Двор",
  WHOLE_HOUSE: "Весь дом",
  OTHER: "Другое место",
};
export function defaultPlace(category) {
  if (category === "ELEVATOR") return { area: "ENTRANCE", liftType: "UNKNOWN" };
  if (category === "ENTRANCE_CLEANING") return { area: "ENTRANCE", coverage: "ENTIRE" };
  if (["YARD_CLEANING", "WASTE_REMOVAL", "PLAYGROUND"].includes(category)) return { area: "YARD" };
  return { area: ["WATER", "HEATING"].includes(category) ? "APARTMENT" : "ENTRANCE" };
}
export function PlaceFields({ category, value, onChange, passport, disabled }) {
  if (!category) return <p className="hint">Выберите категорию — покажем нужные поля места.</p>;
  const update = (key, next) => onChange((current) => ({ ...current, [key]: next }));
  const count = (key) => {
    const n = Number(passport?.fields?.find((field) => field.key === key)?.value);
    return Number.isInteger(n) && n > 0 && n <= 200 ? n : null;
  };
  const numberField = (key, label, total, required = true) => (
    <Field key={key} id={`place-${key}`} label={label}>
      {total && total <= 30 ? (
        <select
          id={`place-${key}`}
          required={required}
          disabled={disabled}
          value={value[key] ?? ""}
          onChange={(event) => update(key, event.target.value ? Number(event.target.value) : null)}
        >
          <option value="">{required ? "Выберите номер" : "Не указан"}</option>
          {Array.from({ length: total }, (_, i) => (
            <option key={i + 1} value={i + 1}>
              {i + 1}
            </option>
          ))}
        </select>
      ) : (
        <input
          id={`place-${key}`}
          type="number"
          inputMode="numeric"
          min="1"
          max={total || (key === "floor" ? 200 : 100)}
          required={required}
          disabled={disabled}
          value={value[key] ?? ""}
          onChange={(event) => update(key, event.target.value ? Number(event.target.value) : null)}
          placeholder={required ? "Введите номер" : "Необязательно"}
        />
      )}
    </Field>
  );
  const areas =
    category === "LIGHTING"
      ? ["ENTRANCE", "YARD"]
      : category === "WATER"
        ? ["APARTMENT", "ENTRANCE", "BASEMENT", "YARD"]
        : category === "HEATING"
          ? ["APARTMENT", "ENTRANCE", "WHOLE_HOUSE"]
          : category === "OTHER"
            ? ["ENTRANCE", "YARD", "BASEMENT", "WHOLE_HOUSE", "OTHER"]
            : [];
  const indoors = ["ENTRANCE", "APARTMENT"].includes(value.area);
  const needFloor =
    indoors &&
    category !== "ELEVATOR" &&
    (category !== "ENTRANCE_CLEANING" || value.coverage === "LOCAL");
  return (
    <div className="place-fields">
      {!!areas.length && (
        <Field id="place-area" label="Где находится проблема?">
          <select
            id="place-area"
            value={value.area}
            disabled={disabled}
            onChange={(event) => onChange({ area: event.target.value })}
          >
            {areas.map((area) => (
              <option key={area} value={area}>
                {placeAreas[area]}
              </option>
            ))}
          </select>
        </Field>
      )}
      {indoors && numberField("entrance", "Подъезд", count("entrance_count"))}
      {category === "ENTRANCE_CLEANING" && (
        <Field id="place-coverage" label="Область уборки">
          <select
            id="place-coverage"
            disabled={disabled}
            value={value.coverage}
            onChange={(event) =>
              onChange((current) => ({ ...current, coverage: event.target.value, floor: null }))
            }
          >
            <option value="ENTIRE">Весь подъезд</option>
            <option value="LOCAL">Конкретный этаж</option>
          </select>
        </Field>
      )}
      {needFloor && numberField("floor", "Этаж", count("floor_count"), category !== "OTHER")}
      {category === "ELEVATOR" && (
        <>
          <Field id="place-lift-type" label="Тип лифта">
            <select
              id="place-lift-type"
              value={value.liftType}
              disabled={disabled}
              onChange={(event) => update("liftType", event.target.value)}
            >
              <option value="UNKNOWN">Не знаю</option>
              <option value="PASSENGER">Пассажирский</option>
              <option value="CARGO">Грузовой</option>
            </select>
          </Field>
          {numberField("liftNumber", "Номер лифта, если одинаковых несколько", null, false)}
        </>
      )}
      {category === "WASTE_REMOVAL" && (
        <>
          {numberField("site", "Номер мусорной площадки, если их несколько", null, false)}
          <p className="hint">Если площадка одна или её номер неизвестен, оставьте поле пустым.</p>
        </>
      )}
      {category === "PLAYGROUND" && (
        <Field id="place-object" label="Объект площадки — необязательно">
          <select
            id="place-object"
            disabled={disabled}
            value={value.object || ""}
            onChange={(event) => update("object", event.target.value || null)}
          >
            <option value="">Не указан</option>
            <option value="SWING">Качели</option>
            <option value="SLIDE">Горка</option>
            <option value="SANDBOX">Песочница</option>
            <option value="WHOLE_PLAYGROUND">Вся площадка</option>
            <option value="OTHER">Другой объект</option>
          </select>
        </Field>
      )}
      {category === "OTHER" && value.area === "OTHER" && (
        <Field id="place-details" label="Уточните место">
          <input
            id="place-details"
            maxLength="160"
            required
            disabled={disabled}
            value={value.details || ""}
            onChange={(event) => update("details", event.target.value)}
            placeholder="Например, вход со стороны дороги"
          />
        </Field>
      )}
      {["YARD_CLEANING", "PLAYGROUND"].includes(category) && (
        <p className="hint">
          Дом уже выбран. Опишите проблему ниже, чтобы отличить её от других обращений.
        </p>
      )}
    </div>
  );
}

export const problemCategories = {
  LIGHTING: "Освещение",
  WATER: "Вода",
  HEATING: "Отопление",
  ELEVATOR: "Лифт",
  ENTRANCE_CLEANING: "Уборка подъезда",
  YARD_CLEANING: "Уборка двора",
  WASTE_REMOVAL: "Вывоз мусора",
  PLAYGROUND: "Детская площадка",
  OTHER: "Другое",
};

export function placeDescription(value) {
  if (!value) return "Пока не определено";
  return [
    value.area === "ENTRANCE"
      ? `Подъезд${value.entrance ? ` ${value.entrance}` : ""}`
      : placeAreas[value.area],
    value.area !== "ENTRANCE" && value.entrance ? `подъезд ${value.entrance}` : null,
    value.floor ? `этаж ${value.floor}` : null,
    value.liftType
      ? { PASSENGER: "пассажирский лифт", CARGO: "грузовой лифт", UNKNOWN: "тип лифта не указан" }[
          value.liftType
        ]
      : null,
    value.liftNumber ? `лифт № ${value.liftNumber}` : null,
    value.coverage === "ENTIRE" ? "весь подъезд" : null,
    value.site ? `площадка № ${value.site}` : null,
    {
      SWING: "качели",
      SLIDE: "горка",
      SANDBOX: "песочница",
      WHOLE_PLAYGROUND: "вся площадка",
      OTHER: "другой объект",
    }[value.object],
    value.details,
  ]
    .filter(Boolean)
    .join(", ");
}
