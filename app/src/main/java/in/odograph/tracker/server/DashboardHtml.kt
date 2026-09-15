package `in`.odograph.tracker.server

import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.DailyTelemetryEntity
import `in`.odograph.tracker.data.MonthTotal
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.RouteSummary
import `in`.odograph.tracker.data.TripEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DashboardHtml {

    /**
     * One file, no network. Downloaded once from the car it opens on a laptop forever after,
     * offline, with the ignition off — which is the whole point, since analysis happens at a
     * desk and the box lives in the car.
     *
     * Battery surfaced here: the used % and kW·h per drive, the cost a blended rate produced,
     * lifetime kWh, real-world mileage and range at a full charge, an x-y kWh chart, and the
     * charge sessions and capture windows the poller recorded.
     */
    fun render(
        trips: List<TripEntity>,
        pointsByTrip: Map<Long, List<PointEntity>>,
        webhookConfigured: Boolean = false,
        months: List<MonthTotal> = emptyList(),
        chargeEvents: List<ChargeEventEntity> = emptyList(),
        telemetryDays: List<DailyTelemetryEntity> = emptyList(),
        capacityKwh: Double = BatteryMath.DEFAULT_CAPACITY_KWH,
        homeRateInr: Double = 8.0,
        outsideRateInr: Double = 25.0,
        socPercent: Double? = null,
        liveRangeKm: Double? = null
    ): String {
        val monthRows = buildString {
            months.forEach { mth ->
                append("<tr><td>").append(mth.month).append("</td>")
                append("<td class=\"num\">").append(mth.drives).append("</td>")
                append("<td class=\"num\">").append("%.1f".format(mth.distanceM / 1000)).append("</td>")
                append("<td class=\"num\">")
                append("%d:%02d".format(mth.durationS / 3600, (mth.durationS % 3600) / 60))
                append("</td></tr>")
            }
            if (months.isEmpty()) {
                append("<tr><td colspan=\"4\" style=\"color:#5C6877\">No completed drives yet.</td></tr>")
            }
        }
        val tripsJson = trips.joinToString(",", "[", "]") { t ->
            """{"id":${t.id},"startedAt":${t.startedAt},"endedAt":${t.endedAt ?: 0},""" +
                """"distanceM":${t.distanceM},"durationS":${t.durationS},""" +
                """"movingS":${t.movingS},"maxSpeedMps":${t.maxSpeedMps},""" +
                """"elevGainM":${t.elevGainM},"elevLossM":${t.elevLossM},""" +
                """"energyKwh":${t.energyKwh ?: "null"},"costInr":${t.costInr ?: "null"},""" +
                """"socStart":${t.socStart ?: "null"},"socEnd":${t.socEnd ?: "null"}}"""
        }
        val routesJson = pointsByTrip.entries.joinToString(",", "{", "}") { (id, pts) ->
            "\"$id\":" + pts.joinToString(",", "[", "]") { "[${it.lat},${it.lon}]" }
        }
        val eventsJson = chargeEvents.joinToString(",", "[", "]") { e ->
            """{"s":${e.startTime},"e":${e.endTime ?: 0},"kwh":${e.energyKwh},""" +
                """"peak":${e.peakPowerKw ?: "null"},"kind":${e.kind ?: "null"},""" +
                """"cost":${e.costInr ?: "null"}}"""
        }
        val daysJson = telemetryDays.joinToString(",", "[", "]") { d ->
            """{"d":${d.day},"from":${d.firstPollAt},"to":${d.lastPollAt}}"""
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
.panel h2+.l{max-height:340px;overflow:auto}
table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
th,td{text-align:right;padding:9px 14px;border-bottom:1px solid var(--line);font-size:13px}
th{color:var(--label);font-weight:500;font-size:10px;letter-spacing:.1em;text-transform:uppercase}
td:first-child,th:first-child{text-align:left}
tbody tr{cursor:pointer}
tbody tr:hover{background:#141922}
tbody tr.on{background:#141922;box-shadow:inset 2px 0 0 var(--accent)}
svg{display:block;width:100%;height:290px;background:#050609}
svg.econ{height:170px}
svg text{fill:#5C6877;font:9px ui-monospace,monospace}
.note{color:var(--label);font-size:11px;font-family:ui-monospace,monospace;padding:10px 16px}
.chg{display:flex;justify-content:space-between;gap:10px;padding:10px 16px;
 border-bottom:1px solid var(--line);font-size:12px;align-items:baseline}
.chg:last-child{border-bottom:0}
.chg .meta{color:var(--dim);font-family:ui-monospace,monospace;font-size:11px}
.badge{font-size:9px;letter-spacing:.14em;font-weight:600;padding:2px 6px;border-radius:3px;
 vertical-align:1px}
.badge.slow{background:#18202B;color:#4FC3F7}
.badge.fast{background:#2A1D3F;color:#B388FF}
.badge.open{background:#241C10;color:#FFCA6B}
.pill{color:var(--dim);font-family:ui-monospace,monospace;font-size:10px}
.days{display:flex;flex-wrap:wrap;gap:4px;padding:12px 16px}
.day{width:34px;height:34px;border-radius:3px;background:#10141C;color:var(--dim);
 font-size:9px;display:flex;flex-direction:column;align-items:center;justify-content:center;
 gap:1px;font-family:ui-monospace,monospace}
.day b{color:var(--fg);font-size:11px}
.day.miss{border:1px dashed #2A3240}
.legend{color:var(--label);font-size:10px;font-family:ui-monospace,monospace;padding:0 16px 10px}
</style>
<div class="wrap">
  <div class="eyebrow"><b>Odograph</b> &middot; archive &middot; $syncBadge</div>
  <h1>Drive history</h1>
  <p class="sub">Generated on the device. Fully self-contained &mdash; no network needed to open this file.</p>
  <div class="cards" id="cards"></div>
  <div class="split">
    <div>
      <div class="panel">
        <h2>Drives</h2>
        <table id="t"><thead><tr>
          <th>Started</th><th>Distance</th><th>Gain/Loss</th><th>Used</th><th>kWh</th><th>&#8377;</th><th>km/kWh</th><th>Elapsed</th><th>Moving</th><th>Avg</th><th>Max</th>
        </tr></thead><tbody></tbody></table>
      </div>
      <div class="panel" style="margin-top:22px">
        <h2>Energy per drive &mdash; kWh</h2>
        <svg id="econ" viewBox="0 0 300 170" preserveAspectRatio="none" class="econ"></svg>
        <div class="note" id="econnote">no instruments with usable energy yet</div>
      </div>
    </div>
    <div>
      <div class="panel">
        <h2>By month</h2>
        <table><thead><tr>
          <th>Month</th><th class="num">Drives</th><th class="num">km</th><th class="num">Time</th>
        </tr></thead><tbody>$monthRows</tbody></table>
      </div>
      <div class="panel" style="margin-top:22px">
        <h2>Charging</h2>
        <div class="note" id="cv"></div>
        <div id="chg"></div>
      </div>
      <div class="panel" style="margin-top:22px">
        <h2>Capture coverage</h2>
        <div class="note" id="covnote"></div>
        <div class="days" id="days"></div>
        <div class="legend">solid = polled that day &middot; dashed = gap</div>
      </div>
      <div class="panel" style="margin-top:22px">
        <h2>Route</h2>
        <svg id="map" viewBox="0 0 300 290" preserveAspectRatio="xMidYMid meet"></svg>
        <div class="note" id="mapnote">select a drive</div>
      </div>
    </div>
  </div>
</div>
<script>
var TRIPS = $tripsJson;
var ROUTES = $routesJson;
var EVENTS = $eventsJson;
var DAYS = $daysJson;
var CAP = $capacityKwh;
var HOME = $homeRateInr;
var EXT = $outsideRateInr;
var SOC = ${socPercent ?: "null"};
var LIVE_RANGE = ${liveRangeKm ?: "null"};
var km = function(m){return (m/1000).toFixed(1);};
var hm = function(s){return String(Math.floor(s/3600)).padStart(2,'0')+':'+String(Math.floor(s%3600/60)).padStart(2,'0');};
var kmh = function(mps){return (mps*3.6).toFixed(0);};
var fmtD = function(ms){var d=new Date(ms);return d.toLocaleString();};
var pos = function(t){return t.energyKwh>0?t.energyKwh:0;};

var inst = TRIPS.filter(function(t){return t.energyKwh>0 && t.distanceM>0;});
var usedAll = TRIPS.reduce(function(a,t){return a+pos(t);},0);
var costAll = TRIPS.reduce(function(a,t){return a+(t.costInr||0);},0);
var effs = inst.map(function(t){return t.energyKwh/(t.distanceM/1000)*100;});
var avgEff = effs.length? effs.reduce(function(a,b){return a+b;},0)/effs.length : null;
var range100 = null;
if (inst.length>=3 && avgEff) range100 = CAP/avgEff*100;
else if (SOC>0 && LIVE_RANGE) range100 = LIVE_RANGE/SOC*100;
document.getElementById('cards').innerHTML = [
  ['Drives', TRIPS.length],
  ['Total km', km(TRIPS.reduce(function(a,t){return a+t.distanceM;},0))],
  ['Used kWh', usedAll.toFixed(2)],
  ['Energy cost', '&#8377;'+costAll.toFixed(2)],
  ['Mileage', avgEff? (100/avgEff).toFixed(2)+' km/kWh' : '&mdash;'],
  ['Range@100%', range100? km(range100)+' km' : '&mdash;'],
  ['Total time', hm(TRIPS.reduce(function(a,t){return a+t.durationS;},0))],
  ['Top speed', kmh(Math.max(0,TRIPS.reduce(function(a,t){return Math.max(a,t.maxSpeedMps);},0)))+' km/h']
].map(function(p){return '<div><b>'+p[1]+'</b><span>'+p[0]+'</span></div>';}).join('');

function cell(v){return v==null?'<td class="dim">&#8212;</td>':'<td>'+v+'</td>';}
var tbody = document.querySelector('#t tbody');
tbody.innerHTML = TRIPS.map(function(t){
  var used = (t.socStart!=null && t.socEnd!=null)?(t.socStart-t.socEnd):null;
  var kwh = t.energyKwh!=null? t.energyKwh.toFixed(2) : null;
  var cost = t.costInr!=null? '&#8377;'+t.costInr.toFixed(2) : null;
  var eff = (t.energyKwh>0 && t.distanceM>0)? (t.distanceM/1000/t.energyKwh).toFixed(2) : null;
  var climb = (t.elevGainM>0 || t.elevLossM>0)?
    '\u2191'+Math.round(t.elevGainM)+'\u00b7\u2193'+Math.round(t.elevLossM)+' m' : null;
  return '<tr data-id="'+t.id+'"><td>'+fmtD(t.startedAt)+'</td><td>'+km(t.distanceM)+
    '</td>'+cell(climb)+(used?cell(used.toFixed(1)+'%'):cell(null))+cell(kwh)+cell(cost)+cell(eff)+
    '</td><td>'+hm(t.durationS)+'</td><td>'+hm(t.movingS)+'</td><td>'+kmh(t.avgSpeedMps)+
    '</td><td>'+kmh(t.maxSpeedMps)+'</td></tr>';
}).join('') || '<tr><td colspan="11" style="color:#5C6877">No drives recorded yet.</td></tr>';

// x-y chart: energy (kWh) per drive, newest on the right.
(function(){
  var data = inst.slice().reverse();
  var svg = document.getElementById('econ');
  var note = document.getElementById('econnote');
  if (data.length < 1){ return; }
  var W=300, H=170, pad=24;
  var maxY = Math.max.apply(null, data.map(function(t){return t.energyKwh;}).concat([1]));
  var pts = data.map(function(t,i){
    var x = pad + (data.length===1?0.5: i/(data.length-1)) * (W-pad*2);
    var y = pad + (1 - Math.max(0,t.energyKwh)/maxY) * (H-pad*2);
    return [x.toFixed(1), y.toFixed(1)];
  });
  var poly = pts.map(function(p,i){return (i? 'L':'M')+p[0]+' '+p[1];}).join(' ');
  var dots = pts.map(function(p){return '<circle cx="'+p[0]+'" cy="'+p[1]+'" r="2" fill="#3DE1FF"/>';}).join('');
  var labels = '';
  labels += '<text x="'+pad+'" y="'+(H-6)+'">'+fmtD(data[data.length-1].startedAt).slice(0,10)+'</text>';
  labels += '<text x="'+(W-60)+'" y="14">max '+maxY.toFixed(2)+' kWh</text>';
  svg.innerHTML = '<polyline points="'+poly+'" fill="none" stroke="#3DE1FF" stroke-width="2" stroke-linejoin="round">'
    +'</polyline>'+dots+labels;
  var avgKwh = data.reduce(function(a,t){return a+pos(t);},0)/data.length;
  note.textContent = data.length+' drives \u00b7 avg '+avgKwh.toFixed(2)+' kWh \u00b7 '
    + (avgEff?(100/avgEff).toFixed(2)+' km/kWh':'')+' \u00b7 rate \u00a5'+HOME+' home / \u00a5'+EXT+' fast';
})();

// Charging sessions, newest first; an open session is waiting for its closing frame.
(function(){
  var cv = document.getElementById('cv');
  var wrap = document.getElementById('chg');
  if (EVENTS.length===0){ cv.textContent='no charge sessions recorded yet'; return; }
  var nFast=0, nSlow=0, kwhFast=0, kwhSlow=0;
  EVENTS.forEach(function(e){
    if (e.kind===1){nFast++;kwhFast+=e.kwh;} else if (e.kind===0){nSlow++;kwhSlow+=e.kwh;}
  });
  cv.textContent = nSlow+' slow ('+kwhSlow.toFixed(2)+' kWh)  '+nFast+' fast ('+kwhFast.toFixed(2)+' kWh)';
  var rows = EVENTS.slice().sort(function(a,b){return b.s-a.s;}).map(function(e){
    var badge = e.kind===1? '<span class="badge fast">FAST</span>'
      : e.kind===0? '<span class="badge slow">SLOW</span>'
      : '<span class="badge open">OPEN</span>';
    var dur = e.e? Math.round((e.e-e.s)/60000) : 0;
    var cost = e.cost!=null? '&#8377;'+e.cost.toFixed(2) : '\u2014';
    return '<div class="chg"><div>'+badge+' '+fmtD(e.s)+
      '<div class="meta">'+e.kwh.toFixed(2)+' kWh \u00b7 '+(e.peak? e.peak.toFixed(1):'\u2014')+' kW peak \u00b7 '+dur+' min</div></div>'+
      '<div class="meta">'+cost+'</div></div>';
  }).join('');
  wrap.innerHTML = rows;
})();

// Capture coverage: which days the poller actually ran, honest about the gaps.
(function(){
  var note = document.getElementById('covnote');
  var wrap = document.getElementById('days');
  if (DAYS.length===0){ note.textContent='no polling days yet'; return; }
  var txt = DAYS.length+' day'+(DAYS.length>1?'s':'')+' polled, first '+fmtD(DAYS[0].from).slice(0,10);
  var fmtDay = function(d){var s=String(d);return s.slice(6,8)+'/'+s.slice(4,6);};
  var cells = DAYS.map(function(d){return '<div class="day" title="'+fmtD(d.from)+' &rarr; '+fmtD(d.to)+'"><b>'+fmtDay(d.d)+'</b><span>'+Math.round((d.to-d.from)/3600000)+'h</span></div>';});
  var gaps = 0;
  for (var i=1;i<DAYS.length;i++){
    var dayLen = 24*3600000;
    if (DAYS[i].d - DAYS[i-1].d > 1) {
      cells.push('<div class="day miss" title="no polling between these days">'+fmtDay(DAYS[i-1].d)+'&#8594;'+fmtDay(DAYS[i].d)+'</div>');
      gaps++;
    }
  }
  note.textContent = txt + (gaps? ' \u00b7 '+gaps+' gap'+(gaps>1?'s':'') : '');
  wrap.innerHTML = cells.join('');
})();

function drawRoute(id){
  var pts = ROUTES[id] || [];
  var svg = document.getElementById('map');
  var note = document.getElementById('mapnote');
  if (pts.length < 2){ svg.innerHTML=''; note.textContent='no points for this drive'; return; }
  var lats = pts.map(function(p){return p[0];}), lons = pts.map(function(p){return p[1];});
  var minLat=Math.min.apply(null,lats), maxLat=Math.max.apply(null,lats);
  var minLon=Math.min.apply(null,lons), maxLon=Math.max.apply(null,lons);
  var span = Math.max(maxLat-minLat, maxLon-minLon) || 1e-9;
  var pad = 18, W=300-pad*2, H=290-pad*2;
  var d = pts.map(function(p,i){
    var x = pad + ((p[1]-minLon)/span)*W;
    var y = pad + (1-(p[0]-minLat)/span)*H;
    return (i?'L':'M')+x.toFixed(1)+' '+y.toFixed(1);
  }).join(' ');
  svg.innerHTML =
    '<path d="'+d+'" fill="none" stroke="#3DE1FF" stroke-opacity=".25" stroke-width="7" stroke-linejoin="round"/>'+
    '<path d="'+d+'" fill="none" stroke="#3DE1FF" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>';
  note.textContent = pts.length + ' fixes';
}

tbody.addEventListener('click', function(e){
  var tr = e.target.closest('tr[data-id]');
  if (!tr) return;
  [].forEach.call(tbody.querySelectorAll('tr'), function(r){r.classList.remove('on');});
  tr.classList.add('on');
  drawRoute(tr.dataset.id);
});
if (TRIPS.length){ var first = tbody.querySelector('tr[data-id]'); if (first){ first.classList.add('on'); drawRoute(first.dataset.id); } }
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

    fun configPage(
        webhookUrl: String,
        deviceId: String,
        telematicsPhone: String = "",
        telematicsPassword: String = "",
        telematicsVin: String = "",
        message: String? = null,
        error: Boolean = false,
        batteryCapacityKwh: String = "49.2",
        homeRateInr: String = "8.0",
        outsideRateInr: String = "25.0",
        docsSyncHours: Int = 1,
        lastDocsSyncAt: Long = 0
    ): String {
        val messageHtml = message?.let {
            "<div class=\"msg" + (if (error) " err" else "") + "\">" + esc(it) + "</div>"
        } ?: ""
        val cadenceHtml = listOf(
            1 to "Every hour",
            12 to "Every 12 hours",
            24 to "Once a day"
        ).joinToString("") { (value, label) ->
            val checked = if (value == docsSyncHours) " checked" else ""
            """<label style="display:inline-flex;gap:7px;align-items:center;margin:0 18px 0 0;
            font-size:12px;letter-spacing:.04em;text-transform:none">
            <input type="radio" name="hours" value="$value"$checked>$label</label>"""
        }
        val lastSync = if (lastDocsSyncAt > 0) {
            SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(lastDocsSyncAt))
        } else {
            "never yet"
        }
        return """<!doctype html>
<title>Odograph Setup</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#08090C;color:#EAEEF4;padding:44px 28px;
 font:14px/1.6 ui-sans-serif,system-ui,sans-serif}
.wrap{max-width:660px;margin:0 auto}
h1{font-size:24px;margin:0 0 6px}
p.sub{color:#8B96A5;margin:0 0 26px}
label{display:block;font-size:10px;letter-spacing:.14em;text-transform:uppercase;
 color:#5C6877;margin:20px 0 7px}
input{width:100%;padding:12px 14px;background:#0D1015;color:#EAEEF4;
 border:1px solid #1E2530;border-radius:3px;font:13px ui-monospace,monospace}
input:focus{outline:2px solid #3DE1FF;outline-offset:-1px}
.row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px}
.actions{display:flex;gap:10px;margin-top:22px}
button{padding:12px 22px;background:#3DE1FF;color:#06080B;border:0;
 border-radius:3px;font:500 12px/1 ui-monospace,monospace;letter-spacing:.12em;
 text-transform:uppercase;cursor:pointer}
button.ghost{background:#161C26;color:#8B96A5}
.msg{margin-top:18px;padding:11px 14px;border:1px solid #1E2530;border-radius:3px;
 background:#0D1015;color:#3DE1FF;font-size:13px}
.msg.err{border-color:rgba(255,90,120,.35);color:#FF7A8A}
.hint{color:#5C6877;font-size:12px;margin-top:8px}
a{color:#3DE1FF}
</style>
<div class="wrap">
  <h1>Odograph setup</h1>
  <p class="sub">Paste from your Mac. Typing a secret URL on a car touchscreen is miserable, so this form exists instead.</p>
  $messageHtml
  <form method="post" action="/config">
    <label for="u">Google Docs link &mdash; the Odograph workbook</label>
    <input id="u" name="webhook" value="${webhookUrl.replace("\"", "&quot;")}"
           placeholder="https://script.google.com/macros/s/..../exec">
    <div class="hint">This box uploads its data here on a schedule — only what's new since the last
      upload, so the payload stays small no matter how old the archive gets — and imports
      control values back. Paste either the spreadsheet link or its deployed Apps Script
      <code>/exec</code> URL. The sheet link is read-only until you deploy the bundled script
      (in the repo: <code>tools/odograph_sheets_apps_script.js</code>) &mdash; open the sheet,
      Extensions &rarr; Apps Script, paste it, Deploy &rarr; Web app &rarr; Anyone.</div>
    <div style="margin-top:18px">
      $cadenceHtml
    </div>
    <div class="hint">How often the box uploads anything new to the sheet (default: every hour).
      Last upload: $lastSync.
      <a href="/sync">Upload now</a> &middot; <a href="/import">Import now</a>.</div>
    <label for="d" style="margin-top:26px">Device name</label>
    <input id="d" name="device" value="${deviceId.replace("\"", "&quot;")}">
    <label for="t">iSMART phone number &mdash; optional</label>
    <input id="t" name="tl_phone" value="${telematicsPhone.replace("\"", "&quot;")}" placeholder="10-digit mobile on the iSmart account">
    <div class="hint">Turns on the battery &amp; charge poller (reads SOC to the driving screen). Leave phone blank to keep recording GPS-only.</div>
    <label for="p">iSMART password</label>
    <input id="p" name="tl_password" type="password" placeholder="${if (telematicsPassword.isNotEmpty()) "password on file" else ""}" autocomplete="off">
    <div class="hint">Stored on this box only. Blank keeps the existing password, so you can change the phone or VIN without retyping it.</div>
    <label for="v">VIN &mdash; optional</label>
    <input id="v" name="tl_vin" value="${telematicsVin.replace("\"", "&quot;")}" placeholder="Defaults to the first vehicle on the account">
    <label for="v">Electricity &mdash; capacity and rates</label>
    <div class="row">
      <div><input id="c" name="capacity" value="$batteryCapacityKwh" inputmode="decimal" placeholder="kWh"><div class="hint">Battery, kWh</div></div>
      <div><input name="home_rate" value="$homeRateInr" inputmode="decimal" placeholder="&#8377;/kWh"><div class="hint">Home rate, slow</div></div>
      <div><input name="out_rate" value="$outsideRateInr" inputmode="decimal" placeholder="&#8377;/kWh"><div class="hint">Fast charge rate</div></div>
    </div>
    <div class="hint">Capacity turns SOC into kW&middot;h. Under 10 kW is a slow/home charge at the home rate; 10 kW and up is fast at the outside rate. Blank keeps the current value.</div>
    <div class="actions">
      <button type="submit" name="op" value="test">Try my connection</button>
      <button type="submit" class="ghost" name="op" value="save">Save</button>
    </div>
  </form>
  <p class="hint" style="margin-top:12px">
    Try my connection logs the box into your iSMART account and shows what it finds &mdash; your car's name, VIN, and the live battery.
    If it works, the credentials are kept and the poller switches on. Save keeps them without testing.
  </p>
  <p class="hint" style="margin-top:30px">
    <a href="/">Dashboard</a> &middot; <a href="/places">Places &amp; routes</a> &middot; <a href="/archive.html">Offline archive</a>
    &middot; <a href="/planner">Trip planner</a> &middot; <a href="/trips.csv">trips.csv</a>
    &middot; <a href="/sync">Export now</a> &middot; <a href="/import">Import now</a>
  </p>
</div>"""
    }

    /**
     * The raw-data hallway: which CSVs a laptop on the LAN can pull, and the exact URLs. Served
     * only while the SETUP → LAN DATA toggle is on.
     */
    fun exportPage(baseUrl: String?, counts: List<Pair<String, String>>): String {
        val host = baseUrl ?: "http://<this device>:${DashboardServer.PORT}"
        val rows = buildString {
            counts.forEach { (name, what) ->
                append("""<a class="row" href="$name"><div>""")
                append("<code>").append(name).append("</code>")
                append("<span>").append(esc(what)).append("</span></div>")
                append("<b>download</b></a>")
            }
        }
        return """<!doctype html>
<title>Odograph LAN Export</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#08090C;color:#EAEEF4;padding:44px 28px;
 font:14px/1.6 ui-sans-serif,system-ui,sans-serif}
.wrap{max-width:660px;margin:0 auto}
h1{font-size:24px;margin:0 0 6px}
p.sub{color:#8B96A5;margin:0 0 26px}
a.row{display:flex;justify-content:space-between;align-items:center;gap:16px;
 padding:16px 18px;background:#0D1015;border:1px solid #1E2530;border-radius:4px;
 text-decoration:none;color:#EAEEF4;margin-bottom:10px}
a.row:hover{border-color:#3DE1FF}
code{font:13px ui-monospace,monospace;color:#3DE1FF}
span{color:#8B96A5;font-size:12.5px}
b{font-size:11px;letter-spacing:.14em;text-transform:uppercase;color:#5C6877}
</style>
<div class="wrap">
<h1>LAN export</h1>
<p class="sub">Pull everything the box records from <code>$host</code>.
 Each row is one file a laptop on the same Wi-Fi can download straight into a notebook.</p>
$rows
</div>"""
    }

    /**
     * "How far can I go?" — a keyboard calculator over the same real numbers the box has been
     * learning: the city and outstation efficiencies it has actually achieved. Baked as a live
     * snapshot at request time (the box is offline), which is exactly honest for a planner.
     */
    fun plannerPage(
        socPercent: Double?,
        capacityKwh: Double,
        homeRateInr: Double,
        outsideRateInr: Double,
        cityEfficiencyKwhPer100Km: Double?,
        longEfficiencyKwhPer100Km: Double?,
        totalKwh: Double,
        lastPollAt: Long?
    ): String {
        val socTxt = socPercent?.let { "%.0f".format(it) } ?: "\u2014"
        val cityRange = cityEfficiencyKwhPer100Km?.let {
            BatteryMath.rangeAtSocKwh(capacityKwh, socPercent ?: 0.0, it)
        }
        val longRange = longEfficiencyKwhPer100Km?.let {
            BatteryMath.rangeAtSocKwh(capacityKwh, socPercent ?: 0.0, it)
        }
        val nowKwh = socPercent?.let { capacityKwh * it.coerceIn(0.0, 100.0) / 100.0 }
        val cityKmPerKwh = cityEfficiencyKwhPer100Km?.let { 100.0 / it }
        val longKmPerKwh = longEfficiencyKwhPer100Km?.let { 100.0 / it }
        val poll = lastPollAt?.let {
            java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
        } ?: "never"

        return """<!doctype html>
<title>Odograph Planner</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#08090C;color:#EAEEF4;padding:44px 28px;
 font:14px/1.6 ui-sans-serif,system-ui,sans-serif}
.wrap{max-width:720px;margin:0 auto}
h1{font-size:24px;margin:0 0 6px}
p.sub{color:#8B96A5;margin:0 0 26px}
.kpis{display:flex;gap:1px;background:#1E2530;border:1px solid #1E2530;border-radius:4px;
 margin-bottom:24px;overflow:hidden;flex-wrap:wrap}
.kpis div{background:#0D1015;padding:14px 20px;flex:1;min-width:130px}
.kpis b{display:block;font-size:22px;font-variant-numeric:tabular-nums}
.kpis span{color:#5C6877;font-size:9px;letter-spacing:.14em;text-transform:uppercase}
label{display:block;font-size:10px;letter-spacing:.14em;text-transform:uppercase;
 color:#5C6877;margin:18px 0 7px}
input,select{width:100%;padding:12px 14px;background:#0D1015;color:#EAEEF4;
 border:1px solid #1E2530;border-radius:3px;font:14px ui-monospace,monospace}
.res{margin-top:20px;padding:16px 18px;border:1px solid #1E2530;border-radius:4px;
 background:#0D1015;font-size:15px}
.res b{color:#3DE1FF}
.res .faint{color:#8B96A5;font-size:12px;margin-top:6px}
.choice{border:1px solid #1E2530;border-radius:4px;padding:14px 16px;margin-bottom:10px;
 background:#0D1015}
.choice input{width:auto;margin-right:10px}
.choice label{display:inline;margin:0;text-transform:none;letter-spacing:0;color:inherit;
 font-size:14px}
.choice .eg{color:#5C6877;font-size:11px;margin-top:6px}
a{color:#3DE1FF}
</style>
<div class="wrap">
  <h1>Trip planner</h1>
  <p class="sub">Real numbers from everything this box has recorded. Sensor snapshot taken $poll &mdash; tap refresh on the car screen for a newer one.</p>
  <div class="kpis">
    <div><b>$socTxt%</b><span>Charge now</span></div>
    <div><b>${nowKwh?.let { "%.2f".format(it) } ?: "\u2014"}</b><span>kWh on board</span></div>
    <div><b>${cityRange?.let { "%.0f".format(it) } ?: "\u2014"} km</b><span>City range</span></div>
    <div><b>${longRange?.let { "%.0f".format(it) } ?: "\u2014"} km</b><span>Outstation range</span></div>
    <div><b>${cityKmPerKwh?.let { "%.2f".format(it) } ?: "\u2014"}</b><span>City km/kWh</span></div>
    <div><b>${longKmPerKwh?.let { "%.2f".format(it) } ?: "\u2014"}</b><span>Trip km/kWh</span></div>
  </div>
  <label for="km">How far are you going? (km)</label>
  <input id="km" type="number" min="1" max="600" value="80" inputmode="numeric">
  <label for="kind">Mostly</label>
  <select id="kind">
    <option value="city">City &mdash; under 50 km</option>
    <option value="long" selected>Outstation &mdash; over 50 km</option>
  </select>
  <div id="res" class="res"></div>
  <p class="hint" style="margin-top:22px">
    <a href="/">Dashboard</a> &middot; <a href="/config">Setup</a> &middot; <a href="/archive.html">Offline archive</a>
  </p>
</div>
<script>
var CAP = $capacityKwh;
var SOC = ${socPercent ?: "null"};
var kmPerKwh = {city: ${
            cityKmPerKwh?.let { "%.2f".format(it) } ?: "null"
    }, long: ${
            longKmPerKwh?.let { "%.2f".format(it) } ?: "null"
    }};
var outRate = $outsideRateInr, homeRate = $homeRateInr;
function upd(){
  var km = parseFloat(document.getElementById('km').value)||0;
  var kind = document.getElementById('kind').value;
  var eff = kmPerKwh[kind];
  if (!eff){ document.getElementById('res').innerHTML='<div class="faint">Not enough real driving yet for '+kind+' consumption under this distance bucket. Drive a few more instrumented trips.</div>'; return; }
  var need = km/eff;
  var onBoard = CAP*(SOC||0)/100;
  var shortfall = Math.max(0, need - onBoard);
  var can = onBoard*eff;
  var cheap = shortfall*homeRate, pricey = shortfall*outRate;
  var cost = shortfall>0? (Math.min(cheap,pricey)) : 0;
  var who = shortfall>0? (cheap<=pricey? 'home (slow)':'fast charger') : '&mdash;';
  var res = '<b>'+need.toFixed(2)+' kWh</b> to cover '+km+' km &mdash; you carry '+onBoard.toFixed(2)+' kWh, so range is '+can.toFixed(0)+' km.<br>';
  if (shortfall>0){
    res += 'Needs '+shortfall.toFixed(2)+' kWh more, roughly <b>\u00a5'+cost.toFixed(2)+'</b> on a '+who+'.';
  } else {
    res += 'Fits on the current charge &mdash; no charging on the way.';
  }
  document.getElementById('res').innerHTML = res;
}
document.getElementById('km').addEventListener('input', upd);
document.getElementById('kind').addEventListener('change', upd);
upd();
</script>"""
    }

    /**
     * The raw payloads the MG servers replied with, next to the decoded model built from them —
     * so the decoder can be checked against exactly what the car sent, byte for byte.
     */
    fun framesPage(entries: List<RawFrames.Entry>): String {
        val blocks = entries.joinToString("\n") { e ->
            "<h3>" + esc(e.label) + "</h3>\n<pre>" + esc(e.content) + "</pre>"
        }.ifEmpty {
            "<p class=\"hint\">Nothing captured yet &mdash; the poller records every frame on its next run.</p>"
        }
        return """<!doctype html>
<title>Odograph frames</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#08090C;color:#EAEEF4;padding:44px 28px;
 font:14px/1.6 ui-sans-serif,system-ui,sans-serif}
.wrap{max-width:980px;margin:0 auto}
h1{font-size:24px;margin:0 0 6px}
p.sub{color:#8B96A5;margin:0 0 20px}
.warn{border:1px solid rgba(255,90,120,.35);border-radius:4px;padding:12px 14px;
 background:#0D1015;color:#FF7A8A;font-size:13px;margin-bottom:26px}
h3{font-size:10px;letter-spacing:.14em;text-transform:uppercase;color:#5C6877;
 margin:22px 0 6px}
pre{margin:0;padding:14px 16px;background:#0D1015;border:1px solid #1E2530;
 border-radius:4px;overflow-x:auto;color:#9FE8B8;
 font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre-wrap;
 word-break:break-all}
.hint{color:#8B96A5;font-size:12px;margin-top:14px}
a{color:#3DE1FF}
</style>
<div class="wrap">
  <h1>Raw MG frames</h1>
  <p class="sub">Newest at the bottom. Each raw response sits above the model the decoder built from it.</p>
  <div class="warn">Hex frames echo the account&rsquo;s session UID and token &mdash; treat this page as a password. Do not copy it anywhere public.</div>
  $blocks
  <p class="hint"><a href="/">Dashboard</a> &middot; <a href="/config">Setup</a></p>
</div>"""
    }
}