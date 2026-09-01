# Optional: send drives to your own Google Sheet

**None of this is required.** Odograph records, displays and exports with the network off. This
adds a way to accumulate drives in a spreadsheet you own, and to receive email summaries, without
putting any credential on a device that lives in your car.

## Why this shape

| Approach | Why not |
|---|---|
| SMTP email from the app | Needs a Gmail app password stored on a dongle in your car |
| Drive API | Needs OAuth, which needs Play Services — unverified on this box |
| **Apps Script web app** | Device holds only a URL. Revocable in one click. No OAuth, no Play Services, no password. |

The URL is a **capability token**, not a credential: it grants access to one script you wrote,
and nothing else in your account.

## Setup, once

1. Create a Google Sheet. Copy its ID from the URL
   (`docs.google.com/spreadsheets/d/`**`THIS_PART`**`/edit`).
2. Go to <https://script.google.com>, new project, paste the script below, replace `SHEET_ID`.
3. **Deploy → New deployment → Web app**, with:
   - *Execute as*: **Me**
   - *Who has access*: **Anyone** — required, because the box sends no credentials. The URL is
     unguessable and is the only thing protecting it. Treat it like a password.
4. Copy the `/exec` URL.
5. On the box, open the dashboard from your Mac (`http://<box-ip>:8080/config`) and paste it there.
   Pasting on a real keyboard rather than typing on a car touchscreen is the entire reason that
   page exists.

## The script

```javascript
const SHEET_ID = 'PASTE_YOUR_SHEET_ID_HERE';

function doPost(e) {
  const payload = JSON.parse(e.postData.contents);
  const book = SpreadsheetApp.openById(SHEET_ID);
  const sheet = book.getSheetByName('Drives') || book.insertSheet('Drives');

  if (sheet.getLastRow() === 0) {
    sheet.appendRow(['device', 'trip_id', 'started', 'ended', 'km', 'elapsed_s',
                     'moving_s', 'max_kmh', 'avg_kmh', 'slowest_km_kmh',
                     'start_lat', 'start_lon', 'end_lat', 'end_lon']);
  }

  const seen = sheet.getRange(1, 1, Math.max(sheet.getLastRow(), 1), 2).getValues()
                    .map(r => r[0] + '#' + r[1]);

  (payload.trips || []).forEach(t => {
    // The device retries anything it did not get an ack for, so skip duplicates.
    if (seen.indexOf(t.device + '#' + t.id) !== -1) return;
    sheet.appendRow([
      t.device, t.id, new Date(t.startedAt), new Date(t.endedAt),
      +(t.distanceM / 1000).toFixed(2), t.durationS, t.movingS,
      +(t.maxSpeedMps * 3.6).toFixed(1), +(t.avgSpeedMps * 3.6).toFixed(1),
      +(t.slowestKmMps * 3.6).toFixed(1),
      t.startLat, t.startLon, t.endLat, t.endLon
    ]);
  });

  return ContentService
    .createTextOutput(JSON.stringify({ ok: true, received: (payload.trips || []).length }))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Optional. Triggers → Add trigger → weeklySummary → Time-driven → Week timer.
 * Email without an SMTP credential ever touching the car.
 */
function weeklySummary() {
  const rows = SpreadsheetApp.openById(SHEET_ID).getSheetByName('Drives')
                             .getDataRange().getValues().slice(1);
  const weekAgo = Date.now() - 7 * 24 * 3600 * 1000;
  const recent = rows.filter(r => new Date(r[2]).getTime() > weekAgo);

  const km = recent.reduce((a, r) => a + Number(r[4] || 0), 0);
  const mins = recent.reduce((a, r) => a + Number(r[5] || 0), 0) / 60;
  const top = recent.reduce((a, r) => Math.max(a, Number(r[7] || 0)), 0);

  MailApp.sendEmail({
    to: Session.getActiveUser().getEmail(),
    subject: 'Odograph — last 7 days',
    body: [
      recent.length + ' drives',
      km.toFixed(1) + ' km',
      Math.round(mins) + ' minutes',
      'top speed ' + top.toFixed(0) + ' km/h'
    ].join('\n')
  });
}
```

## Behaviour when the network is absent

Sending is attempted on service start and from **Sync now** on the config page. Every failure is
swallowed and the trip stays queued (`syncedAt IS NULL`) for the next attempt. A deleted endpoint,
a tunnel, or a hotspot that never came up changes nothing about recording, the cluster, the local
dashboard, or CSV/GPX export.
