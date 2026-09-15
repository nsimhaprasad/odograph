"use strict";

const $ = (sel) => document.querySelector(sel);

function setText(key, text) {
  const el = $(`[data-k="${key}"]`);
  if (el) el.textContent = text;
}

function fmt(n) {
  if (n === null || n === undefined || Number.isNaN(n)) return "—";
  return Math.round(n * 10) / 10;
}

function colorFor(v, min, max) {
  const t = Math.max(0, Math.min(1, (v - min) / (max - min)));
  const r = Math.round(255 * (1 - t));
  const g = Math.round(255 * t);
  const b = 80;
  return `rgb(${r},${g},${b})`;
}

async function getJson(path) {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  const text = await res.text();
  return text.trim().startsWith("[") || text.trim().startsWith("{") ? JSON.parse(text) : text;
}

function drawBars(canvas, values, labels) {
  const ctx = canvas.getContext("2d");
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth || canvas.width;
  const h = canvas.clientHeight || canvas.height;
  canvas.width = w * dpr; canvas.height = h * dpr;
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);
  if (!values.length) return;
  const max = Math.max(...values, 1);
  const pad = 4, gap = 2;
  const bw = (w - pad * 2) / values.length;
  values.forEach((v, i) => {
    const bh = (v / max) * (h - 24);
    const x = pad + i * bw;
    ctx.fillStyle = colorFor(v, Math.min(...values), max);
    ctx.fillRect(x + gap / 2, h - 20 - bh, bw - gap, bh);
    if (labels && labels[i]) {
      ctx.fillStyle = "#8b95a5";
      ctx.font = "10px monospace";
      ctx.fillText(labels[i], x + 2, h - 6);
    }
  });
}

function drawSpark(canvas, values, fill) {
  const ctx = canvas.getContext("2d");
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth || canvas.width;
  const h = canvas.clientHeight || canvas.height;
  canvas.width = w * dpr; canvas.height = h * dpr;
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);
  if (values.length < 2) return;
  const min = Math.min(...values), max = Math.max(...values), span = max - min || 1;
  const step = w / (values.length - 1);
  ctx.beginPath();
  values.forEach((v, i) => {
    const x = i * step;
    const y = h - 10 - ((v - min) / span) * (h - 20);
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.strokeStyle = fill || "#4ec9b0";
  ctx.lineWidth = 2;
  ctx.stroke();
}

function renderCost(d) {
  if (typeof d === "string" || !d.tripCostInr) {
    setText("costTrip", "off or empty");
    setText("costCharge", "");
    setText("costKm", "");
    return;
  }
  setText("costTrip", `trips ₹${fmt(d.tripCostInr)} · ${fmt(d.tripKm)} km`);
  setText("costCharge", `charging ₹${fmt(d.chargeCostInr)} · ${d.chargeSessions} sessions · ${fmt(d.chargeEnergyKwh)} kWh`);
  setText("costKm", `${fmt(d.tripEnergyKwh)} kWh driven`);
}

function renderRoutes(rows) {
  const tbody = $("#routeRows");
  tbody.innerHTML = "";
  if (!Array.isArray(rows) || !rows.length) {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td colspan="4" class="note">no qualifying routes yet (≥3 drives, ≥30 km total)</td>`;
    tbody.appendChild(tr);
    return;
  }
  rows.forEach((r) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td>${r.startName} → ${r.endName}</td><td>${Math.round(r.energyKwh)} kWh</td><td>${fmt(r.distanceKm)} km</td><td>${fmt(r.energyKwh / r.distanceKm)} kWh/km</td>`;
    tbody.appendChild(tr);
  });
}

function renderPlaces(list) {
  const ul = $("#placeList");
  ul.innerHTML = "";
  (Array.isArray(list) ? list : []).forEach((p) => {
    const li = document.createElement("li");
    li.textContent = `${p.name} · ${p.visits} visit${p.visits === 1 ? "" : "s"}`;
    ul.appendChild(li);
  });
}

function renderTelemetry(days) {
  const strip = $("#teleStrip");
  strip.innerHTML = "";
  (Array.isArray(days) ? days : []).slice(-45).forEach((d) => {
    const cell = document.createElement("div");
    cell.className = "cell" + (d.lastPollAt > 0 ? " on" : "");
    cell.title = `day ${d.day}: polls window present`;
    strip.appendChild(cell);
  });
}

function renderDrain(frames) {
  const arr = Array.isArray(frames) ? frames : [];
  const note = arr.length
    ? `samples since ${new Date(arr[0].t * 1000).toLocaleDateString()}; soc at rest`
    : "no parked samples yet";
  setText("drainNote", note);
}

async function refresh() {
  try {
    const live = await getJson("/api/live");
    setText("liveStatus", live.hasFix ? "live" : "no fix");
    if (live.batterySocPercent !== null && live.batterySocPercent !== undefined) {
      setText("soc", `${fmt(live.batterySocPercent)}%`);
    } else {
      setText("soc", "SOC —");
    }
    setText("range", live.batteryRangeKm ? `${fmt(live.batteryRangeKm)} km` : "range —");
    setText("charge", live.batteryCharging === null ? "" : live.batteryCharging ? "charging" : "not charging");
    setText("trip", live.tripId >= 0 ? `trip #${live.tripId}` : "idle");
  } catch (e) {
    setText("liveStatus", "offline");
  }

  try {
    const cost = await getJson("/api/cost?bucket=" + ($(".bucket.on")?.dataset.bucket || "30d"));
    renderCost(cost);
    const costChart = $("#costChart");
    costChart.setAttribute("width", 640);
    costChart.setAttribute("height", 180);
    const chartData = await getJson("/api/cost?bucket=" + ($(".bucket.on")?.dataset.bucket || "30d"));
    if (typeof chartData === "object") {
      drawBars(costChart, [chartData.tripCostInr || 0, chartData.chargeCostInr || 0], ["driving", "charging"]);
    }
  } catch (e) { /* api gate / off */ }

  try {
    const range = await getJson("/api/range");
    if (Array.isArray(range) && range.length) {
      drawSpark($("#rangeChart"), range.map((d) => d.distanceKm / Math.max(d.energyKwh, 0.01)));
      setText("rangeNote", `${range.length} instrumented days`);
    } else {
      setText("rangeNote", "not enough days yet");
    }
  } catch (e) { /* gate off */ }

  try { renderRoutes(await getJson("/api/routes")); } catch (e) {}
  try { renderPlaces(await getJson("/api/places")); } catch (e) {}
  try { renderTelemetry(await getJson("/api/telemetry")); } catch (e) {}
  try { renderDrain(await getJson("/api/drain")); } catch (e) {}
}

document.querySelectorAll(".bucket").forEach((b) => {
  b.addEventListener("click", (ev) => {
    document.querySelectorAll(".bucket").forEach((x) => x.classList.remove("on"));
    ev.target.classList.add("on");
    refresh();
  });
});

$("#backupBtn").addEventListener("click", async () => {
  try {
    const res = await fetch("/backup");
    const blob = await res.blob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `odograph-backup-${new Date().toISOString().slice(0, 10)}.db`;
    a.click();
    setText("backupNote", `downloaded ${(blob.size / 1024).toFixed(1)} kB`);
  } catch (e) {
    setText("backupNote", "backup unavailable");
  }
});

$("#restoreBtn").addEventListener("click", () => $("#restoreFile").click());
$("#restoreFile").addEventListener("change", async (ev) => {
  const file = ev.target.files[0];
  if (!file) return;
  if (!confirm(`Replace all box data with ${file.name}? This cannot be undone.`)) {
    ev.target.value = "";
    return;
  }
  try {
    const res = await fetch("/restore", { method: "POST", body: file });
    setText("backupNote", `restore: ${await res.text()}`);
  } catch (e) {
    setText("backupNote", "restore failed");
  }
  ev.target.value = "";
});

refresh();
setInterval(refresh, 5000);