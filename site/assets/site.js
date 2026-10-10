// Atelier RA — scripts communs du portail
const DEPOT = "Ghepito/atelier-ra";
const API = "https://api.github.com/repos/" + DEPOT;

const LOGO = `<svg viewBox="0 0 108 108" aria-hidden="true"><rect width="108" height="108" rx="22" fill="#0F1418"/>
<path fill="#FFB020" d="M54,24a12,12 0,1 1,-0.01,0zM51,40h6v22h-6zM54,74l-8,-12h16z"/>
<path stroke="#3CCF8E" stroke-width="4" fill="none" d="M28,70l26,12l26,-12M28,70l26,-10l26,10"/></svg>`;

function entete(actif) {
  const liens = [
    ["index.html", "Accueil"],
    ["tutos.html", "Tutos"],
    ["espace.html", "Espace connecté"],
  ];
  document.querySelector("header.top").innerHTML = `<div class="in">
    <a class="logo" href="index.html">${LOGO}<span>ATELIER RA</span></a>
    <nav class="main">${liens.map(([h, t]) => `<a href="${h}" class="${h === actif ? "on" : ""}">${t}</a>`).join("")}</nav>
  </div>`;
  const f = document.querySelector("footer");
  if (f) f.innerHTML = `<div class="in"><span>Atelier RA — prototype R&D de maintenance assistée par réalité augmentée</span>
    <span><a href="https://github.com/${DEPOT}">Code source</a></span></div>`;
}

async function derniereVersion() {
  const r = await fetch(API + "/releases/tags/derniere-version", { headers: { Accept: "application/vnd.github+json" } });
  if (!r.ok) throw new Error("GitHub " + r.status);
  const j = await r.json();
  const apk = j.assets.find(a => a.name.endsWith(".apk"));
  const pdf = j.assets.find(a => a.name.endsWith(".pdf"));
  const num = (j.name.match(/version (\d+)/) || [])[1];
  return {
    version: num, date: new Date(j.published_at || j.created_at),
    apk: apk && apk.browser_download_url, apkTaille: apk && apk.size,
    pdf: pdf && pdf.browser_download_url,
  };
}

async function historique(n = 8) {
  const r = await fetch(API + "/commits?per_page=" + n);
  if (!r.ok) return [];
  return (await r.json()).map(c => ({
    date: new Date(c.commit.author.date),
    msg: c.commit.message.split("\n")[0],
  }));
}

const fmtDate = d => d.toLocaleDateString("fr-FR", { day: "2-digit", month: "short", year: "numeric" });
const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
