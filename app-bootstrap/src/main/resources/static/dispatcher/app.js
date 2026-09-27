const bridge = window.WebApp;
const initData = bridge?.initData || "";
const headers = initData ? { "X-Max-Init-Data": initData } : { "X-Demo-Session": "dispatcher" };
const $ = id => document.getElementById(id);
let current = null;
const transitions = {
  DRAFT: ["OPEN", "Принять заявку"],
  OPEN: ["ASSIGNED", "Назначить себе"],
  ASSIGNED: ["IN_PROGRESS", "Начать работу"],
  IN_PROGRESS: ["RESOLVED", "Отметить выполненной"],
  REOPENED: ["ASSIGNED", "Повторно назначить себе"],
};
const statusLabels = {
  DRAFT: "Новая", OPEN: "Принята", ASSIGNED: "Назначена",
  IN_PROGRESS: "В работе", VERIFICATION_72H: "Ожидает подтверждения",
  REOPENED: "Открыта повторно",
};

async function api(path, options = {}) {
  const response = await fetch(path, { ...options,
    headers: { ...headers, ...(options.body ? { "Content-Type": "application/json" } : {}) } });
  if (!response.ok) {
    if (response.status === 401) throw new Error("Откройте кабинет в MAX или включите локальный профиль demo.");
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || `Ошибка ${response.status}`);
  }
  return response.json();
}

function notice(message) { $("notice").textContent = message; $("notice").hidden = !message; }

async function loadQueue() {
  const houseId = $("house").value;
  if (!houseId) return;
  const issues = await api(`/v1/dispatcher/issues?houseId=${encodeURIComponent(houseId)}`);
  const list = $("issue-list");
  list.replaceChildren();
  if (!issues.length) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "Новых заявок пока нет.";
    list.append(empty);
  }
  for (const issue of issues) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "attachment-link";
    button.textContent = `${statusLabels[issue.status] || issue.status} · ${issue.location || "Место не указано"} · ${issue.description}`;
    button.addEventListener("click", () => showIssue(issue.id));
    list.append(button);
  }
}

async function showIssue(id) {
  current = await api(`/v1/dispatcher/issues/${encodeURIComponent(id)}`);
  $("issue-title").textContent = current.category || "Проблема дома";
  $("issue-address").textContent = current.address;
  $("issue-location").textContent = current.location ? `Место: ${current.location}` : "";
  $("issue-time").textContent = current.occurredAt ? `Замечено: ${new Date(current.occurredAt).toLocaleString("ru-RU")}` : "";
  $("issue-description").textContent = current.description;
  $("issue-status").textContent = statusLabels[current.status] || current.status;
  $("issue-participants").textContent = `Участников: ${current.participants}`;
  const attachments = $("issue-attachments");
  attachments.replaceChildren();
  for (const file of current.attachments || []) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "attachment-link";
    button.textContent = `Скачать вложение · ${file.mime}`;
    button.addEventListener("click", async () => {
      const response = await fetch(`/v1/attachments/${encodeURIComponent(file.id)}`, { headers });
      if (!response.ok) { notice("Не удалось открыть вложение"); return; }
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url;
      link.download = file.id;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    });
    attachments.append(button);
  }
  const transition = transitions[current.status];
  $("next-status").hidden = !transition;
  if (transition) $("next-status").textContent = transition[1];
  $("issue-detail").hidden = false;
}

$("next-status").addEventListener("click", async () => {
  if (!current) return;
  const reason = $("reason").value.trim();
  if (!reason) { notice("Укажите причину изменения статуса."); return; }
  const button = $("next-status");
  button.disabled = true;
  try {
    await api(`/v1/issues/${encodeURIComponent(current.id)}/status`, {
      method: "PATCH", body: JSON.stringify({ status: transitions[current.status][0], reason }),
    });
    $("reason").value = "";
    notice("");
    await loadQueue();
    await showIssue(current.id);
  } catch (error) { notice(error.message); }
  finally { button.disabled = false; }
});

$("house").addEventListener("change", () => {
  $("issue-detail").hidden = true;
  loadQueue().catch(error => notice(error.message));
});
$("refresh-queue").addEventListener("click", () => loadQueue().catch(error => notice(error.message)));

api("/v1/me/houses").then(houses => {
  const select = $("house");
  select.replaceChildren();
  for (const house of houses) select.add(new Option(house.address, house.id));
  if (houses.length) return loadQueue();
}).catch(error => notice(error.message));
