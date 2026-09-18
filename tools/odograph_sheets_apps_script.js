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
 *   - The box uploads only what is NEW since its last successful upload (see doPost): closed
 *     trips with their points, closed charge sessions and coverage days. Rows are APPENDED or
 *     overwritten by key, never re-uploaded wholesale, so a payload never grows with history.
 *   - The upload is idempotent: if the box retries a POST that partially failed, the same keys
 *     are overwritten instead of duplicated.
 *   - Analytics is rebuilt from the workbook's own accumulated history, with derived numbers and
 *     charts for a non-technical viewer.
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
    var places = body.places || [];
    var battery = body.battery || [];
    var meta = body.meta || {};

    var tripSheet = tab(ss, 'Trips');
    var tripCols = ['id','start','end','km','duration_s','moving_s','max_kmh','avg_kmh',
      'slowest_kmh','start_lat','start_lon','end_lat','end_lon','soc_start','soc_end',
      'energy_kwh','cost_inr','climb_m','descent_m'];
    upsertRows(tripSheet, tripCols, trips.map(tripRow), 0);

    var pointSheet = tab(ss, 'Points');
    var pointCols = ['trip_id','t_ms','lat','lon','speed_mps','altitude_m','interpolated'];
    appendNewPoints(pointSheet, pointCols, points.map(pointRow));

    var chargeSheet = tab(ss, 'Charges');
    var chargeCols = ['id','start','end','start_soc','end_soc','energy_kwh','peak_kw','kind','cost_inr'];
    upsertRows(chargeSheet, chargeCols, charges.map(chargeRow), 0);

    var teleSheet = tab(ss, 'Telemetry');
    var teleCols = ['day','first_poll_ms','last_poll_ms'];
    upsertRows(teleSheet, teleCols, days.map(function (d) { return [d.day, d.firstPollAt, d.lastPollAt]; }), 0);

    // Places and battery frames are what make this sheet a backup rather than a report. Without
    // places a restored trip knows where it went but not what that place is called, and every
    // route grouping is lost; without battery frames the pack has no measured health.
    var placeSheet = tab(ss, 'Places');
    var placeCols = ['id','lat','lon','visits','label','auto_name','geocoded_at'];
    upsertRows(placeSheet, placeCols, places.map(placeRow), 0);

    var batterySheet = tab(ss, 'Battery');
    var batteryCols = ['id','trip_id','t_ms','soc_pct','charging','range_km','charge_kw',
      'odometer_km','battery_kwh','exterior_temp_c'];
    upsertRows(batterySheet, batteryCols, battery.map(batteryRow), 0);

    var logSheet = tab(ss, 'SyncLog');
    logSheet.appendRow([new Date(), body.device || '', trips.length, points.length,
      charges.length, days.length, places.length, battery.length, meta.schema || '']);
    if (logSheet.getLastRow() > 300) {
      logSheet.deleteRows(2, logSheet.getLastRow() - 250);
    }

    buildAnalytics(ss, meta);

    afterPost(ss);

    return json({ status: 'ok', trips: trips.length, points: points.length,
      charges: charges.length, telemetry: days.length,
      places: places.length, battery: battery.length });
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

/** First-column lookup: key -> sheet row number (1-based), header row excluded. */
function indexByFirstColumn(sheet) {
  var map = {};
  var values = sheet.getDataRange().getValues();
  for (var r = 1; r < values.length; r++) {
    var key = String(values[r][0]);
    if (key !== '' && !(key in map)) map[key] = r + 1;
  }
  return map;
}

/**
 * Overwrite rows whose key already exists, append the rest. Retry-safe: the same key can be
 * re-sent after a partial failure without turning into a duplicate row.
 */
function upsertRows(sheet, header, rows, keyCol) {
  if (rows.length === 0) return;
  ensureHeader(sheet, header);
  var existing = indexByFirstColumn(sheet);
  var fresh = [];
  rows.forEach(function (row) {
    var key = String(row[keyCol]);
    if (key in existing) {
      sheet.getRange(existing[key], 1, 1, header.length).setValues([row]);
    } else {
      fresh.push(row);
    }
  });
  if (fresh.length) {
    sheet.getRange(sheet.getLastRow() + 1, 1, fresh.length, header.length).setValues(fresh);
  }
}

function placeRow(p) {
  return [p.id, p.lat, p.lon, p.visits, p.label || '', p.autoName || '', p.geocodedAt || ''];
}

function batteryRow(b) {
  // Blank rather than zero for a missing reading: "not measured" and "measured as nothing" are
  // different facts, and flattening the first into the second restores a car that was measured
  // when it was not.
  function n(v) { return (v === null || v === undefined) ? '' : v; }
  return [b.id, b.tripId, b.t, n(b.socPercent), n(b.charging), n(b.rangeKm),
    n(b.chargingPowerKw), n(b.odometerKm), n(b.batteryEnergyKwh), n(b.exteriorTempC)];
}

/** Append a trip's points once, and once only. */
function appendNewPoints(sheet, header, rows) {
  if (rows.length === 0) return;
  ensureHeader(sheet, header);
  var present = indexByFirstColumn(sheet);
  var fresh = rows.filter(function (row) { return !(String(row[0]) in present); });
  if (fresh.length) {
    sheet.getRange(sheet.getLastRow() + 1, 1, fresh.length, header.length).setValues(fresh);
  }
}

function ensureHeader(sheet, header) {
  if (!sheet.getRange(1, 1, 1, header.length).getValues()[0].join('|').length) {
    sheet.getRange(1, 1, 1, header.length).setValues([header]);
  }
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
 * The viewer-facing tab: derived numbers first, then per-month tables and charts. Rebuilt each
 * upload from the workbook's own accumulated history, so totals stay right even though the box
 * only ever sends the newest rows.
 */
function buildAnalytics(ss, meta) {
  var sheet = tab(ss, 'Analytics');
  var charts = sheet.getCharts();
  for (var c = 0; c < charts.length; c++) sheet.removeChart(charts[c]);
  if (sheet.getLastRow() > 0) sheet.clear();

  var capacityKwh = Number(meta.capacityKwh) || 0;

  var trips = readTrips(ss);
  var charges = readCharges(ss);

  var totalKm = 0, totalKwh = 0, totalCost = 0, effSum = 0, effN = 0;
  var months = {};
  trips.forEach(function (t) {
    var km = (Number(t.km) || 0);
    totalKm += km;
    if (t.kwh != null && Number(t.kwh) > 0 && km > 0) {
      totalKwh += Number(t.kwh);
      effSum += Number(t.kwh) * 100 / km;
      effN++;
    }
    if (t.cost != null) totalCost += Number(t.cost);
    var m = monthOf(t.start);
    months[m] = months[m] || { drives: 0, km: 0, kwh: 0, cost: 0, minutes: 0 };
    months[m].drives++;
    months[m].km += km;
    months[m].kwh += (Number(t.kwh) || 0);
    months[m].cost += (Number(t.cost) || 0);
    months[m].minutes += (Number(t.moving) || 0) / 60;
  });

  var range100 = effN && capacityKwh ? Math.round(100 * capacityKwh / (effSum / effN)) : '';
  var per100 = effN ? (effSum / effN).toFixed(1) : '';
  var costKwh = charges.reduce(function (a, c) { return a + (Number(c.cost) || 0); }, 0);

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
    var colChart = sheet.newChart().asColumnChart()
      .addRange(sheet.getRange(headerRow, 1, bodyRows + 1, 6))
      .setPosition(bodyRows + 3, 8, 0, 0)
      .build();
    sheet.insertChart(colChart);
  }

  var byKind = {};
  charges.forEach(function (c) {
    var kind = c.kind || 'open';
    if (Number(c.cost) > 0) byKind[kind] = (byKind[kind] || 0) + Number(c.cost);
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

function readTrips(ss) {
  var sheet = ss.getSheetByName('Trips');
  if (!sheet) return [];
  var v = sheet.getDataRange().getValues();
  if (v.length < 2) return [];
  var h = v[0], out = [];
  var col = function (name) { return h.indexOf(name); };
  var iStart = col('start'), iKm = col('km'), iMoving = col('moving_s'),
    iKwh = col('energy_kwh'), iCost = col('cost_inr');
  for (var r = 1; r < v.length; r++) {
    var s = v[r][iStart];
    out.push({ start: s instanceof Date ? s.getTime() : s,
      km: v[r][iKm], moving: v[r][iMoving], kwh: num(v[r][iKwh]), cost: num(v[r][iCost]) });
  }
  return out;
}

function readCharges(ss) {
  var sheet = ss.getSheetByName('Charges');
  if (!sheet) return [];
  var v = sheet.getDataRange().getValues();
  if (v.length < 2) return [];
  var h = v[0], out = [];
  var iKind = h.indexOf('kind'), iCost = h.indexOf('cost_inr');
  for (var r = 1; r < v.length; r++) {
    out.push({ kind: v[r][iKind], cost: num(v[r][iCost]) });
  }
  return out;
}

function num(x) { return x == null || x === '' ? null : Number(x); }

function afterPost(ss) {
  // Fresh workbooks are usually a single "Sheet1" the user never asked for; hide it once the
  // owned tabs exist so the workbook reads cleanly. Harmless whenever it is already gone.
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