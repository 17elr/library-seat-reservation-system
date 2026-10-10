"use strict";
const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const labels = {
  RESERVED: "待签到",
  CHECKED_IN: "已签到",
  TEMP_LEFT: "暂离中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  NO_SHOW: "未签到超时",
  LEAVE_TIMEOUT: "暂离超时",
  ADMIN_CLOSED: "管理关闭",
};
const active = ["RESERVED", "CHECKED_IN", "TEMP_LEFT"];
const state = {
  boot: null,
  route: "home",
  page: 1,
  status: "",
  search: "",
  date: "",
  roomId: null,
  selected: null,
  booking: null,
  sequence: 0,
  socket: false,
  window: false,
  seatSearch: "",
  bookVersion: 0,
};
const content = $("#content");
let serverEpoch = 0,
  receivedAt = 0,
  toastTimer,
  detailTimer,
  detailVersion = 0;
function serverLocal() {
  return new Date(serverEpoch + Date.now() - receivedAt + 8 * 3600000)
    .toISOString()
    .slice(0, 19);
}
function today() {
  return serverLocal().slice(0, 10);
}
function dateAdd(date, days) {
  return new Date(Date.parse(date + "T00:00:00Z") + days * 86400000)
    .toISOString()
    .slice(0, 10);
}
function stamp(value) {
  return value ? esc(value.replace("T", " ").slice(0, 16)) : "—";
}
function time(value) {
  return value ? esc(value.slice(11, 16)) : "—";
}
function badge(status) {
  return `<span class="status ${esc(status)}">${esc(labels[status] || status)}</span>`;
}
function notify(message, error = false) {
  const t = $("#toast");
  t.textContent = message;
  t.className = "toast" + (error ? " error" : "");
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 5000);
}
async function api(path, options = {}) {
  const headers = { Accept: "application/json", ...options.headers };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(options.body);
  }
  if (options.method && options.method !== "GET")
    headers[$('meta[name="_csrf_header"]').content] =
      $('meta[name="_csrf"]').content;
  let response;
  try {
    response = await fetch("/api" + path, { ...options, headers });
  } catch (error) {
    throw new Error("网络连接中断，请重试；预约提交重试会沿用同一个请求标识。");
  }
  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch (error) {
    throw new Error("服务器响应异常，请刷新后重试。");
  }
  if (!response.ok) {
    if (response.status === 401) {
      location.href = "/login";
      throw new Error("请重新登录");
    }
    const error = new Error(data.message || "操作失败，请重试");
    error.status = response.status;
    throw error;
  }
  return data;
}
function syncClock(now) {
  serverEpoch = Date.parse(now + "+08:00");
  receivedAt = Date.now();
}
async function refreshBoot() {
  state.boot = await api("/bootstrap");
  syncClock(state.boot.now);
}
const studentNav = [
  ["home", "我的工作台"],
  ["book", "预约座位"],
  ["reservations", "我的预约"],
  ["violations", "违约记录"],
  ["notices", "公告与规则"],
  ["profile", "个人信息"],
];
const adminNav = [
  ["home", "管理概览"],
  ["rooms", "阅览室管理"],
  ["seats", "座位管理"],
  ["reservations", "预约管理"],
  ["users", "学生管理"],
  ["violations", "违约管理"],
  ["notices", "公告管理"],
  ["rules", "预约规则"],
  ["audits", "操作日志"],
  ["profile", "个人信息"],
];
function isAdmin() {
  return state.boot.user.role === "ADMIN";
}
function header(title, description, action = "") {
  return `<div class="heading"><div><h1>${esc(title)}</h1><p class="muted">${esc(description)}</p></div>${action}</div>`;
}
function empty(title, text = "") {
  return `<div class="empty"><strong>${esc(title)}</strong><p>${esc(text)}</p></div>`;
}
function pager(data) {
  const pages = Math.max(1, Math.ceil(data.total / data.size));
  return `<div class="pager"><span>共 ${data.total} 条记录</span><div><button data-page="${data.page - 1}" ${data.page <= 1 ? "disabled" : ""}>上一页</button><span>${data.page} / ${pages}</span><button data-page="${data.page + 1}" ${data.page >= pages ? "disabled" : ""}>下一页</button></div></div>`;
}
function bindPager() {
  $$("[data-page]").forEach(
    (b) =>
      (b.onclick = () => {
        state.page = Number(b.dataset.page);
        route();
      }),
  );
}
function modal(title, html, { wide = false } = {}) {
  clearInterval(detailTimer);
  detailVersion++;
  const d = $("#modal");
  d.dataset.busy = "false";
  d.classList.toggle("wide", wide);
  $("#modal-content").innerHTML =
    `<div class="modal-head"><h2>${esc(title)}</h2><button type="button" aria-label="关闭对话框" id="modal-close">×</button></div>${html}`;
  $("#modal-close").onclick = () => d.close();
  if (!d.open) d.showModal();
}
function field(label, name, value = "", type = "text", extra = "") {
  return `<label>${esc(label)}<input name="${name}" type="${type}" value="${esc(value)}" ${extra}></label>`;
}
function checkfield(label, name, value) {
  return `<label class="checkbox"><input type="checkbox" name="${name}" ${value ? "checked" : ""}>${esc(label)}</label>`;
}
function formValues(form) {
  const values = Object.fromEntries(new FormData(form));
  $$("input[type=checkbox]", form).forEach((i) => (values[i.name] = i.checked));
  $$("input[type=number]", form).forEach(
    (i) => (values[i.name] = Number(i.value)),
  );
  return values;
}
function formModal(title, fields, save, help = "") {
  modal(
    title,
    `<form id="edit-form"><div class="form-grid">${fields}</div>${help ? `<p class="muted small">${esc(help)}</p>` : ""}<p class="inline-error" id="form-error" role="alert"></p><div class="form-actions"><button type="button" id="form-cancel">取消</button><button class="primary" type="submit">保存</button></div></form>`,
  );
  $("#form-cancel").onclick = () => $("#modal").close();
  $("#edit-form").onsubmit = async (e) => {
    e.preventDefault();
    const button = $("button[type=submit]", e.target);
    button.disabled = true;
    $("#form-error").textContent = "";
    try {
      await save(formValues(e.target));
      $("#modal").close();
      notify("操作已保存");
      await refreshBoot();
      await route();
    } catch (error) {
      $("#form-error").textContent = error.message;
    } finally {
      button.disabled = false;
    }
  };
}
function reasonModal(title, save, help = "") {
  formModal(
    title,
    `<label class="full">操作原因<textarea name="reason" required maxlength="200" placeholder="填写原因，方便学生和管理员追溯"></textarea></label>`,
    save,
    help,
  );
}
async function route() {
  const seq = ++state.sequence;
  state.route = location.hash.slice(1) || "home";
  document.body.dataset.route = state.route;
  const nav = isAdmin() ? adminNav : studentNav;
  if (!nav.some((n) => n[0] === state.route)) {
    location.hash = "home";
    return;
  }
  $$("#navigation a").forEach((a) =>
    a.classList.toggle("active", a.hash === "#" + state.route),
  );
  $("#breadcrumb").textContent = nav.find((n) => n[0] === state.route)[1];
  content.innerHTML = '<p class="loading">正在加载…</p>';
  try {
    const view = {
      home: isAdmin() ? adminHome : studentHome,
      book: bookPage,
      reservations: reservationPage,
      violations: violationPage,
      notices: noticePage,
      profile: profilePage,
      rooms: roomPage,
      seats: seatAdminPage,
      users: userPage,
      rules: rulePage,
      audits: auditPage,
    }[state.route];
    await view(seq);
  } catch (error) {
    if (seq === state.sequence) {
      content.innerHTML =
        header("暂时无法加载", error.message) +
        '<button class="primary" id="retry">重新加载</button>';
      $("#retry").onclick = route;
    }
  }
}
function current(seq) {
  return seq === state.sequence;
}
async function studentHome(seq) {
  await refreshBoot();
  if (!current(seq)) return;
  const r = state.boot.currentReservation;
  const q = state.boot.rule;
  content.innerHTML =
    header(
      "我的工作台",
      state.boot.user.name + "，欢迎使用图书馆座位预约服务。",
    ) +
    `<section class="study-banner"><div class="study-banner-copy"><h2>预约自习座位</h2><p>选择日期与时段，查看各阅览室可用座位。</p><a class="primary action-link" href="#book">去预约</a><span class="banner-note">到馆后签到，离馆前签退</span></div><figure><img src="/images/library-windows.webp" alt="自然采光的图书馆阅读空间" width="955" height="1440"><figcaption>学习空间 · 示意图片</figcaption></figure></section>` +
    `<div class="metrics"><div class="metric"><p>开放阅览室</p><strong>${state.boot.rooms.filter((x) => x.enabled).length}<span class="small"> 间</span></strong><small>按开放安排提供预约</small></div><div class="metric"><p>启用座位</p><strong>${state.boot.rooms.filter((x) => x.enabled).reduce((s, r) => s + r.seatCount, 0)}<span class="small"> 个</span></strong><small>按所选时段查询可用情况</small></div><div class="metric"><p>可预约日期</p><strong>${q.advanceDays + 1}<span class="small"> 天</span></strong><small>包含今日</small></div><div class="metric"><p>最长预约</p><strong>${q.maxDurationMinutes / 60}<span class="small"> 小时</span></strong><small>起止时间按半小时选择</small></div></div>` +
    `<div class="two-column"><section class="panel"><div class="panel-title"><h2>当前预约</h2><a class="muted small" href="#reservations">查看全部</a></div>${r ? `<div class="record-card"><div class="record-top"><strong>${esc(r.seatNo)}</strong>${badge(r.status)}</div><p>${esc(r.roomName)}</p><p>${stamp(r.startTime)} 至 ${time(r.endTime)}</p><p class="muted">${esc(reservationHint(r, state.boot.currentRule))}</p><div class="actions"><button class="primary small" data-detail="${r.id}">查看预约</button></div></div>` : empty("暂无进行中的预约", "选好日期与时段，即可预约座位。")}</section><section class="panel"><div class="panel-title"><h2>图书馆公告</h2><a class="muted small" href="#notices">全部公告</a></div>${noticeList(state.boot.notices.slice(0, 2))}</section></div>`;
  bindDetails();
}
function noticeList(notices) {
  return notices.length
    ? notices
        .map(
          (n) =>
            `<article class="notice"><h3>${esc(n.title)}</h3><p>${esc(n.content)}</p><time>${stamp(n.createdAt)}</time></article>`,
        )
        .join("")
    : empty("暂无公告");
}
function clockMinutes(value) {
  const [h, m] = value.split(":").map(Number);
  return h * 60 + m;
}
function clockText(minutes) {
  return (
    String(Math.floor(minutes / 60)).padStart(2, "0") +
    ":" +
    String(minutes % 60).padStart(2, "0")
  );
}
function plusMinutes(value, minutes) {
  return new Date(Date.parse(value + "+08:00") + minutes * 60000 + 8 * 3600000)
    .toISOString()
    .slice(0, 19);
}
function reservationHint(r, q) {
  if (r.status === "TEMP_LEFT")
    return (
      "请在 " + r.leaveDeadline.replace("T", " ").slice(0, 16) + " 前返回。"
    );
  if (r.status === "RESERVED")
    return (
      "签到时间：" +
      time(plusMinutes(r.startTime, -q.checkinEarlyMinutes)) +
      "—" +
      time(plusMinutes(r.startTime, q.checkinGraceMinutes)) +
      "，到馆后完成签到。"
    );
  return r.startTime > serverLocal()
    ? "已签到，等待 " + time(r.startTime) + " 开始使用。"
    : "正在使用，离馆前请签退。";
}
function bookingRestriction() {
  if (state.boot.currentReservation)
    return "您已有一条有效预约，请先结束或取消。";
  const until = state.boot.user.blockedUntil;
  return until && until > serverLocal()
    ? "当前限制新预约至 " + until.replace("T", " ").slice(0, 16) + "。"
    : "";
}
async function bookPage(seq) {
  const version = ++state.bookVersion;
  const stillCurrent = () => current(seq) && version === state.bookVersion;
  if (isAdmin()) {
    location.hash = "home";
    return;
  }
  await refreshBoot();
  if (!stillCurrent()) return;
  const rooms = state.boot.rooms.filter((r) => r.enabled),
    q = state.boot.rule;
  if (!rooms.length) {
    content.innerHTML =
      header("预约座位", "选择日期与时段，查看可预约座位。") +
      empty("暂无开放阅览室", "请关注图书馆公告。");
    return;
  }
  if (!rooms.some((r) => r.id === state.roomId)) state.roomId = rooms[0].id;
  let date = state.booking?.date;
  if (!date || date < today() || date > dateAdd(today(), q.advanceDays))
    date = today();
  let schedules = await api("/rooms/schedules?date=" + date);
  if (!stillCurrent()) return;
  syncClock(schedules.now);
  if (
    !state.booking &&
    !schedules.rooms.some((r) => r.bookable) &&
    q.advanceDays > 0
  ) {
    date = dateAdd(date, 1);
    schedules = await api("/rooms/schedules?date=" + date);
    if (!stillCurrent()) return;
    syncClock(schedules.now);
  }
  if (!state.booking && schedules.rooms.some((r) => r.bookable))
    state.roomId = schedules.rooms.find((r) => r.bookable).roomId;
  const schedule = schedules.rooms.find((r) => r.roomId === state.roomId),
    room = rooms.find((r) => r.id === state.roomId);
  const last = clockMinutes(schedule.closeTime);
  const starts = schedule.bookable
    ? Array.from(
        {
          length: Math.max(
            0,
            (last - clockMinutes(schedule.earliestStart)) / 30,
          ),
        },
        (_, i) => clockText(clockMinutes(schedule.earliestStart) + i * 30),
      )
    : [];
  let start = starts.includes(state.booking?.start)
    ? state.booking.start
    : starts[0] || "";
  function endsFor(value) {
    return value
      ? Array.from(
          {
            length: Math.max(
              0,
              Math.min(q.maxDurationMinutes, last - clockMinutes(value)) / 30,
            ),
          },
          (_, i) => clockText(clockMinutes(value) + (i + 1) * 30),
        )
      : [];
  }
  let ends = endsFor(start),
    end = ends.includes(state.booking?.end)
      ? state.booking.end
      : ends[Math.min(3, ends.length - 1)] || "";
  state.booking = { date, start, end };
  state.selected = null;
  state.seats = null;
  state.requestKey = null;
  const dates = Array.from({ length: q.advanceDays + 1 }, (_, i) =>
    dateAdd(today(), i),
  );
  const restriction = bookingRestriction();
  content.innerHTML =
    header("预约座位", "选择时段、挑选座位，确认后完成预约。") +
    (restriction
      ? `<div class="booking-warning"><span>${esc(restriction)}</span>${state.boot.currentReservation ? `<button class="small" data-detail="${state.boot.currentReservation.id}">查看当前预约</button>` : '<a href="#violations">查看违约记录</a>'}</div>`
      : "") +
    `<section class="panel booking-toolbar"><div class="booking-step"><b>1</b><span>选择日期和时段</span><small>仅显示当前可预约的半小时时间</small></div><div class="toolbar"><label>预约日期<select id="book-date">${dates.map((d, i) => `<option value="${d}" ${d === date ? "selected" : ""}>${d}${i === 0 ? " · 今天" : i === 1 ? " · 明天" : ""}</option>`).join("")}</select></label><label>开始时间<select id="book-start" ${starts.length ? "" : "disabled"}>${starts.length ? starts.map((v) => `<option ${v === start ? "selected" : ""}>${v}</option>`).join("") : '<option value="">无可用时段</option>'}</select></label><label>结束时间<select id="book-end" ${ends.length ? "" : "disabled"}>${ends.map((v) => `<option ${v === end ? "selected" : ""}>${v}</option>`).join("")}</select></label><button class="primary" id="query-seats" ${starts.length ? "" : "disabled"}>刷新座位</button><span class="grow"></span><label class="checkbox"><input type="checkbox" id="socket-filter" ${state.socket ? "checked" : ""}>带插座</label><label class="checkbox"><input type="checkbox" id="window-filter" ${state.window ? "checked" : ""}>靠窗</label></div><div class="booking-shortcuts"><span>常用时长</span>${[
      30, 60, 120, 180, 240,
    ]
      .filter((m) => m <= q.maxDurationMinutes)
      .map(
        (m) =>
          `<button class="small" type="button" data-duration="${m}" ${!start || clockMinutes(start) + m > last ? "disabled" : ""}>${m < 60 ? "30 分钟" : m / 60 + " 小时"}</button>`,
      )
      .join(
        "",
      )}<span class="schedule-text">${schedule.closed ? "当日闭馆" : !schedule.bookable ? "当日已无可预约时段" : `当日开放 ${schedule.openTime.slice(0, 5)}—${schedule.closeTime.slice(0, 5)}`}${schedule.reason ? " · " + esc(schedule.reason) : ""}</span></div></section>` +
    `<div class="room-tabs">${rooms
      .map((r) => {
        const sc = schedules.rooms.find((s) => s.roomId === r.id);
        return `<button class="room-tab ${r.id === state.roomId ? "active" : ""}" data-room="${r.id}" aria-pressed="${r.id === state.roomId}"><img class="room-thumb" src="${roomPhoto(r)}" alt="${esc(r.name)}环境示意图" width="96" height="72"><span class="room-tab-copy"><strong>${esc(r.name)}</strong><small>${esc(r.floor)} · ${r.seatCount} 个座位</small><small>${sc.closed ? "当日闭馆" : sc.openTime.slice(0, 5) + "—" + sc.closeTime.slice(0, 5)} · 示意图</small></span></button>`;
      })
      .join("")}</div>` +
    `<div class="seat-layout"><section class="panel seat-map-panel" id="seat-map"></section><aside class="panel selection-panel" id="selection"></aside></div>`;
  let queryVersion = 0;
  function storeTime() {
    state.booking = {
      date: $("#book-date").value,
      start: $("#book-start").value,
      end: $("#book-end").value,
    };
    state.selected = null;
    state.requestKey = null;
  }
  $("#book-date").onchange = () => {
    storeTime();
    bookPage(seq).catch((e) => notify(e.message, true));
  };
  $("#book-start").onchange = async () => {
    start = $("#book-start").value;
    ends = endsFor(start);
    const previous = state.booking.end;
    $("#book-end").innerHTML = ends
      .map((v) => `<option ${v === previous ? "selected" : ""}>${v}</option>`)
      .join("");
    if (!ends.includes(previous))
      $("#book-end").value = ends[Math.min(3, ends.length - 1)] || "";
    storeTime();
    updateDurations();
    await querySeats();
  };
  $("#book-end").onchange = async () => {
    storeTime();
    updateDurations();
    await querySeats();
  };
  $("#query-seats").onclick = async () => {
    storeTime();
    await querySeats();
  };
  $$("[data-duration]").forEach(
    (b) =>
      (b.onclick = async () => {
        $("#book-end").value = clockText(
          clockMinutes($("#book-start").value) + Number(b.dataset.duration),
        );
        storeTime();
        updateDurations();
        await querySeats();
      }),
  );
  $$("[data-room]").forEach(
    (btn) =>
      (btn.onclick = () => {
        storeTime();
        state.roomId = Number(btn.dataset.room);
        bookPage(seq).catch((e) => notify(e.message, true));
      }),
  );
  $("#socket-filter").onchange = () => {
    state.socket = $("#socket-filter").checked;
    filterSeats();
  };
  $("#window-filter").onchange = () => {
    state.window = $("#window-filter").checked;
    filterSeats();
  };
  bindDetails();
  updateDurations();
  renderSelection();
  if (start && end) await querySeats();
  else
    $("#seat-map").innerHTML = empty(
      schedule.closed ? "当日闭馆" : "没有可预约时段",
      schedule.reason || "请选择其他日期或阅览室。",
    );
  function updateDurations() {
    const b = state.booking;
    $$("[data-duration]").forEach((btn) => {
      const m = Number(btn.dataset.duration);
      btn.disabled = !b.start || clockMinutes(b.start) + m > last;
      btn.classList.toggle(
        "chosen-duration",
        b.start && clockMinutes(b.end) - clockMinutes(b.start) === m,
      );
    });
  }
  async function querySeats() {
    const query = ++queryVersion,
      b = { ...state.booking },
      roomId = state.roomId;
    state.selected = null;
    state.seats = null;
    renderSelection();
    $("#query-seats").disabled = true;
    $("#seat-map").innerHTML = '<p class="loading">正在获取最新座位…</p>';
    try {
      const data = await api(
        `/rooms/${roomId}/seats?start=${b.date}T${b.start}:00&end=${b.date}T${b.end}:00`,
      );
      if (
        !stillCurrent() ||
        query !== queryVersion ||
        state.roomId !== roomId ||
        JSON.stringify(state.booking) !== JSON.stringify(b)
      )
        return;
      state.seats = data.seats;
      const cols = Math.max(8, ...data.seats.map((s) => s.colNo)),
        rows = Math.max(1, ...data.seats.map((s) => s.rowNo));
      const available = data.seats.filter((s) => s.available).length;
      $("#seat-map").innerHTML =
        `<div class="panel-title"><h2><span class="step-number">2</span>${esc(data.room.name)}</h2><span class="muted small">可预约 ${available} / ${data.seats.length}</span></div><div class="seat-tools"><p class="muted small">${esc(data.room.description)}</p><div class="seat-search-tools"><input id="seat-search" aria-label="搜索座位编号" placeholder="搜索座位号，如 A-08" maxlength="20" value="${esc(state.seatSearch)}"><button class="small" id="pick-seat">帮我选一个</button></div></div>${!data.open ? '<div class="alert info">开放安排已变化，该时段不可预约，请刷新页面重新选择。</div>' : ""}<div id="match-count" class="match-count" role="status"></div><div class="room-annotation"><span>窗侧</span><span>阅览区域</span><span>窗侧</span></div><div class="seat-map-scroll"><div class="seat-grid" style="grid-template-columns:repeat(${cols},minmax(42px,1fr));grid-template-rows:repeat(${rows},54px)">${data.seats.map((s) => `<button class="seat ${s.available ? "" : "unavailable"}" data-seat="${s.id}" style="grid-row:${s.rowNo};grid-column:${s.colNo}" title="${esc(s.seatNo + " · " + (s.available ? "可预约" : s.enabled ? "该时段不可用" : "停用"))}" aria-label="${esc(s.seatNo + " " + (s.available ? "可预约" : s.enabled ? "该时段不可用" : "停用") + (s.hasSocket ? " 带插座" : "") + (s.nearWindow ? " 靠窗" : ""))}" aria-pressed="false" aria-disabled="${!s.available}"><b>${esc(s.seatNo)}</b><span>${s.available ? (s.hasSocket ? "插座" : "") + (s.nearWindow ? " 靠窗" : "") : s.enabled ? "不可用" : "停用"}</span></button>`).join("")}</div></div><div class="seat-map-bottom"><span>入口 / 出口</span><div class="legend"><span><i></i>可预约</span><span><i class="taken"></i>不可用</span><span><i class="chosen"></i>已选择</span></div></div>`;
      function selectSeat(s) {
        state.selected = s;
        state.requestKey = crypto.randomUUID();
        $$("[data-seat]").forEach((btn) => {
          const selected = Number(btn.dataset.seat) === s.id;
          btn.classList.toggle("selected", selected);
          btn.setAttribute("aria-pressed", selected);
        });
        renderSelection();
      }
      $$("[data-seat]").forEach(
        (btn) =>
          (btn.onclick = () => {
            const s = state.seats.find(
              (s) => s.id === Number(btn.dataset.seat),
            );
            if (s.available) selectSeat(s);
          }),
      );
      $("#pick-seat").onclick = () => {
        const s = state.seats.find(matches);
        if (s) {
          selectSeat(s);
          $(`[data-seat="${s.id}"]`).focus();
        }
      };
      $("#seat-search").oninput = (e) => {
        state.seatSearch = e.target.value.trim();
        filterSeats();
      };
      filterSeats();
    } catch (error) {
      if (stillCurrent() && query === queryVersion) {
        $("#seat-map").innerHTML = empty("无法查询", error.message);
        renderSelection();
      }
    } finally {
      if (stillCurrent() && query === queryVersion && $("#query-seats"))
        $("#query-seats").disabled = false;
    }
  }
  function matches(s) {
    return (
      s.available &&
      s.seatNo.toLowerCase().includes(state.seatSearch.toLowerCase()) &&
      (!state.socket || s.hasSocket) &&
      (!state.window || s.nearWindow)
    );
  }
  function filterSeats() {
    $$("[data-seat]").forEach((btn) => {
      const s = state.seats?.find((s) => s.id === Number(btn.dataset.seat));
      const hidden =
        !s?.seatNo.toLowerCase().includes(state.seatSearch.toLowerCase()) ||
        (state.socket && !s?.hasSocket) ||
        (state.window && !s?.nearWindow);
      btn.classList.toggle("filtered", hidden);
      if (hidden && s?.id === state.selected?.id) {
        state.selected = null;
        btn.classList.remove("selected");
        btn.setAttribute("aria-pressed", "false");
      }
    });
    const count = state.seats?.filter(matches).length || 0;
    if ($("#match-count"))
      $("#match-count").textContent = count
        ? "符合筛选条件的可预约座位：" + count + " 个"
        : "暂无符合条件的可预约座位，请调整筛选或时段。";
    if ($("#pick-seat")) $("#pick-seat").disabled = count === 0;
    renderSelection();
  }
  function renderSelection() {
    const s = state.selected,
      b = state.booking,
      minutes =
        b.start && b.end ? clockMinutes(b.end) - clockMinutes(b.start) : 0;
    const restriction = bookingRestriction();
    $("#selection").innerHTML =
      `<h3><span class="step-number">3</span>确认预约</h3><div class="selection-number">${s ? esc(s.seatNo) : "请选择座位"}</div><div class="detail-row"><span>阅览室</span><span>${esc(room.name)}</span></div><div class="detail-row"><span>日期</span><span>${esc(b.date)}</span></div><div class="detail-row"><span>时段</span><span>${b.start ? esc(b.start) + "—" + esc(b.end) : "无可用时段"}</span></div><div class="detail-row"><span>预约时长</span><span>${minutes ? minutes + " 分钟" : "—"}</span></div><div class="detail-row"><span>座位属性</span><span>${s ? esc([s.hasSocket ? "插座" : "", s.nearWindow ? "靠窗" : ""].filter(Boolean).join(" / ") || "普通座位") : "—"}</span></div><button class="primary" id="confirm-book" ${s && !restriction ? "" : "disabled"}>确认预约</button>${restriction ? `<p class="selection-hint">${esc(restriction)}</p>` : '<p class="selection-hint">提交成功后才会为您保留座位。</p>'}<div class="rule-note"><strong>预约须知 · 演示规则</strong>开始前 ${q.checkinEarlyMinutes} 分钟可签到，开始后 ${q.checkinGraceMinutes} 分钟内须完成签到。<br>暂离最多 ${q.leaveMinutes} 分钟。<br>请本人到馆后操作签到。</div>`;
    if (s && !restriction)
      $("#confirm-book").onclick = () => confirmBooking(s, b, room);
  }
}
function confirmBooking(s, b, room) {
  const key = state.requestKey || crypto.randomUUID();
  state.requestKey = key;
  const input = {
    seatId: s.id,
    startTime: `${b.date}T${b.start}:00`,
    endTime: `${b.date}T${b.end}:00`,
    requestKey: key,
  };
  modal(
    "确认预约",
    `<div class="selection-number">${esc(s.seatNo)}</div><p>${esc(room.name)} · ${esc(b.date)}</p><p>${esc(b.start)}—${esc(b.end)}</p><p class="muted small">提交成功后，请在预约详情查看签到截止。查询到可用不代表已锁定座位。</p><p class="inline-error" id="booking-error"></p><div class="form-actions"><button id="booking-cancel">取消</button><button id="booking-submit" class="primary">提交预约</button></div>`,
  );
  $("#booking-cancel").onclick = () => $("#modal").close();
  $("#booking-submit").onclick = async () => {
    const btn = $("#booking-submit");
    btn.disabled = true;
    try {
      sessionStorage.setItem(
        "pending-" + state.boot.user.id,
        JSON.stringify(input),
      );
      const r = await api("/reservations", { method: "POST", body: input });
      sessionStorage.removeItem("pending-" + state.boot.user.id);
      $("#modal").close();
      notify("预约成功");
      location.hash = "reservations";
      await showDetail(r.id);
    } catch (error) {
      $("#booking-error").textContent = error.message;
      if (error.status)
        sessionStorage.removeItem("pending-" + state.boot.user.id);
    } finally {
      btn.disabled = false;
    }
  };
}
function roomPhoto(room) {
  const images = {
    R1: "library-study",
    R2: "library-books",
    R3: "library-reading",
  };
  return "/images/" + (images[room.code] || "library-study") + ".webp";
}
function statusOptions(selected) {
  return (
    '<option value="">全部状态</option><option value="ACTIVE" ' +
    (selected === "ACTIVE" ? "selected" : "") +
    ">进行中的预约</option>" +
    Object.entries(labels)
      .map(
        ([s, n]) =>
          `<option value="${s}" ${s === selected ? "selected" : ""}>${n}</option>`,
      )
      .join("")
  );
}
async function reservationPage(seq) {
  const admin = isAdmin(),
    params = new URLSearchParams({ page: state.page, status: state.status });
  if (admin) params.set("search", state.search);
  if (state.date) params.set("date", state.date);
  const data = await api(
    (admin ? "/admin/reservations" : "/me/reservations") + "?" + params,
  );
  if (!current(seq)) return;
  content.innerHTML =
    header(
      admin ? "预约管理" : "我的预约",
      admin
        ? "查看预约状态，处理异常使用情况。"
        : "查看预约记录，及时签到与签退。",
    ) +
    `<section class="panel"><form class="toolbar" id="reservation-filter"><label>预约状态<select name="status">${statusOptions(state.status)}</select></label>${admin ? `<label>学号 / 姓名 / 编号<input name="search" placeholder="输入关键词" value="${esc(state.search)}" maxlength="100"></label>` : ""}<label>预约日期<input type="date" name="date" value="${esc(state.date)}"></label><button class="primary">查询</button><button type="button" id="reset-filter">重置</button></form>` +
    (data.items.length
      ? `<table><thead><tr><th>预约编号</th>${admin ? "<th>学生</th>" : ""}<th>阅览室 / 座位</th><th>预约时段</th><th>状态</th><th>操作</th></tr></thead><tbody>${data.items.map((r) => `<tr><td>${esc(r.reservationNo)}<div class="muted">创建于 ${stamp(r.createdAt)}</div></td>${admin ? `<td>${esc(r.userName)}<div class="muted">${esc(r.studentNo)}</div></td>` : ""}<td>${esc(r.roomName)}<div class="muted">${esc(r.seatNo)}</div></td><td>${esc(r.startTime.slice(0, 10))}<br>${time(r.startTime)}—${time(r.endTime)}</td><td>${badge(r.status)}</td><td><div class="actions"><button class="small" data-detail="${r.id}">详情</button>${admin && active.includes(r.status) ? `<button class="small danger" data-close="${r.id}">关闭预约</button>` : ""}</div></td></tr>`).join("")}</tbody></table>`
      : empty(
          "暂无符合条件的预约",
          admin ? "调整筛选条件后重试。" : "先去预约一个座位吧。",
        )) +
    pager(data) +
    "</section>";
  $("#reservation-filter").onsubmit = (e) => {
    e.preventDefault();
    const v = formValues(e.target);
    state.status = v.status;
    state.search = v.search || "";
    state.date = v.date || "";
    state.page = 1;
    route();
  };
  $("#reset-filter").onclick = () => {
    state.status = "";
    state.search = "";
    state.date = "";
    state.page = 1;
    route();
  };
  bindPager();
  bindDetails();
  $$("[data-close]").forEach(
    (b) =>
      (b.onclick = () =>
        reasonModal(
          "关闭预约",
          (v) =>
            api(`/admin/reservations/${b.dataset.close}/close`, {
              method: "POST",
              body: v,
            }),
          "将立即释放余下时段，并告知学生关闭原因；不会计为学生违约。",
        )),
  );
}
function bindDetails() {
  $$("[data-detail]").forEach(
    (b) =>
      (b.onclick = () =>
        showDetail(Number(b.dataset.detail)).catch((e) =>
          notify(e.message, true),
        )),
  );
}
async function showDetail(id) {
  const version = ++detailVersion;
  const data = await api("/reservations/" + id);
  if (version !== detailVersion) return;
  const r = data.reservation,
    q = data.rule,
    now = data.now;
  syncClock(now);
  let actions = "";
  if (!isAdmin() && active.includes(r.status)) {
    if (r.status === "RESERVED") {
      if (now < r.startTime)
        actions += `<button data-action="cancel">取消预约</button>`;
      const early = new Date(
        Date.parse(r.startTime + "+08:00") -
          q.checkinEarlyMinutes * 60000 +
          8 * 3600000,
      )
        .toISOString()
        .slice(0, 19);
      actions += `<button class="primary" data-action="check-in" ${now < early ? "disabled" : ""}>${now < early ? "尚未到签到时间" : "到场签到"}</button>`;
    }
    if (r.status === "CHECKED_IN") {
      if (now >= r.startTime && r.leaveCount < q.leaveLimit)
        actions += `<button data-action="leave">暂离 ${q.leaveMinutes} 分钟</button>`;
      actions +=
        '<button class="primary" data-action="check-out">结束使用 / 签退</button>';
    }
    if (r.status === "TEMP_LEFT")
      actions +=
        '<button data-action="check-out">结束使用 / 签退</button><button class="primary" data-action="return">已返回座位</button>';
  }
  const deadline = new Date(
    Date.parse(r.startTime + "+08:00") +
      q.checkinGraceMinutes * 60000 +
      8 * 3600000,
  )
    .toISOString()
    .slice(0, 19);
  modal(
    "预约详情",
    `<div class="record-top" style="display:flex;justify-content:space-between"><span class="selection-number" style="margin-top:0">${esc(r.seatNo)}</span>${badge(r.status)}</div><div class="detail-row"><span>预约编号</span><span>${esc(r.reservationNo)}</span></div><div class="detail-row"><span>阅览室</span><span>${esc(r.roomName)}</span></div>${isAdmin() ? `<div class="detail-row"><span>学生</span><span>${esc(r.userName)} · ${esc(r.studentNo)}</span></div>` : ""}<div class="detail-row"><span>预约时段</span><span>${stamp(r.startTime)}—${time(r.endTime)}</span></div><div class="detail-row"><span>签到截止</span><span>${stamp(deadline)}</span></div><div class="detail-row"><span>已用暂离次数</span><span>${r.leaveCount} / ${q.leaveLimit}</span></div>${r.status === "TEMP_LEFT" ? `<div class="alert info">请在 ${stamp(r.leaveDeadline)} 前返回，超时后座位将被释放。</div>` : ""}${r.status === "CHECKED_IN" && now < r.startTime ? '<div class="alert info">已登记签到，请等待预约开始后使用座位。</div>' : ""}${r.finishReason ? `<div class="alert info">${esc(r.finishReason)}</div>` : ""}${active.includes(r.status) ? '<div class="deadline-panel" id="deadline-panel" role="status"></div>' : ""}<h3 style="margin-top:25px">操作记录</h3><div class="timeline">${data.events.map((e) => `<div class="timeline-item"><b>${esc(e.reason)}</b><time>${stamp(e.occurredAt)} · ${e.actorId ? "用户操作" : "系统处理"}</time></div>`).join("")}</div><p id="detail-error" class="inline-error"></p><div class="form-actions">${actions}</div>`,
    { wide: true },
  );
  if (active.includes(r.status)) {
    updateDetailDeadline(r, q);
    detailTimer = setInterval(() => updateDetailDeadline(r, q), 1000);
  }
  $$("[data-action]").forEach(
    (b) =>
      (b.onclick = async () => {
        if (
          ["cancel", "check-out"].includes(b.dataset.action) &&
          !window.confirm(
            b.dataset.action === "cancel"
              ? "确定取消这条预约吗？"
              : "确定结束使用并释放座位吗？",
          )
        )
          return;
        $("#modal").dataset.busy = "true";
        $$("[data-action]").forEach((button) => (button.disabled = true));
        try {
          const updated = await api(`/reservations/${id}/${b.dataset.action}`, {
            method: "POST",
          });
          notify(
            updated.status === r.status
              ? "预约状态未变化，请查看最新详情"
              : "操作成功",
          );
          await refreshBoot();
          await route();
          await showDetail(id);
        } catch (error) {
          $("#modal").dataset.busy = "false";
          if ($("#detail-error"))
            $("#detail-error").textContent = error.message;
          $$("[data-action]").forEach((button) => (button.disabled = false));
          updateDetailDeadline(r, q);
        }
      }),
  );
}
function updateDetailDeadline(r, q) {
  if (!$("#modal").open || !$("#deadline-panel")) {
    clearInterval(detailTimer);
    return;
  }
  if ($("#modal").dataset.busy === "true") return;
  const now = serverLocal(),
    early = plusMinutes(r.startTime, -q.checkinEarlyMinutes),
    deadline = plusMinutes(r.startTime, q.checkinGraceMinutes);
  let target, label;
  if (r.status === "RESERVED") {
    target = now < early ? early : deadline;
    label = now < early ? "距离可签到时间" : "距离签到截止";
  } else if (r.status === "TEMP_LEFT") {
    target = r.leaveDeadline;
    label = "剩余返回时间";
  } else {
    target = now < r.startTime ? r.startTime : r.endTime;
    label = now < r.startTime ? "距离预约开始" : "剩余使用时间";
  }
  const seconds = Math.max(
    0,
    Math.floor(
      (Date.parse(target + "+08:00") - Date.parse(now + "+08:00")) / 1000,
    ),
  );
  const h = Math.floor(seconds / 3600),
    m = Math.floor((seconds % 3600) / 60),
    s = seconds % 60;
  $("#deadline-panel").innerHTML =
    `<span>${label}</span><strong>${h ? String(h).padStart(2, "0") + ":" : ""}${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}</strong><button class="small" id="refresh-detail">刷新状态</button>`;
  $("#refresh-detail").onclick = () =>
    showDetail(r.id).catch((e) => notify(e.message, true));
  const checkin = $('[data-action="check-in"]');
  if (checkin) {
    checkin.disabled = now < early || now > deadline;
    checkin.textContent =
      now < early
        ? "尚未到签到时间"
        : now > deadline
          ? "签到时间已过"
          : "到场签到";
  }
  if (
    now >= r.endTime ||
    (r.status === "RESERVED" && now > deadline) ||
    (r.status === "TEMP_LEFT" && now > r.leaveDeadline)
  ) {
    $$("[data-action]").forEach((b) => (b.disabled = true));
    $("#deadline-panel").classList.add("expired");
    $("#deadline-panel span").textContent = "时段已到期，正在更新状态";
    clearInterval(detailTimer);
    showDetail(r.id).catch((e) => {
      if ($("#deadline-panel span"))
        $("#deadline-panel span").textContent = e.message;
    });
  }
  const cancel = $('[data-action="cancel"]');
  if (cancel && now >= r.startTime) cancel.disabled = true;
}
async function violationPage(seq) {
  await refreshBoot();
  if (!current(seq)) return;
  const data = await api(
    (isAdmin() ? "/admin" : "/me") + "/violations?page=" + state.page,
  );
  if (!current(seq)) return;
  content.innerHTML =
    header(
      isAdmin() ? "违约管理" : "违约记录",
      isAdmin()
        ? "核验违约原因，保留撤销处理记录。"
        : "按时签到和返回，让座位资源有序共享。",
    ) +
    (!isAdmin() &&
    state.boot.user.blockedUntil &&
    state.boot.user.blockedUntil > serverLocal()
      ? `<div class="alert error">当前限制新预约至 ${stamp(state.boot.user.blockedUntil)}。</div>`
      : "") +
    `<section class="panel">${data.items.length ? `<table><thead><tr>${isAdmin() ? "<th>学生</th>" : ""}<th>违约类型</th><th>原因</th><th>发生时间</th><th>记录状态</th><th>操作</th></tr></thead><tbody>${data.items.map((v) => `<tr>${isAdmin() ? `<td>${esc(v.userName)}<div class="muted">${esc(v.studentNo)}</div></td>` : ""}<td>${badge(v.type)}</td><td>${esc(v.reason)}${v.isRevoked ? `<div class="muted">撤销原因：${esc(v.revokeReason)}</div>` : ""}</td><td>${stamp(v.occurredAt)}</td><td>${v.isRevoked ? "已撤销" : "有效"}</td><td><div class="actions"><button class="small" data-detail="${v.reservationId}">关联预约</button>${isAdmin() && !v.isRevoked ? `<button class="small" data-revoke="${v.id}">撤销</button>` : ""}</div></td></tr>`).join("")}</tbody></table>` : empty("暂无违约记录", "按时使用座位，保持良好的预约记录。")}${pager(data)}</section>`;
  bindPager();
  bindDetails();
  $$("[data-revoke]").forEach(
    (b) =>
      (b.onclick = () =>
        reasonModal(
          "撤销违约",
          (v) =>
            api("/admin/violations/" + b.dataset.revoke + "/revoke", {
              method: "POST",
              body: v,
            }),
          "撤销后将重新计算该学生的预约限制，原记录会保留。",
        )),
  );
}
async function noticePage(seq) {
  if (!isAdmin()) {
    const q = state.boot.rule;
    content.innerHTML =
      header("公告与预约规则", "了解开放安排与座位使用要求。") +
      `<div class="two-column"><section class="panel"><div class="panel-title"><h2>最新公告</h2></div>${noticeList(state.boot.notices)}</section><section class="panel"><div class="panel-title"><h2>预约规则</h2><span class="muted small">演示规则 · 版本 ${q.id}</span></div><ul class="rule-list"><li>可预约今天至未来 ${q.advanceDays} 天的座位。</li><li>起止时间按半小时选择，单次最多 ${q.maxDurationMinutes / 60} 小时。</li><li>每人同时最多 1 条有效预约。</li><li>开始前 ${q.checkinEarlyMinutes} 分钟起可签到，开始后 ${q.checkinGraceMinutes} 分钟为签到截止。</li><li>每次预约允许暂离 ${q.leaveLimit} 次，每次不超过 ${q.leaveMinutes} 分钟。</li><li>滚动 ${q.violationWindowDays} 天有效违约达到 ${q.violationLimit} 次，将限制新预约 ${q.blockDays} 天。</li><li>仅可在开始前取消未签到的预约。</li></ul></section></div>`;
    return;
  }
  const data = await api("/admin/notices");
  if (!current(seq)) return;
  content.innerHTML =
    header(
      "公告管理",
      "发布开放通知和座位使用提醒。",
      '<button class="primary" id="add-notice">新增公告</button>',
    ) +
    `<section class="panel">${data.length ? `<table><thead><tr><th>标题</th><th>发布时间</th><th>状态</th><th>操作</th></tr></thead><tbody>${data.map((n) => `<tr><td>${esc(n.title)}<div class="muted">${esc(n.content.slice(0, 70))}</div></td><td>${stamp(n.createdAt)}</td><td>${n.published ? "已发布" : "草稿 / 已撤下"}</td><td><button class="small" data-edit="${n.id}">编辑</button></td></tr>`).join("")}</tbody></table>` : empty("暂无公告")}</section>`;
  function edit(n = {}) {
    formModal(
      n.id ? "编辑公告" : "新增公告",
      `<label class="full">标题<input name="title" required maxlength="100" value="${esc(n.title)}"></label><label class="full">正文<textarea name="content" required maxlength="5000">${esc(n.content)}</textarea></label>${checkfield("发布到学生端", "published", n.published ?? true)}`,
      (v) =>
        api("/admin/notices" + (n.id ? "/" + n.id : ""), {
          method: n.id ? "PUT" : "POST",
          body: v,
        }),
      "公告以纯文本展示。",
    );
  }
  $("#add-notice").onclick = () => edit();
  $$("[data-edit]").forEach(
    (b) =>
      (b.onclick = () =>
        edit(data.find((n) => n.id === Number(b.dataset.edit)))),
  );
}
async function profilePage() {
  const u = state.boot.user;
  content.innerHTML =
    header("个人信息", "维护自己的账号与登录密码。") +
    `<section class="panel"><h2>账号信息</h2><div class="profile-list"><div class="detail-row"><span>账号</span><span>${esc(u.studentNo)}</span></div><div class="detail-row"><span>姓名</span><span>${esc(u.name)}</span></div><div class="detail-row"><span>班级 / 部门</span><span>${esc(u.className)}</span></div><div class="detail-row"><span>角色</span><span>${isAdmin() ? "管理员" : "学生"}</span></div></div><button class="primary" id="change-password" style="margin-top:23px">修改密码</button></section>`;
  $("#change-password").onclick = () =>
    formModal(
      "修改密码",
      field(
        "原密码",
        "oldPassword",
        "",
        "password",
        'required autocomplete="current-password"',
      ) +
        field(
          "新密码",
          "newPassword",
          "",
          "password",
          'required minlength="8" maxlength="64" autocomplete="new-password"',
        ),
      async (v) => {
        await api("/me/password", { method: "POST", body: v });
        location.href = "/login?logout";
      },
      "至少 8 位。修改后请使用新密码重新登录。",
    );
}
async function adminHome(seq) {
  const start = state.statsStart || today(),
    end = state.statsEnd || today();
  const d = await api(`/admin/statistics?start=${start}&end=${end}`);
  if (!current(seq)) return;
  const rate = d.due
    ? Math.round((Number(d.arrived) / Number(d.due)) * 100) + "%"
    : "—";
  content.innerHTML =
    header("管理概览", "了解预约情况与当前座位使用状态。") +
    `<div class="metrics"><div class="metric"><p>当前使用中</p><strong>${d.inUse}</strong><small>已签到且已到预约开始时间</small></div><div class="metric"><p>当前暂离</p><strong>${d.away}</strong><small>暂离期间继续保留座位</small></div><div class="metric"><p>待签到预约</p><strong>${d.waiting}</strong><small>包含未来的有效预约</small></div><div class="metric"><p>启用座位</p><strong>${d.seats}</strong><small>${d.rooms} 间开放阅览室</small></div></div>` +
    `<section class="panel"><div class="panel-title"><h2>预约数据</h2><span class="muted small">按预约开始日期统计</span></div><form class="toolbar" id="stats-filter"><label>开始日期<input type="date" name="start" value="${start}" required></label><label>结束日期<input type="date" name="end" value="${end}" required></label><button class="primary">查询</button></form><div class="metrics" style="margin:0"><div class="metric"><p>预约总数</p><strong>${d.reservations}</strong></div><div class="metric"><p>签到率</p><strong>${rate}</strong><small>${d.arrived} / ${d.due} 条已过签到截止的应到预约</small></div><div class="metric"><p>未签到超时</p><strong>${d.noShow}</strong></div><div class="metric"><p>暂离超时</p><strong>${d.leaveTimeout}</strong></div></div><p class="stat-note">排除学生开始前取消和管理员关闭后计算签到率；${d.cancelled} 条学生取消，${d.adminClosed} 条管理员关闭。不将按钮签到数据视为实际到馆人数。</p></section>` +
    `<section class="panel"><div class="panel-title"><h2>常用管理</h2></div><div class="actions"><a href="#reservations"><button>查看预约</button></a><a href="#seats"><button>维护座位</button></a><a href="#notices"><button>发布公告</button></a><a href="#audits"><button>操作日志</button></a></div></section>`;
  $("#stats-filter").onsubmit = (e) => {
    e.preventDefault();
    const v = formValues(e.target);
    state.statsStart = v.start;
    state.statsEnd = v.end;
    route();
  };
}
async function roomPage(seq) {
  const [rooms, openings] = await Promise.all([
    api("/admin/rooms"),
    api("/admin/openings"),
  ]);
  if (!current(seq)) return;
  content.innerHTML =
    header(
      "阅览室管理",
      "维护阅览室和开放安排。",
      '<button class="primary" id="add-room">新增阅览室</button>',
    ) +
    `<section class="panel"><table><thead><tr><th>阅览室</th><th>楼层</th><th>启用座位</th><th>日常开放</th><th>状态</th><th>操作</th></tr></thead><tbody>${rooms.map((r) => `<tr><td>${esc(r.name)}<div class="muted">${esc(r.code)} · ${esc(r.description)}</div></td><td>${esc(r.floor)}</td><td>${r.seatCount}</td><td>${esc(r.openTime.slice(0, 5))}—${esc(r.closeTime.slice(0, 5))}</td><td>${r.enabled ? "启用" : "停用"}</td><td><button class="small" data-edit-room="${r.id}">编辑</button></td></tr>`).join("")}</tbody></table></section><section class="panel"><div class="panel-title"><h2>特殊日期开放安排</h2><button class="small" id="add-opening">设置日期安排</button></div>${openings.length ? `<table><thead><tr><th>阅览室</th><th>日期</th><th>开放情况</th><th>说明</th><th>操作</th></tr></thead><tbody>${openings.map((o) => `<tr><td>${esc(rooms.find((r) => r.id === o.roomId)?.name)}</td><td>${esc(o.specialDate)}</td><td>${o.isClosed ? "全天闭馆" : esc(o.openTime.slice(0, 5) + "—" + o.closeTime.slice(0, 5))}</td><td>${esc(o.reason)}</td><td>${o.specialDate >= today() ? `<button class="small" data-edit-opening="${o.id}">编辑</button>` : "历史安排"}</td></tr>`).join("")}</tbody></table>` : empty("暂无特殊日期安排", "默认每日按照日常开放时间开放。")}</section>`;
  function edit(r = {}) {
    formModal(
      r.id ? "编辑阅览室" : "新增阅览室",
      field(
        "编号",
        "code",
        r.code || "",
        "text",
        'required pattern="[A-Za-z0-9_-]{1,32}" maxlength="32"',
      ) +
        field("名称", "name", r.name || "", "text", 'required maxlength="60"') +
        field(
          "楼层",
          "floor",
          r.floor || "",
          "text",
          'required maxlength="40"',
        ) +
        `<label>说明<input name="description" value="${esc(r.description)}" maxlength="500"></label>` +
        field(
          "开放时间",
          "openTime",
          r.openTime?.slice(0, 5) || "08:00",
          "time",
          'required step="1800"',
        ) +
        field(
          "关闭时间",
          "closeTime",
          r.closeTime?.slice(0, 5) || "22:00",
          "time",
          'required step="1800"',
        ) +
        checkfield("启用阅览室", "enabled", r.enabled ?? true) +
        `<label class="full">变更原因<input name="reason" required maxlength="200"></label>`,
      (v) =>
        api("/admin/rooms" + (r.id ? "/" + r.id : ""), {
          method: r.id ? "PUT" : "POST",
          body: v,
        }),
      "停用或缩短开放时间将自动关闭不再符合条件的有效预约，并保留操作记录。",
    );
  }
  $("#add-room").onclick = () => edit();
  $$("[data-edit-room]").forEach(
    (b) =>
      (b.onclick = () =>
        edit(rooms.find((r) => r.id === Number(b.dataset.editRoom)))),
  );
  function editOpening(o = {}) {
    formModal(
      o.id ? "编辑日期安排" : "设置日期开放安排",
      `<label>阅览室<select name="roomId" ${o.id ? "disabled" : ""}>${rooms.map((r) => `<option value="${r.id}" ${r.id === o.roomId ? "selected" : ""}>${esc(r.name)}</option>`).join("")}</select></label>` +
        field(
          "特殊日期",
          "specialDate",
          o.specialDate || today(),
          "date",
          `required min="${today()}" ${o.id ? "readonly" : ""}`,
        ) +
        field(
          "开放时间",
          "openTime",
          o.openTime?.slice(0, 5) || "08:00",
          "time",
          'required step="1800"',
        ) +
        field(
          "关闭时间",
          "closeTime",
          o.closeTime?.slice(0, 5) || "22:00",
          "time",
          'required step="1800"',
        ) +
        checkfield("全天闭馆", "isClosed", o.isClosed ?? false) +
        `<label class="full">说明与原因<input name="reason" required maxlength="200" value="${esc(o.reason)}"></label>`,
      (v) =>
        api("/admin/openings", {
          method: "POST",
          body: {
            ...v,
            openTime: $("[name=openTime]").value,
            closeTime: $("[name=closeTime]").value,
            roomId: o.roomId || Number(v.roomId),
          },
        }),
      "同一日期的新安排会覆盖旧安排。缩短开放时间或闭馆会关闭受影响的有效预约；恢复开放不会恢复已关闭预约。",
    );
    const toggle = $("[name=isClosed]"),
      times = $$("[name=openTime],[name=closeTime]");
    const update = () =>
      times.forEach((input) => {
        input.disabled = toggle.checked;
        input.required = !toggle.checked;
      });
    toggle.onchange = update;
    update();
  }
  $("#add-opening").onclick = () => editOpening();
  $$("[data-edit-opening]").forEach(
    (button) =>
      (button.onclick = () =>
        editOpening(
          openings.find((o) => o.id === Number(button.dataset.editOpening)),
        )),
  );
}
async function seatAdminPage(seq) {
  const rooms = state.boot.rooms;
  if (!rooms.length) {
    content.innerHTML =
      header("座位管理", "按阅览室维护座位。") + empty("请先新增阅览室");
    return;
  }
  if (!rooms.some((r) => r.id === state.adminRoom))
    state.adminRoom = rooms[0].id;
  const seats = await api(`/admin/rooms/${state.adminRoom}/seats`);
  if (!current(seq)) return;
  content.innerHTML =
    header(
      "座位管理",
      "维护座位编号、位置和使用属性。",
      '<button class="primary" id="add-seat">新增座位</button>',
    ) +
    `<section class="panel"><div class="admin-room-filter"><label>阅览室</label><select id="admin-room">${rooms.map((r) => `<option value="${r.id}" ${state.adminRoom === r.id ? "selected" : ""}>${esc(r.name)}</option>`).join("")}</select><span class="muted small">共 ${seats.length} 个座位</span></div>${seats.length ? `<table><thead><tr><th>座位编号</th><th>网格位置</th><th>属性</th><th>状态</th><th>操作</th></tr></thead><tbody>${seats.map((s) => `<tr><td>${esc(s.seatNo)}</td><td>第 ${s.rowNo} 行 · 第 ${s.colNo} 列</td><td>${esc([s.hasSocket ? "带插座" : "", s.nearWindow ? "靠窗" : ""].filter(Boolean).join(" / ") || "普通座位")}</td><td>${s.enabled ? "启用" : "停用"}</td><td><button class="small" data-edit-seat="${s.id}">编辑</button></td></tr>`).join("")}</tbody></table>` : empty("暂无座位", "点击右上角新增座位。")}</section>`;
  $("#admin-room").onchange = (e) => {
    state.adminRoom = Number(e.target.value);
    route();
  };
  function edit(s = {}) {
    formModal(
      s.id ? "编辑座位" : "新增座位",
      field(
        "座位编号",
        "seatNo",
        s.seatNo || "",
        "text",
        'required maxlength="20"',
      ) +
        field(
          "行号",
          "rowNo",
          s.rowNo || 1,
          "number",
          'required min="1" max="30"',
        ) +
        field(
          "列号",
          "colNo",
          s.colNo || 1,
          "number",
          'required min="1" max="20"',
        ) +
        checkfield("带插座", "hasSocket", s.hasSocket) +
        checkfield("靠窗", "nearWindow", s.nearWindow) +
        checkfield("启用座位", "enabled", s.enabled ?? true) +
        `<label class="full">变更原因<input name="reason" required maxlength="200"></label>`,
      (v) =>
        api("/admin/seats" + (s.id ? "/" + s.id : ""), {
          method: s.id ? "PUT" : "POST",
          body: { ...v, roomId: state.adminRoom },
        }),
      "同一房间内编号和网格位置不能重复。停用会自动关闭该座位上的有效预约。",
    );
  }
  $("#add-seat").onclick = () => edit();
  $$("[data-edit-seat]").forEach(
    (b) =>
      (b.onclick = () =>
        edit(seats.find((s) => s.id === Number(b.dataset.editSeat)))),
  );
}
async function userPage(seq) {
  const users = await api(
    "/admin/users?search=" + encodeURIComponent(state.userSearch || ""),
  );
  if (!current(seq)) return;
  content.innerHTML =
    header(
      "学生管理",
      "创建和维护学生账号，管理员账号不在此处修改。",
      '<button class="primary" id="add-user">新增学生</button>',
    ) +
    `<section class="panel"><form class="toolbar" id="user-filter"><label>学号 / 姓名<input name="search" maxlength="100" value="${esc(state.userSearch)}" placeholder="搜索学生"></label><button class="primary">查询</button><span class="muted small">最多显示 200 条，请使用搜索定位</span></form>${users.length ? `<table><thead><tr><th>账号</th><th>姓名</th><th>班级</th><th>状态</th><th>限制预约至</th><th>操作</th></tr></thead><tbody>${users.map((u) => `<tr><td>${esc(u.studentNo)}</td><td>${esc(u.name)} ${u.role === "ADMIN" ? '<span class="role">管理员</span>' : ""}</td><td>${esc(u.className)}</td><td>${u.enabled ? "启用" : "停用"}</td><td>${u.blockedUntil && u.blockedUntil > serverLocal() ? stamp(u.blockedUntil) : "—"}</td><td>${u.role === "STUDENT" ? `<div class="actions"><button class="small" data-reset-password="${u.id}">重置密码</button><button class="small ${u.enabled ? "danger" : ""}" data-toggle-user="${u.id}">${u.enabled ? "停用" : "启用"}</button></div>` : "—"}</td></tr>`).join("")}</tbody></table>` : empty("未找到学生")}</section>`;
  $("#user-filter").onsubmit = (e) => {
    e.preventDefault();
    state.userSearch = formValues(e.target).search;
    route();
  };
  $("#add-user").onclick = () =>
    formModal(
      "新增学生",
      field(
        "学号",
        "studentNo",
        "",
        "text",
        'required pattern="[A-Za-z0-9_-]{3,32}" maxlength="32"',
      ) +
        field("姓名", "name", "", "text", 'required maxlength="50"') +
        field("班级", "className", "", "text", 'maxlength="100"') +
        field(
          "初始密码",
          "password",
          "",
          "password",
          'required minlength="8" maxlength="64" autocomplete="new-password"',
        ),
      (v) => api("/admin/users", { method: "POST", body: v }),
      "密码至少 8 位，请提醒学生首次登录后修改。",
    );
  $$("[data-reset-password]").forEach(
    (button) =>
      (button.onclick = () =>
        formModal(
          "重置学生密码",
          field(
            "新密码",
            "newPassword",
            "",
            "password",
            'required minlength="8" maxlength="64" autocomplete="new-password"',
          ) +
            field("操作原因", "reason", "", "text", 'required maxlength="200"'),
          (v) =>
            api("/admin/users/" + button.dataset.resetPassword + "/password", {
              method: "POST",
              body: v,
            }),
          "只更新密码并退出该学生的旧登录，保留原有预约和违约记录。请通过本人确认的方式告知新密码。",
        )),
  );
  $$("[data-toggle-user]").forEach(
    (b) =>
      (b.onclick = () => {
        const u = users.find((u) => u.id === Number(b.dataset.toggleUser));
        reasonModal(
          u.enabled ? "停用学生" : "启用学生",
          (v) =>
            api("/admin/users/" + u.id + "/enabled", {
              method: "POST",
              body: { ...v, enabled: !u.enabled },
            }),
          u.enabled
            ? "停用后现有有效预约会关闭。"
            : "启用不会撤销已有违约或预约限制。",
        );
      }),
  );
}
const ruleFields = [
  ["advanceDays", "可预约未来天数", 0, 14],
  ["maxDurationMinutes", "单次最长分钟数", 30, 480],
  ["checkinEarlyMinutes", "提前签到分钟数", 0, 30],
  ["checkinGraceMinutes", "签到宽限分钟数", 1, 29],
  ["leaveMinutes", "暂离最长分钟数", 1, 120],
  ["leaveLimit", "暂离次数", 0, 10],
  ["violationWindowDays", "违约统计天数", 1, 30],
  ["violationLimit", "限制触发次数", 1, 20],
  ["blockDays", "限制预约天数", 1, 30],
];
async function rulePage(seq) {
  const rules = await api("/admin/rules");
  if (!current(seq)) return;
  const q = state.boot.rule;
  content.innerHTML =
    header(
      "预约规则",
      "按版本生效，既有预约继续沿用创建时的规则。",
      '<button class="primary" id="add-rule">新增规则版本</button>',
    ) +
    `<section class="panel"><div class="panel-title"><h2>当前生效 · 版本 ${q.id}</h2><span class="muted small">${stamp(q.effectiveAt)} 起</span></div><div class="form-grid">${ruleFields.map(([key, label]) => `<div class="detail-row"><span>${label}</span><b>${q[key]}</b></div>`).join("")}</div><p class="muted small">每人同时 1 条有效预约，时段步长固定为 30 分钟。当前为演示规则。</p></section><section class="panel"><h2>规则版本</h2><table><thead><tr><th>版本</th><th>生效时间</th><th>状态</th><th>最大时长</th><th>签到宽限</th><th>暂离上限</th></tr></thead><tbody>${rules.map((r) => `<tr><td>${r.id}</td><td>${stamp(r.effectiveAt)}</td><td>${r.id === q.id ? "当前生效" : r.effectiveAt > serverLocal() ? "待生效" : "历史版本"}</td><td>${r.maxDurationMinutes} 分钟</td><td>${r.checkinGraceMinutes} 分钟</td><td>${r.leaveMinutes} 分钟 / ${r.leaveLimit} 次</td></tr>`).join("")}</tbody></table></section>`;
  $("#add-rule").onclick = () => {
    const future = new Date(
      serverEpoch + Date.now() - receivedAt + 8 * 3600000 + 120000,
    )
      .toISOString()
      .slice(0, 16);
    formModal(
      "新增规则版本",
      `<label class="full">生效时间<input type="datetime-local" name="effectiveAt" required value="${future}"></label>` +
        ruleFields
          .map(([key, label, min, max]) =>
            field(
              label,
              key,
              q[key],
              "number",
              `required min="${min}" max="${max}"${key === "maxDurationMinutes" ? ' step="30"' : ""}`,
            ),
          )
          .join(""),
      (v) => api("/admin/rules", { method: "POST", body: v }),
      "只影响生效后创建的新预约；已有预约和相关违约按原版本处理。",
    );
  };
}
async function auditPage(seq) {
  const data = await api("/admin/audits?page=" + state.page);
  if (!current(seq)) return;
  content.innerHTML =
    header("操作日志", "追溯管理员的变更与异常处理。") +
    `<section class="panel">${data.items.length ? `<table><thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>对象</th><th>原因</th></tr></thead><tbody>${data.items.map((a) => `<tr><td>${stamp(a.createdAt)}</td><td>${esc(a.operatorName)}</td><td>${esc(a.operation)}</td><td>${esc(a.targetType)} #${a.targetId}</td><td>${esc(a.reason)}</td></tr>`).join("")}</tbody></table>` : empty("暂无管理操作记录")}${pager(data)}</section>`;
  bindPager();
}
async function start() {
  $("#modal").addEventListener("close", () => {
    clearInterval(detailTimer);
    detailVersion++;
  });
  try {
    await refreshBoot();
    const nav = isAdmin() ? adminNav : studentNav;
    $("#navigation").innerHTML = nav
      .map(
        ([key, title]) =>
          `<a href="#${key}"><span class="nav-label">${title}</span></a>`,
      )
      .join("");
    window.addEventListener("hashchange", () => {
      state.page = 1;
      state.status = "";
      state.search = "";
      state.date = "";
      route();
    });
    await route();
    const pending = sessionStorage.getItem("pending-" + state.boot.user.id);
    if (pending) {
      try {
        const input = JSON.parse(pending);
        const r = await api("/reservations", { method: "POST", body: input });
        sessionStorage.removeItem("pending-" + state.boot.user.id);
        notify("已确认上次提交的预约结果");
        await showDetail(r.id);
      } catch (error) {
        if (error.status)
          sessionStorage.removeItem("pending-" + state.boot.user.id);
        notify(error.message, true);
      }
    }
  } catch (error) {
    content.innerHTML = empty("加载失败", error.message);
  }
}
start();
