/**
 * Odograph → Google Sheets bridge (bind this to your Odograph workbook).
 *
 * Deploy once:
 *   1. Open the target spreadsheet: the "Google Docs link" from the box's configure page.
 *   2. Extensions → Apps Script → paste this file.
 *   3. Deploy → New deployment → Web app → Execute as: Me, access: Anyone.
 *   4. Copy the /exec URL and paste it into the box's configure page ("Google Docs link").
 *
 * What happens next:
 *   - The box POSTs its whole dataset once/twice a day (see doPost).
 *   - Tabs are REPLACED, not appended: Trips, Points, Charges, Telemetry, Analytics, SyncLog.
 *     A power cut can never leave the workbook half-written.
 *   - Analytics is rebuilt with derived numbers and charts for a non-technical viewer.
 *   - Editing the Control tab and importing (box "Import now", or any visit of /import) pushes
 *     rates and capacity back to the box.
 */
function doPost(e) {
  try {
    var body = JSON.parse(e.postData.contents);
    var ss = SpreadsheetApp.getActiveSpreadsheet();

    var trips = body.trips || [];
    var points = body.points || [];
    var charges = body.charges || [];
    var days = body.telemetry || [];
    var meta = body.meta || {};

    var tripSheet = tab(ss, 'Trips');
    writeRows(tripSheet, ['id','start','end','km','duration_s','moving_s',
      'max_kmh','avg_kmh','slowest_kmh','start_lat','start_lon','end_lat','end_lon',
      'soc_start','soc_end','energy_kwh','cost_inr','climb_m','descent_m'],
      trips.map(tripRow));

    var pointSheet = tab(ss, 'Points');
    writeRows(pointSheet, ['trip_id','t_ms','lat','lon','speed_mps','altitude_m','interpolated'],
      points.map(pointRow));

    var chargeSheet = tab(ss, 'Charges');
    writeRows(chargeSheet, ['id','start','end','start_soc','end_soc','energy_kwh','peak_kw',
      'kind','cost_inr'],
      charges.map(chargeRow));

    var teleSheet = tab(ss, 'Telemetry');
    writeRows(teleSheet, ['day','first_poll_ms','last_poll_ms'],
      days.map(function (d) { return [d.day, d.firstPollAt, d.lastPollAt]; }));

    var logSheet = tab(ss, 'SyncLog');
    logSheet.appendRow([new Date(), body.device || '', trips.length, points.length,
      charges.length, days.length]);
    if (logSheet.getLastRow() > 300) {
      logSheet.deleteRows(2, logSheet.getLastRow() - 250);
    }

    buildAnalytics(ss, trips, charges, meta);

    afterPost(ss);

    return json({ status: 'ok', trips: trips.length, points: points.length,
      charges: charges.length, telemetry: days.length });
  } catch (err) {
    return json({ status: 'error', message: String(err) });
  }
}

/** The box's import: returns the Control tab's values as JSON. */
function doGet() {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var control = ss.getSheetByName('Control');
    var out = {};
    if (control) {
      var v = control.getDataRange().getValues();
      for (var i = 0; i < v.length; i++) {
        var key = String(v[i][0]).trim();
        if (key && v[i][1] !== '') out[key] = v[i][1];
      }
    }
    return json(out);
  } catch (err) {
    return json({ error: String(err) });
  }
}

function tab(ss, name) {
  var t = ss.getSheetByName(name);
  if (!t) t = ss.insertSheet(name);
  return t;
}

function writeRows(sheet, header, rows) {
  if (sheet.getLastRow() > 0) {
    sheet.getRange(1, 1, sheet.getLastRow(), sheet.getLastColumn()).clear();
  }
  var all = [header].concat(rows);
  if (all.length) sheet.getRange(1, 1, all.length, header.length).setValues(all);
}

function tripRow(t) {
  return [t.id, epoch(t.startedAt), epoch(t.endedAt), (t.distanceM / 1000).toFixed(1),
    t.durationS, t.movingS, toKmh(t.maxSpeedMps), toKmh(t.avgSpeedMps), toKmh(t.slowestKmMps),
    t.startLat, t.startLon, t.endLat, t.endLon, t.socStart, t.socEnd, t.energyKwh,
    t.costInr, t.elevGainM, t.elevLossM];
}

function pointRow(p) {
  return [p.tripId, p.t, p.lat, p.lon, p.speedMps, p.altitudeM, p.interpolated];
}

function chargeRow(c) {
  return [c.id, epoch(c.startTime), c.endTime ? epoch(c.endTime) : '', c.startSoc, c.endSoc,
    c.energyKwh, c.peakPowerKw, c.kind || 'open', c.costInr];
}

function epoch(ms) { return ms ? new Date(ms) : ''; }
function toKmh(mps) { return mps == null ? '' : (mps * 3.6).toFixed(1); }

/**
 * The viewer-facing tab: derived numbers first, then per-month tables and charts. The whole tab
 * is rebuilt every export, so charts always reflect the snapshot just written.
 */
function buildAnalytics(ss, trips, charges, meta) {
  var sheet = tab(ss, 'Analytics');
  var charts = sheet.getCharts();
  for (var c = 0; c < charts.length; c++) sheet.removeChart(charts[c]);
  if (sheet.getLastRow() > 0) sheet.clear();

  var totalKm = 0, totalKwh = 0, totalCost = 0, effSum = 0, effN = 0;
  var months = {};
  trips.forEach(function (t) {
    var km = t.distanceM / 1000;
    totalKm += km;
    if (t.energyKwh && km > 0) { totalKwh += t.energyKwh; effSum += t.energyKwh * 100 / km; effN++; }
    if (t.costInr) totalCost += t.costInr;
    var m = monthOf(t.startedAt);
    months[m] = months[m] || { drives: 0, km: 0, kwh: 0, cost: 0, minutes: 0 };
    months[m].drives++;
    months[m].km += km;
    months[m].kwh += (t.energyKwh || 0);
    months[m].cost += (t.costInr || 0);
    months[m].minutes += (t.movingS || 0) / 60;
  });

  var range100 = effN ? Math.round(100 * meta.capacityKwh / (effSum / effN)) : '';
  var per100 = effN ? (effSum / effN).toFixed(1) : '';
  var costKwh = charges.reduce(function (a, c) { return a + (c.costInr || 0); }, 0);

  var r = 1;
  sheet.getRange(r, 1).setValue('ODOG RAPH — snapshot').setFontWeight('bold');
  r += 2;

  var stats = [
    ['Lifetime km', totalKm.toFixed(1)],
    ['Lifetime kWh', totalKwh.toFixed(1)],
    ['Trip cost ₹', totalCost.toFixed(2)],
    ['Charge cost ₹', costKwh.toFixed(2)],
    ['Avg consumption kWh/100 km', per100],
    ['Range at 100% (km)', range100],
    ['Cost ₹/km', totalKm ? (totalCost / totalKm).toFixed(2) : '']
  ];
  sheet.getRange(r, 1, stats.length, 2).setValues(stats);
  r += stats.length + 2;

  var headerRow = r;
  sheet.getRange(r, 1, 1, 6)
    .setValues([['Month', 'Drives', 'km', 'kWh', '₹', 'Moving h']])
    .setFontWeight('bold');
  r++;
  Object.keys(months).sort().forEach(function (m) {
    var mo = months[m];
    sheet.getRange(r, 1, 1, 6).setValues([[m, mo.drives, mo.km.toFixed(1), mo.kwh.toFixed(1),
      mo.cost.toFixed(2), (mo.minutes / 60).toFixed(1)]]);
    r++;
  });

  var bodyRows = r - headerRow - 1;
  if (bodyRows > 0) {
    var monthRange = sheet.getRange(headerRow, 1, bodyRows + 1, 6);
    var colChart = sheet.newChart().asColumnChart()
      .addRange(monthRange)
      .setPosition(bodyRows + 3, 8, 0, 0)
      .build();
    sheet.insertChart(colChart);
  }

  var byKind = {};
  charges.forEach(function (c) {
    if (c.costInr) byKind[c.kind || 'open'] = (byKind[c.kind || 'open'] || 0) + c.costInr;
  });
  if (Object.keys(byKind).length) {
    var kr = sheet.getLastRow() + 3;
    sheet.getRange(kr, 1).setValue('Charge cost by kind').setFontWeight('bold');
    var kd = [];
    Object.keys(byKind).forEach(function (k) { kd.push([k, byKind[k]]); });
    sheet.getRange(kr + 1, 1, kd.length, 2).setValues(kd);
    var pie = sheet.newChart().asPieChart()
      .addRange(sheet.getRange(kr, 1, kd.length + 1, 2))
      .setPosition(kr + 1, 4, 0, 0)
      .build();
    sheet.insertChart(pie);
  }
  sheet.setFrozenRows(1);
}

function afterPost(ss) {
  // Fresh workbooks are usually a single "Sheet1" the user never asked for; hide it once the
  // four owned tabs exist so the workbook reads cleanly. Harmless whenever it is already gone.
  var stray = ss.getSheetByName('Sheet1');
  if (stray && !stray.isSheetHidden() && ss.getSheets().length > 4) stray.hideSheet();
}

function monthOf(ms) {
  if (!ms) return '?';
  var d = new Date(ms);
  return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2);
}

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o))
    .setMimeType(ContentService.MimeType.JSON);
}