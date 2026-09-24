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
      'energy_kwh','cost_inr','climb_m','descent_m',
      'avg_temp_c','climate_share','car_energy_kwh','car_distance_km','cluster_id',
      'start_place_id','end_place_id',
      'estimated_energy_kwh','estimated_cost_inr','energy_source'];
    upsertRows(tripSheet, tripCols, trips.map(tripRow), 0);

    var pointSheet = tab(ss, 'Points');
    var pointCols = ['trip_id','t_ms','lat','lon','speed_mps','altitude_m','interpolated'];
    appendNewPoints(pointSheet, pointCols, points.map(pointRow));

    var chargeSheet = tab(ss, 'Charges');
    var chargeCols = ['id','start','end','start_soc','end_soc','energy_kwh','peak_kw','kind','cost_inr',
      'delivered_kwh','place_id','samples_total','samples_above','reconstructed',
      'entered_rate_inr','entered_bill_inr','gst_rate_pct'];
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
      'odometer_km','battery_kwh','exterior_temp_c',
      'working_v','working_a','charge_remaining_min','dist_since_charge_km','power_since_charge_kwh',
      'climate_on','interior_temp_c','charging_type','plugged_in','car_capacity_kwh','aux_v',
      'car_journey_id','car_journey_dist_raw','engine_status_raw','power_mode_raw','handbrake',
      'tyre_fl_psi','tyre_fr_psi','tyre_rl_psi','tyre_rr_psi',
      'car_gps_sats','car_gps_status','car_speed_kmh','charger_id','charger_supplier',
      'last_charge_end_kwh','static_drain_raw','charge_elapsed_s','day_dist_raw','day_power_raw'];
    upsertRows(batterySheet, batteryCols, battery.map(batteryRow), 0);

    // The schema the writing app used, kept where a later restore can ask the sheet what shape
    // it is rather than taking the reader's word for it. A restore that trusts the caller's claim
    // is not a version check at all.
    if (meta.schema) {
      PropertiesService.getDocumentProperties().setProperty('schema', String(meta.schema));
    }

    var logSheet = tab(ss, 'SyncLog');
    logSheet.appendRow([new Date(), body.device || '', trips.length, points.length,
      charges.length, days.length, places.length, battery.length, meta.schema || '']);
    if (logSheet.getLastRow() > 300) {
      logSheet.deleteRows(2, logSheet.getLastRow() - 250);
    }

    // A catch-up run sends many pages; the analytics tab only needs rebuilding after the last.
    if (!meta.more) buildAnalytics(ss, meta);

    afterPost(ss);

    return json({ status: 'ok', trips: trips.length, points: points.length,
      charges: charges.length, telemetry: days.length,
      places: places.length, battery: battery.length });
  } catch (err) {
    return json({ status: 'error', message: String(err) });
  }
}

/** The box's import: returns the Control tab's values as JSON. */
/**
 * Reads a tab back as objects, keyed by its header row.
 *
 * Blank cells come back as null rather than empty string or zero, because the app writes a blank
 * for "not measured" and a restore that turns those into numbers rebuilds a car that was measured
 * when it was not.
 */
function readTab(ss, name) {
  var sheet = ss.getSheetByName(name);
  if (!sheet || sheet.getLastRow() < 2) return [];
  var values = sheet.getDataRange().getValues();
  var header = values[0];
  var rows = [];
  for (var r = 1; r < values.length; r++) {
    var obj = {};
    for (var c = 0; c < header.length; c++) {
      var key = String(header[c]);
      var cell = values[r][c];
      obj[key] = (cell === '' || cell === null || cell === undefined) ? null : cell;
    }
    rows.push(obj);
  }
  return rows;
}

function doGet(e) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();

    // The whole sheet, for restoring a box that has lost its database. Behind a parameter so the
    // ordinary control read stays small and cheap — this one can be megabytes.
    if (e && e.parameter && e.parameter.export === 'all') {
      return json({
        kind: 'odograph-backup',
        schema: writtenSchema(),
        exportedAt: new Date().getTime(),
        trips: readTab(ss, 'Trips'),
        points: readTab(ss, 'Points'),
        charges: readTab(ss, 'Charges'),
        telemetry: readTab(ss, 'Telemetry'),
        places: readTab(ss, 'Places'),
        battery: readTab(ss, 'Battery')
      });
    }

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

/**
 * The schema version the app that filled this workbook was writing, or null if it never said.
 *
 * Null means a workbook written before this was recorded, not a workbook of version zero, and the
 * two must not be confused: the reader treats null as "assume my own era", which is safe only
 * because every tab is read by column name.
 */
function writtenSchema() {
  var v = PropertiesService.getDocumentProperties().getProperty('schema');
  return v ? Number(v) : null;
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
  sheet = ensureHeader(sheet, header);
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
    n(b.chargingPowerKw), n(b.odometerKm), n(b.batteryEnergyKwh), n(b.exteriorTempC),
    n(b.workingVoltage), n(b.workingCurrent), n(b.chargeTimeRemainingMin),
    n(b.distanceSinceLastChargeKm), n(b.powerUsageSinceLastChargeKwh),
    n(b.climateRunning), n(b.interiorTempC), n(b.chargingType), n(b.pluggedIn),
    n(b.carCapacityKwh), n(b.auxVoltage), n(b.carJourneyId), n(b.carJourneyDistanceRaw),
    n(b.engineStatusRaw), n(b.powerModeRaw), n(b.handbrake),
    n(b.tyreFlPsi), n(b.tyreFrPsi), n(b.tyreRlPsi), n(b.tyreRrPsi),
    n(b.carGpsSatellites), n(b.carGpsStatus), n(b.carSpeedKmh),
    n(b.chargerId), n(b.chargerSupplier), n(b.lastChargeEndKwh),
    n(b.staticDrainRaw), n(b.chargeElapsedS), n(b.dayDistanceRaw), n(b.dayPowerRaw)];
}

/** Append a trip's points once, and once only. */
function appendNewPoints(sheet, header, rows) {
  if (rows.length === 0) return;
  sheet = ensureHeader(sheet, header);
  var present = indexByFirstColumn(sheet);
  var fresh = rows.filter(function (row) { return !(String(row[0]) in present); });
  if (fresh.length) {
    sheet.getRange(sheet.getLastRow() + 1, 1, fresh.length, header.length).setValues(fresh);
  }
}

/**
 * Makes sure row 1 is this header, and that the rows under it are in this layout.
 *
 * The old check was `join('|').length`, and an empty row of twenty-nine cells joins to
 * twenty-eight pipe characters — never zero — so no tab ever received a header, and a tab that
 * already held rows in an older layout quietly took new rows in the new one underneath them.
 * A workbook like that cannot be restored from, because the reader finds no column names.
 *
 * Three cases now: an empty tab gets the header; a tab whose row 1 is this header is fine; a
 * tab holding anything else is renamed aside, untouched, and a clean tab takes its name. Nothing
 * is ever deleted — the old rows stay readable under the old name.
 */
function ensureHeader(sheet, header) {
  var first = sheet.getLastRow() === 0 ? [] : sheet.getRange(1, 1, 1, header.length).getValues()[0];
  var empty = first.every(function (v) { return v === '' || v === null; });
  if (empty) {
    sheet.getRange(1, 1, 1, header.length).setValues([header]);
    return sheet;
  }
  var matches = first.length === header.length &&
    first.every(function (v, i) { return String(v) === header[i]; });
  if (matches) return sheet;
  var ss = sheet.getParent();
  var name = sheet.getName();
  var n = 1;
  while (ss.getSheetByName(name + ' (old ' + n + ')')) n++;
  sheet.setName(name + ' (old ' + n + ')');
  var fresh = ss.insertSheet(name);
  fresh.getRange(1, 1, 1, header.length).setValues([header]);
  return fresh;
}

function tripRow(t) {
  function n(v) { return (v === null || v === undefined) ? '' : v; }
  return [t.id, epoch(t.startedAt), epoch(t.endedAt), (t.distanceM / 1000).toFixed(1),
    t.durationS, t.movingS, toKmh(t.maxSpeedMps), toKmh(t.avgSpeedMps), toKmh(t.slowestKmMps),
    t.startLat, t.startLon, t.endLat, t.endLon, t.socStart, t.socEnd, t.energyKwh,
    t.costInr, t.elevGainM, t.elevLossM,
    // The conditions the drive was made in. Without them a restored history can say what every
    // drive cost but no longer why, and every efficiency split comes back empty.
    n(t.avgTempC), n(t.climateShare), n(t.carEnergyKwh), n(t.carDistanceKm), n(t.clusterId),
    n(t.startPlaceId), n(t.endPlaceId),
    // Kept in their own columns, never merged into energy_kwh. That column is what the app's
    // efficiency model learns from, and a reckoned figure sitting in it would be trained on.
    n(t.estimatedEnergyKwh), n(t.estimatedCostInr), n(t.energySource)];
}

function pointRow(p) {
  return [p.tripId, p.t, p.lat, p.lon, p.speedMps, p.altitudeM, p.interpolated];
}

function chargeRow(c) {
  function n(v) { return (v === null || v === undefined) ? '' : v; }
  return [c.id, epoch(c.startTime), c.endTime ? epoch(c.endTime) : '', c.startSoc, c.endSoc,
    c.energyKwh, c.peakPowerKw, c.kind || 'open', c.costInr,
    // deliveredKwh is typed in by hand from a charger app or a wall meter. It is the one value
    // here that no amount of re-polling could ever recover, and it was not being written down.
    n(c.deliveredKwh), n(c.placeId), n(c.samplesTotal), n(c.samplesAbove), n(c.reconstructed),
    n(c.enteredRateInr), n(c.enteredBillInr), n(c.gstRatePct)];
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