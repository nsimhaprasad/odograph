package `in`.odograph.tracker.server

import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.RouteSummary
import `in`.odograph.tracker.data.TripEntity

object DashboardHtml {

    /**
     * One file, no network. Downloaded once from the car it opens on a laptop forever after,
     * offline, with the ignition off — which is the whole point, since analysis happens at a
     * desk and the box lives in the car.
     */
    fun render(
        trips: List<TripEntity>,
        pointsByTrip: Map<Long, List<PointEntity>>,
        webhookConfigured: Boolean = false
    ): String {
        val tripsJson = trips.joinToString(",", "[", "]") { t ->
            """{"id":${t.id},"startedAt":${t.startedAt},"endedAt":${t.endedAt ?: 0},""" +
                """"distanceM":${t.distanceM},"durationS":${t.durationS},""" +
                """"movingS":${t.movingS},"maxSpeedMps":${t.maxSpeedMps},""" +
                """"avgSpeedMps":${t.avgSpeedMps},"synced":${t.syncedAt != null}}"""
        }
        val routesJson = pointsByTrip.entries.joinToString(",", "{", "}") { (id, pts) ->
            "\"$id\":" + pts.joinToString(",", "[", "]") { "[${it.lat},${it.lon}]" }
        }
        val syncBadge = if (webhookConfigured) "sync on" else "local only"

        return """<!doctype html>
<title>Odograph Archive</title>
<style>
:root{color-scheme:dark;--bg:#08090C;--panel:#0D1015;--fg:#EAEEF4;--dim:#8B96A5;
--label:#5C6877;--line:#1E2530;--accent:#3DE1FF;--accent2:#7B5CFF}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);padding:36px 28px;
 font:14px/1.55 ui-sans-serif,system-ui,-apple-system,sans-serif}
.wrap{max-width:1180px;margin:0 auto}
.eyebrow{font-family:ui-monospace,monospace;font-size:11px;letter-spacing:.18em;
 text-transform:uppercase;color:var(--label);display:flex;gap:10px;align-items:center}
.eyebrow b{color:var(--accent);font-weight:500}
h1{font-size:26px;margin:12px 0 4px;letter-spacing:-.01em}
p.sub{color:var(--dim);margin:0 0 26px}
.cards{display:flex;gap:1px;background:var(--line);border:1px solid var(--line);
 margin-bottom:26px;flex-wrap:wrap;border-radius:4px;overflow:hidden}
.cards div{background:var(--panel);padding:16px 22px;flex:1;min-width:132px}
.cards b{display:block;font-size:27px;font-weight:600;font-variant-numeric:tabular-nums}
.cards span{color:var(--label);font-size:10px;letter-spacing:.14em;text-transform:uppercase}
.split{display:grid;grid-template-columns:1fr 300px;gap:22px;align-items:start}
@media(max-width:860px){.split{grid-template-columns:1fr}}
.panel{border:1px solid var(--line);border-radius:4px;overflow:hidden;background:var(--panel)}
.panel h2{font-size:11px;letter-spacing:.16em;text-transform:uppercase;color:var(--label);
 margin:0;padding:12px 16px;border-bottom:1px solid var(--line);font-weight:500}
table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
th,td{text-align:right;padding:9px 14px;border-bottom:1px solid var(--line);font-size:13px}
th{color:var(--label);font-weight:500;font-size:10px;letter-spacing:.1em;text-transform:uppercase}
td:first-child,th:first-child{text-align:left}
tbody tr{cursor:pointer}
tbody tr:hover{background:#141922}
tbody tr.on{background:#141922;box-shadow:inset 2px 0 0 var(--accent)}
svg{display:block;width:100%;height:290px;background:#050609}
.note{color:var(--label);font-size:11px;font-family:ui-monospace,monospace;padding:10px 16px}
</style>
<div class="wrap">
  <div class="eyebrow"><b>Odograph</b> &middot; archive &middot; $syncBadge</div>
  <h1>Drive history</h1>
  <p class="sub">Generated on the device. Fully self-contained &mdash; no network needed to open this file.</p>
  <div class="cards" id="cards"></div>
  <div class="split">
    <div class="panel">
      <h2>Drives</h2>
      <table id="t"><thead><tr>
        <th>Started</th><th>Distance</th><th>Elapsed</th><th>Moving</th><th>Avg</th><th>Max</th>
      </tr></thead><tbody></tbody></table>
    </div>
    <div class="panel">
      <h2>Route</h2>
      <svg id="map" viewBox="0 0 300 290" preserveAspectRatio="xMidYMid meet"></svg>
      <div class="note" id="mapnote">select a drive</div>
    </div>
  </div>
</div>
<script>
const TRIPS = $tripsJson;
const ROUTES = $routesJson;
const km = m => (m/1000).toFixed(1);
const hm = s => String(Math.floor(s/3600)).padStart(2,'0')+':'+String(Math.floor(s%3600/60)).padStart(2,'0');
const kmh = mps => (mps*3.6).toFixed(0);

document.getElementById('cards').innerHTML = [
  ['Drives', TRIPS.length],
  ['Total km', km(TRIPS.reduce((a,t)=>a+t.distanceM,0))],
  ['Total time', hm(TRIPS.reduce((a,t)=>a+t.durationS,0))],
  ['Moving', hm(TRIPS.reduce((a,t)=>a+t.movingS,0))],
  ['Top speed', kmh(Math.max(0,...TRIPS.map(t=>t.maxSpeedMps)))+' km/h']
].map(([l,v])=>'<div><b>'+v+'</b><span>'+l+'</span></div>').join('');

const tbody = document.querySelector('#t tbody');
tbody.innerHTML = TRIPS.map(t =>
  '<tr data-id="'+t.id+'"><td>'+new Date(t.startedAt).toLocaleString()+'</td><td>'+km(t.distanceM)+
  '</td><td>'+hm(t.durationS)+'</td><td>'+hm(t.movingS)+'</td><td>'+kmh(t.avgSpeedMps)+
  '</td><td>'+kmh(t.maxSpeedMps)+'</td></tr>').join('') ||
  '<tr><td colspan="6" style="color:#5C6877">No drives recorded yet.</td></tr>';

function drawRoute(id){
  const pts = ROUTES[id] || [];
  const svg = document.getElementById('map');
  const note = document.getElementById('mapnote');
  if (pts.length < 2){ svg.innerHTML=''; note.textContent='no points for this drive'; return; }
  const lats = pts.map(p=>p[0]), lons = pts.map(p=>p[1]);
  const minLat=Math.min(...lats), maxLat=Math.max(...lats);
  const minLon=Math.min(...lons), maxLon=Math.max(...lons);
  const span = Math.max(maxLat-minLat, maxLon-minLon) || 1e-9;
  const pad = 18, W=300-pad*2, H=290-pad*2;
  const d = pts.map((p,i)=>{
    const x = pad + ((p[1]-minLon)/span)*W;
    const y = pad + (1-(p[0]-minLat)/span)*H;
    return (i?'L':'M')+x.toFixed(1)+' '+y.toFixed(1);
  }).join(' ');
  svg.innerHTML =
    '<path d="'+d+'" fill="none" stroke="#3DE1FF" stroke-opacity=".25" stroke-width="7" stroke-linejoin="round"/>'+
    '<path d="'+d+'" fill="none" stroke="#3DE1FF" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>';
  note.textContent = pts.length + ' fixes';
}

tbody.addEventListener('click', e => {
  const tr = e.target.closest('tr[data-id]');
  if (!tr) return;
  [...tbody.querySelectorAll('tr')].forEach(r=>r.classList.remove('on'));
  tr.classList.add('on');
  drawRoute(tr.dataset.id);
});
if (TRIPS.length) { const first = tbody.querySelector('tr[data-id]'); if (first){ first.classList.add('on'); drawRoute(first.dataset.id); } }
</script>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    /**
     * Naming places is a keyboard job, so it lives here rather than on a car touchscreen.
     * A typed label always beats the reverse-geocoded suggestion shown beside it.
     */
    fun placesPage(
        places: List<PlaceEntity>,
        routes: List<RouteSummary>,
        message: String? = null
    ): String {
        val placeRows = buildString {
            places.sortedByDescending { it.visits }.forEach { p ->
                append("<tr><td><form method=\"post\" action=\"/places\" class=\"inline\">")
                append("<input type=\"hidden\" name=\"id\" value=\"").append(p.id).append("\">")
                append("<input name=\"label\" value=\"").append(esc(p.label ?: "")).append("\" ")
                append("placeholder=\"").append(esc(p.autoName ?: "name this place")).append("\">")
                append("<button type=\"submit\">Save</button></form></td>")
                append("<td class=\"dim\">").append(esc(p.autoName ?: "\u2014")).append("</td>")
                append("<td class=\"num\">").append(p.visits).append("</td>")
                append("<td class=\"dim mono\">")
                append("%.4f, %.4f".format(p.lat, p.lon)).append("</td></tr>")
            }
            if (places.isEmpty()) {
                append("<tr><td colspan=\"4\" class=\"dim\">")
                append("No places yet. They appear once a drive has finished.</td></tr>")
            }
        }

        val byId = places.associateBy { it.id }
        val routeRows = buildString {
            routes.forEach { r ->
                append("<tr><td>").append(esc(byId[r.startId]?.displayName ?: "Unknown"))
                append(" &rarr; ").append(esc(byId[r.endId]?.displayName ?: "Unknown")).append("</td>")
                append("<td class=\"num\">").append(r.drives).append("</td>")
                append("<td class=\"num\">").append("%.1f".format(r.avgDistanceM / 1000)).append("</td>")
                append("<td class=\"num\">").append((r.avgDurationS / 60).toInt()).append(" min</td>")
                append("<td class=\"num\">").append("%.1f".format(r.totalDistanceM / 1000)).append("</td></tr>")
            }
            if (routes.isEmpty()) {
                append("<tr><td colspan=\"5\" class=\"dim\">")
                append("No completed drives grouped yet. A trip is grouped once it ends, ")
                append("and it ends when the ignition does.</td></tr>")
            }
        }

        val banner = message?.let { "<div class=\"msg\">" + esc(it) + "</div>" } ?: ""

        return PLACES_SHELL
            .replace("{{BANNER}}", banner)
            .replace("{{ROUTES}}", routeRows)
            .replace("{{PLACES}}", placeRows)
    }

    private val PLACES_SHELL = """<!doctype html>
<title>Odograph Places</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#08090C;color:#EAEEF4;padding:44px 28px;
 font:14px/1.6 ui-sans-serif,system-ui,sans-serif}
.wrap{max-width:940px;margin:0 auto}
h1{font-size:24px;margin:0 0 6px}
h2{font-size:11px;letter-spacing:.16em;text-transform:uppercase;color:#5C6877;
 margin:34px 0 12px;font-weight:500}
p.sub{color:#8B96A5;margin:0 0 8px;max-width:70ch}
table{border-collapse:collapse;width:100%}
th,td{text-align:left;padding:9px 12px;border-bottom:1px solid #1E2530;font-size:13px}
th{color:#5C6877;font-weight:500;font-size:10px;letter-spacing:.1em;text-transform:uppercase}
td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}
.dim{color:#8B96A5}.mono{font-family:ui-monospace,monospace;font-size:12px}
form.inline{display:flex;gap:8px;margin:0}
input{flex:1;padding:7px 10px;background:#0D1015;color:#EAEEF4;border:1px solid #1E2530;
 border-radius:3px;font:13px ui-sans-serif,system-ui,sans-serif;min-width:130px}
input:focus{outline:2px solid #3DE1FF;outline-offset:-1px}
button{padding:7px 14px;background:#3DE1FF;color:#06080B;border:0;border-radius:3px;
 font:500 11px/1 ui-monospace,monospace;letter-spacing:.1em;text-transform:uppercase;cursor:pointer}
.msg{margin:16px 0;padding:11px 14px;border:1px solid #1E2530;border-radius:3px;
 background:#0D1015;color:#3DE1FF;font-size:13px}
a{color:#3DE1FF}
</style>
<div class="wrap">
  <h1>Places and routes</h1>
  <p class="sub">Name a place once and every route through it reads properly &mdash;
  Home, Office, the gym. Your label always beats the suggested name beside it.</p>
  {{BANNER}}

  <h2>Most visited routes</h2>
  <table><thead><tr>
    <th>Route</th><th class="num">Drives</th><th class="num">Avg km</th>
    <th class="num">Avg time</th><th class="num">Total km</th>
  </tr></thead><tbody>{{ROUTES}}</tbody></table>

  <h2>Places</h2>
  <table><thead><tr>
    <th>Your label</th><th>Suggested</th><th class="num">Visits</th><th>Coordinates</th>
  </tr></thead><tbody>{{PLACES}}</tbody></table>

  <p style="margin-top:32px"><a href="/">Dashboard</a> &middot;
     <a href="/config">Setup</a> &middot;
     <a href="/archive.html">Offline archive</a></p>
</div>"""

    fun configPage(webhookUrl: String, deviceId: String, message: String? = null): String =
        """<!doctype html>
<title>Odograph Setup</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#08090C;color:#EAEEF4;padding:44px 28px;
 font:14px/1.6 ui-sans-serif,system-ui,sans-serif}
.wrap{max-width:640px;margin:0 auto}
h1{font-size:24px;margin:0 0 6px}
p.sub{color:#8B96A5;margin:0 0 26px}
label{display:block;font-size:10px;letter-spacing:.14em;text-transform:uppercase;
 color:#5C6877;margin:20px 0 7px}
input{width:100%;padding:12px 14px;background:#0D1015;color:#EAEEF4;
 border:1px solid #1E2530;border-radius:3px;font:13px ui-monospace,monospace}
input:focus{outline:2px solid #3DE1FF;outline-offset:-1px}
button{margin-top:22px;padding:12px 24px;background:#3DE1FF;color:#06080B;border:0;
 border-radius:3px;font:500 12px/1 ui-monospace,monospace;letter-spacing:.12em;
 text-transform:uppercase;cursor:pointer}
.msg{margin-top:18px;padding:11px 14px;border:1px solid #1E2530;border-radius:3px;
 background:#0D1015;color:#3DE1FF;font-size:13px}
.hint{color:#5C6877;font-size:12px;margin-top:8px}
a{color:#3DE1FF}
</style>
<div class="wrap">
  <h1>Odograph setup</h1>
  <p class="sub">Paste from your Mac. Typing a secret URL on a car touchscreen is miserable, so this form exists instead.</p>
  ${message?.let { """<div class="msg">$it</div>""" } ?: ""}
  <form method="post" action="/config">
    <label for="u">Webhook URL &mdash; optional</label>
    <input id="u" name="webhook" value="${webhookUrl.replace("\"", "&quot;")}"
           placeholder="https://script.google.com/macros/s/..../exec">
    <div class="hint">A Google Apps Script web app URL. Leave blank to keep everything on the device &mdash; nothing here is required for recording.</div>
    <label for="d">Device name</label>
    <input id="d" name="device" value="${deviceId.replace("\"", "&quot;")}">
    <button type="submit">Save</button>
  </form>
  <p class="hint" style="margin-top:30px">
    <a href="/">Dashboard</a> &middot; <a href="/places">Places &amp; routes</a> &middot; <a href="/archive.html">Offline archive</a>
    &middot; <a href="/trips.csv">trips.csv</a> &middot; <a href="/sync">Sync now</a>
  </p>
</div>"""
}
