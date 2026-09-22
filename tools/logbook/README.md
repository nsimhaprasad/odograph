# Windsor Logbook

A single HTML file. Open it from disk — no server, no hosting, no build:

    open tools/logbook/index.html

## Where it gets its numbers

Three sources, any of which is enough on its own.

**The box, over the LAN.** Put the head unit's address in (`http://10.66.102.154:8080`)
and press refresh. This is the freshest answer and needs the car to be on the same
network. The address is remembered, and the page fetches on load once it knows one.

Needs the box running a build whose LAN server sends `Access-Control-Allow-Origin` —
without it a page opened from your own disk sends `Origin: null`, the browser applies
the same-origin rule, and the request never leaves. Silently: nothing appears in the
page to say why, which is worth knowing before blaming the car.

**The backup sheet.** Paste the Apps Script's `/exec` link. Works from anywhere, and is
as fresh as the last successful upload. The spreadsheet's own link is not this link —
that one serves a web page, not the data, and the page says so rather than failing
obscurely.

**A saved export.** Drop any of the box's JSON responses (`/api/trips`, `/api/charges`,
`/api/places`, `/api/range`, `/api/drain`) or the sheet's `?export=all` bundle onto the
page. Several at once is fine; each is recognised by its shape. This path needs no
network at all.

The file ships with a snapshot of real data so it shows something the moment it opens.
Place coordinates are deliberately not in it: a dashboard needs to know where, not
exactly where, and a home address should not be the thing that leaks.

## What it shows

Lifetime totals, then mileage drive by drive against the 5–8 km/kW·h band this car can
physically manage, then daily distance, charging sessions, places by visit, standing
drain while parked, and every drive.

Two things are flagged in red rather than smoothed over, because both are real and both
are worth seeing: a charge session labelled *fast* that averaged single-digit kW, and any
drive claiming a mileage the car cannot produce. The second is the whole-percent charge
measurement showing through — one percent of this pack is 0.53 kW·h, and across a short
hop that single step is most of the reading.
