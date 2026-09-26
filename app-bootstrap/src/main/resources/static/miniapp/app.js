const bridge = window.WebApp;
const initData = bridge?.initData || "";
const headers = initData ? { "X-Max-Init-Data": initData } : { "X-Demo-Session": "true" };
const $ = (id) => document.getElementById(id);
let reportId = null;
let reportData = null;
let uploaded = 0;

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { ...headers, ...(options.body && !(options.body instanceof FormData)
      ? { "Content-Type": "application/json" } : {}) },
  });
  if (!response.ok) {
    if (response.status === 401) throw new Error("Откройте приложение в MAX. Для локального просмотра запустите сервер с профилем demo.");
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `Ошибка ${response.status}`);
  }
  return response.json();
}

function notice(message) {
  $("notice").textContent = message;
  $("notice").hidden = !message;
}

function step(name) {
  for (const id of ["form-step", "decision-step", "result-step"]) $(id).hidden = id !== name;
  window.scrollTo({ top: 0, behavior: "smooth" });
}

async function loadHouses() {
  const invitation = new URLSearchParams(location.search).get("invite") || bridge?.initDataUnsafe?.start_param;
  if (invitation) await api(`/v1/invitations/${encodeURIComponent(invitation)}/accept`, { method: "POST" });
  const houses = await api("/v1/me/houses");
  const select = $("house");
  select.replaceChildren(new Option("Выберите дом", ""));
  for (const house of houses) select.add(new Option(house.address, house.id));
  if (houses.length === 1) select.value = houses[0].id;
  if (!houses.length) notice("У вас пока нет подтверждённого доступа к дому. Попросите приглашение у администратора.");
}

function showResult(issue) {
  $("result-title").textContent = issue.status === "DRAFT" ? "Новая заявка сохранена" : "Вы присоединились к заявке";
  $("result-address").textContent = issue.address;
  $("result-location").textContent = issue.location ? `Место: ${issue.location}` : "";
  $("result-time").textContent = issue.occurredAt ? `Замечено: ${new Date(issue.occurredAt).toLocaleString("ru-RU")}` : "";
  $("result-description").textContent = issue.description;
  $("result-status").textContent = issue.status === "DRAFT" ? "Ожидает диспетчера" : issue.status;
  $("result-participants").textContent = `Участников: ${issue.participants}`;
  const files = $("result-attachments");
  files.replaceChildren();
  for (const attachment of issue.attachments || []) {
    const button = document.createElement("button");
    button.className = "attachment-link";
    button.type = "button";
    button.textContent = `Открыть вложение · ${attachment.mime}`;
    button.addEventListener("click", async () => {
      const response = await fetch(`/v1/attachments/${encodeURIComponent(attachment.id)}`, { headers });
      if (!response.ok) { notice("Не удалось открыть вложение"); return; }
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url;
      link.download = attachment.id;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    });
    files.append(button);
  }
  notice("");
  step("result-step");
}

async function decide(path, body) {
  const button = $("new-issue");
  button.disabled = true;
  try {
    showResult(await api(path, { method: "POST", body: JSON.stringify(body) }));
  } catch (error) {
    notice(error.message);
  } finally {
    button.disabled = false;
  }
}

$("report-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = event.currentTarget.querySelector("button");
  button.disabled = true;
  notice("");
  try {
    const files = [...$("attachments").files];
    if (files.length > 5 || files.some(file => file.size > 10 * 1024 * 1024)) {
      throw new Error("Можно прикрепить до 5 файлов по 10 МБ");
    }
    let report;
    if (!reportId) {
      report = await api("/v1/reports", {
        method: "POST",
        body: JSON.stringify({ houseId: $("house").value, category: $("category").value,
          location: $("location").value.trim(), occurredAt: new Date($("occurred-at").value).toISOString(),
          text: $("description").value.trim() }),
      });
      reportId = report.reportId;
      reportData = report;
      for (const field of ["house", "category", "location", "occurred-at", "description"]) $(field).disabled = true;
    } else {
      report = reportData;
    }
    for (let index = uploaded; index < files.length; index++) {
      const body = new FormData();
      body.append("file", files[index]);
      await api(`/v1/reports/${encodeURIComponent(reportId)}/attachments`, { method: "POST", body });
      uploaded = index + 1;
    }
    const container = $("candidates");
    container.replaceChildren();
    if (!report.candidates.length) {
      const message = document.createElement("p");
      message.className = "muted";
      message.textContent = "Похожих активных заявок не найдено.";
      container.append(message);
    }
    for (const candidate of report.candidates) {
      const issue = await api(`/v1/issues/${encodeURIComponent(candidate.issueId)}`);
      const card = document.createElement("article");
      card.className = "candidate";
      const title = document.createElement("strong");
      title.textContent = issue.description || issue.category || "Проблема дома";
      const details = document.createElement("p");
      details.textContent = `${issue.address} · ${issue.participants} участников`;
      const score = document.createElement("span");
      score.className = "score";
      score.textContent = `${Math.round(candidate.score * 100)}% совпадение`;
      const join = document.createElement("button");
      join.type = "button";
      join.textContent = "Присоединиться";
      join.addEventListener("click", () => decide(`/v1/issues/${encodeURIComponent(issue.id)}/join`, { reportId }));
      card.append(score, title, details, join);
      container.append(card);
    }
    step("decision-step");
  } catch (error) {
    notice(error.message);
  } finally {
    button.disabled = false;
  }
});

$("new-issue").addEventListener("click", () => decide("/v1/issues", { reportId }));
$("again").addEventListener("click", () => {
  reportId = null;
  reportData = null;
  uploaded = 0;
  for (const field of ["house", "category", "location", "occurred-at", "description"]) $(field).disabled = false;
  $("report-form").reset();
  step("form-step");
});
const localNow = new Date(Date.now() - new Date().getTimezoneOffset() * 60000);
$("occurred-at").value = localNow.toISOString().slice(0, 16);
loadHouses().catch((error) => notice(error.message));
