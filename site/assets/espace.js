// Atelier RA — espace connecté (Firebase Auth + Firestore)
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword,
  signOut, sendPasswordResetEmail,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  getFirestore, doc, getDoc, setDoc, updateDoc, deleteDoc, addDoc, collection, query, where,
  getDocs, serverTimestamp, runTransaction,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";

import { chargerModele, creerJumeau } from "./jumeau.js";

const firebaseConfig = {
  apiKey: "AIzaSyDsBpRo_4ICgHMMhReDO5ycB73BXxBjAKI",
  authDomain: "atelier-ra.firebaseapp.com",
  projectId: "atelier-ra",
  storageBucket: "atelier-ra.firebasestorage.app",
  messagingSenderId: "300086069191",
  appId: "1:300086069191:web:84a5a101f80caa00dcfbe2",
};
const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const E = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const racine = $("#app");

let user = null, profil = null, vue = null;
let inscription = false;
let jumeau = null;          // vue 3D active (une seule à la fois)
const fermerJumeau = () => { if (jumeau) { jumeau.dispose(); jumeau = null; } };    // évite la création d'un profil par défaut pendant l'inscription
let machines = [];          // [{id, data}]
const ROLES = { apprenant: "Apprenant", formateur: "Formateur", admin: "Administrateur" };
const STATUTS = { a_valider: "À valider", integree: "Intégrée", a_reprendre: "À reprendre" };
const TYPES_PROP = {
  releve: "Relevé de plaque / caractéristiques",
  point: "Nouveau point d'entretien",
  chaine: "Complément de chaîne",
  remarque: "Remarque / anomalie",
};
const TYPES_POINT = ["actionneur", "capteur", "préactionneur", "commande", "sécurité", "énergie", "structure"];

function toast(msg) {
  const t = document.createElement("div"); t.className = "toast"; t.textContent = msg;
  document.body.appendChild(t); setTimeout(() => t.remove(), 2600);
}
const fmt = ts => {
  const d = ts?.toDate ? ts.toDate() : (ts?.seconds != null ? new Date(ts.seconds * 1000) : (ts ? new Date(ts) : null));
  if (d && isNaN(d)) return "";
  return d ? d.toLocaleString("fr-FR", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" }) : "";
};
const estFormateur = () => profil && (profil.role === "formateur" || profil.role === "admin");
const estAdmin = () => profil && profil.role === "admin";
function erreurFr(e) {
  const c = e?.code || "";
  if (c.includes("invalid-credential") || c.includes("wrong-password") || c.includes("user-not-found")) return "E-mail ou mot de passe incorrect.";
  if (c.includes("email-already-in-use")) return "Un compte existe déjà avec cet e-mail.";
  if (c.includes("weak-password")) return "Mot de passe trop court (6 caractères minimum).";
  if (c.includes("invalid-email")) return "Adresse e-mail invalide.";
  if (c.includes("permission-denied")) return "Action non autorisée pour votre rôle (ou règles de sécurité non installées).";
  if (c.includes("too-many-requests")) return "Trop de tentatives, réessayez dans quelques minutes.";
  return e?.message || String(e);
}

// ======================================================================= Connexion
function vueConnexion(mode = "login") {
  racine.innerHTML = `
  <div class="auth card">
    <div class="seg"><button data-m="login" class="${mode === "login" ? "on" : ""}">Connexion</button>
      <button data-m="signup" class="${mode === "signup" ? "on" : ""}">Créer un compte</button></div>
    <form id="f">
      ${mode === "signup" ? `<label class="f">Nom et prénom</label><input name="nom" required autocomplete="name">` : ""}
      <label class="f">E-mail</label><input name="email" type="email" required autocomplete="email">
      <label class="f">Mot de passe</label><input name="mdp" type="password" required minlength="6" autocomplete="${mode === "signup" ? "new-password" : "current-password"}">
      ${mode === "signup" ? `<label class="f">Code classe <small>(fourni par votre formateur — facultatif pour les formateurs)</small></label>
        <input name="code" autocomplete="off" style="text-transform:uppercase" placeholder="ex. K7P2QX">` : ""}
      <div class="row"><button class="btn" type="submit">${mode === "signup" ? "Créer mon compte" : "Se connecter"}</button>
      ${mode === "login" ? `<a href="#" id="oubli" style="font-size:14px">Mot de passe oublié ?</a>` : ""}</div>
      <div class="err" id="err"></div>
    </form>
  </div>`;
  $$(".seg button").forEach(b => b.onclick = () => vueConnexion(b.dataset.m));
  const f = $("#f");
  f.onsubmit = async ev => {
    ev.preventDefault();
    const d = Object.fromEntries(new FormData(f));
    $("#err").textContent = "";
    try {
      if (mode === "login") {
        await signInWithEmailAndPassword(auth, d.email.trim(), d.mdp);
      } else {
        let classe = null;
        const code = (d.code || "").trim().toUpperCase();
        inscription = true;
        const cred = await createUserWithEmailAndPassword(auth, d.email.trim(), d.mdp);
        if (code) {
          const c = await getDoc(doc(db, "codes", code));
          if (c.exists()) classe = c.data().classe;
          else toast("Code classe inconnu : vous pourrez le saisir plus tard.");
        }
        await setDoc(doc(db, "users", cred.user.uid), {
          email: d.email.trim(), nom: d.nom.trim(), role: "apprenant", classe, cree: serverTimestamp(),
        });
        inscription = false;
        profil = null;
        await chargerProfil();
      }
    } catch (e) {
      inscription = false;
      const z = $("#err"); if (z) z.textContent = erreurFr(e); else toast(erreurFr(e));
    }
  };
  const o = $("#oubli");
  if (o) o.onclick = async ev => {
    ev.preventDefault();
    const email = f.email.value.trim();
    if (!email) { $("#err").textContent = "Saisissez d'abord votre e-mail."; return; }
    try { await sendPasswordResetEmail(auth, email); toast("E-mail de réinitialisation envoyé."); }
    catch (e) { $("#err").textContent = erreurFr(e); }
  };
}

// ======================================================================= Profil et navigation
async function chargerProfil() {
  const s = await getDoc(doc(db, "users", user.uid));
  if (!s.exists()) {
    // Compte sans profil (création interrompue) : on le crée
    await setDoc(doc(db, "users", user.uid), { email: user.email, nom: user.email.split("@")[0], role: "apprenant", classe: null, cree: serverTimestamp() });
    return chargerProfil();
  }
  profil = s.data();
  vue = vue || (estFormateur() ? "valider" : "nouveau");
  rendre();
}

function onglets() {
  const t = [];
  if (!estFormateur()) t.push(["nouveau", "Nouveau relevé"], ["mes", "Mes relevés"], ["jumeau", "Jumeau 3D"]);
  if (estFormateur()) t.push(["valider", "À valider"], ["fiches", "Fiches machines"], ["classes", "Classes"], ["publier", "Publier"]);
  if (estAdmin()) t.push(["users", "Utilisateurs"]);
  t.push(["compte", "Mon compte"]);
  return t;
}

async function rendre() {
  fermerJumeau();
  racine.innerHTML = `
    <div class="who"><h1>Bonjour ${E(profil.nom)}</h1><span class="badge ${profil.role}">${ROLES[profil.role] || profil.role}</span>
      <span style="margin-left:auto"></span><button class="btn ghost sm" id="deco">Se déconnecter</button></div>
    <div class="subnav">${onglets().map(([k, t]) => `<button data-v="${k}" class="${k === vue ? "on" : ""}">${t}${k === "valider" ? ' <span class="n" id="nval" hidden></span>' : ""}</button>`).join("")}</div>
    <div id="vue"><p class="sub">Chargement…</p></div>`;
  $("#deco").onclick = () => signOut(auth);
  $$(".subnav button").forEach(b => b.onclick = () => { vue = b.dataset.v; rendre(); });
  try {
    await chargerMachines();
    const V = { jumeau: vueJumeau, nouveau: vueNouveau, mes: vueMes, valider: vueValider, fiches: vueFiches, classes: vueClasses, publier: vuePublier, users: vueUsers, compte: vueCompte };
    await (V[vue] || vueCompte)($("#vue"));
    if (estFormateur()) compterAValider();
  } catch (e) {
    $("#vue").innerHTML = `<div class="note warn">${E(erreurFr(e))}</div>`;
  }
}

async function compterAValider() {
  const s = await getDocs(query(collection(db, "propositions"), where("statut", "==", "a_valider")));
  const n = $("#nval"); if (n && s.size) { n.hidden = false; n.textContent = s.size; }
}

async function chargerMachines() {
  const s = await getDocs(collection(db, "machines"));
  machines = s.docs.map(d => ({ id: d.id, data: d.data() })).sort((a, b) => (a.data.ordre ?? 99) - (b.data.ordre ?? 99));
}
const optionsMachines = sel => machines.map(m => `<option value="${m.id}" ${m.id === sel ? "selected" : ""}>${E(m.data.code)} — ${E(m.data.nom)}</option>`).join("");
const optionsPoints = (mid, sel) => {
  const m = machines.find(x => x.id === mid);
  return `<option value="">— aucun / je ne sais pas —</option>` + (m?.data.entretien || []).map((p, i) =>
    `<option value="${i}" ${String(i) === String(sel) ? "selected" : ""}>${i + 1} · ${E(p.ou)}</option>`).join("");
};

// ======================================================================= Apprenant : nouveau relevé
async function compresserPhoto(file) {
  const img = await new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = URL.createObjectURL(file); });
  let max = 1024, q = 0.72, data;
  for (let k = 0; k < 6; k++) {
    const r = Math.min(1, max / Math.max(img.width, img.height));
    const c = document.createElement("canvas"); c.width = Math.round(img.width * r); c.height = Math.round(img.height * r);
    c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
    data = c.toDataURL("image/jpeg", q);
    if (data.length < 600000) break;
    max = Math.round(max * 0.8); q -= 0.08;
  }
  return data;
}

function champsType(type) {
  if (type === "releve") return `
    <label class="f">Désignation de l'organe</label><input name="designation" placeholder="ex. Moto-réducteur dosage solide">
    <label class="f">Fabricant</label><input name="fabricant">
    <label class="f">Référence</label><input name="reference">
    <label class="f">Caractéristiques relevées <small>(puissance, tension, intensité, vitesse, pression…)</small></label>
    <textarea name="caracteristiques" placeholder="ex. 0,18 kW · 400 V · 0,63 A · 1370 tr/min"></textarea>`;
  if (type === "point") return `
    <label class="f">Où ? <small>(organe concerné)</small></label><input name="ou" required>
    <label class="f">Action d'entretien proposée</label><textarea name="action" required></textarea>
    <label class="f">Périodicité</label><input name="periode" placeholder="ex. Mensuel, 6 mois…">
    <label class="f">Type d'élément</label><select name="typePoint">${TYPES_POINT.map(t => `<option>${t}</option>`).join("")}</select>`;
  if (type === "chaine") return `
    <label class="f">Chaîne concernée</label><input name="chaineTitre" placeholder="ex. Action — convoyeur">
    <label class="f">Fonction (bloc)</label><select name="bloc"><option>Alimenter</option><option>Distribuer</option><option>Convertir</option><option>Transmettre</option><option>Agir</option><option>Acquérir</option><option>Traiter</option><option>Communiquer</option></select>
    <label class="f">Organe / composant</label><input name="organe">
    <label class="f">Explication</label><textarea name="explication"></textarea>`;
  return `<label class="f">Description</label><textarea name="description" required></textarea>`;
}

async function vueNouveau(el) {
  if (!machines.length) { el.innerHTML = `<div class="note">Aucune machine n'est encore disponible : votre formateur doit d'abord initialiser les fiches.</div>`; return; }
  el.innerHTML = `
  <div class="card" style="max-width:720px">
    <h3 style="margin-top:0">Nouveau relevé</h3>
    <p class="sub">Devant la machine : observez, photographiez, notez. Votre formateur validera votre proposition.</p>
    <form id="f">
      <label class="f">Machine</label><select name="machine" id="mach">${optionsMachines(machines[0].id)}</select>
      <label class="f">Type</label><select name="type" id="type">${Object.entries(TYPES_PROP).map(([k, v]) => `<option value="${k}">${v}</option>`).join("")}</select>
      <label class="f">Repère concerné <small>(numéro affiché dans l'appli)</small></label><select name="repere" id="rep">${optionsPoints(machines[0].id)}</select>
      <div id="champs">${champsType("releve")}</div>
      <label class="f">Photo <small>(facultative — plaque signalétique, organe…)</small></label>
      <input type="file" accept="image/*" capture="environment" id="photo">
      <img id="apercu" class="photo" hidden alt="">
      <label class="f">Commentaire</label><textarea name="commentaire" style="min-height:60px"></textarea>
      <div class="row"><button class="btn" type="submit" id="go">Envoyer au formateur</button></div>
      <div class="err" id="err"></div>
    </form>
  </div>`;
  let photo = null;
  $("#mach").onchange = e => { $("#rep").innerHTML = optionsPoints(e.target.value); };
  $("#type").onchange = e => { $("#champs").innerHTML = champsType(e.target.value); };
  $("#photo").onchange = async e => {
    const f = e.target.files[0]; if (!f) return;
    try { photo = await compresserPhoto(f); $("#apercu").src = photo; $("#apercu").hidden = false; }
    catch { toast("Photo illisible"); }
  };
  $("#f").onsubmit = async ev => {
    ev.preventDefault();
    const d = Object.fromEntries(new FormData(ev.target));
    const { machine, type, repere, commentaire, ...contenu } = d;
    const m = machines.find(x => x.id === machine);
    $("#go").disabled = true;
    try {
      await addDoc(collection(db, "propositions"), {
        auteur: user.uid, auteurNom: profil.nom, classe: profil.classe || null,
        machine, machineCode: m?.data.code || machine, type,
        repere: repere === "" ? null : Number(repere),
        repereNom: repere === "" ? null : (m?.data.entretien?.[Number(repere)]?.ou || null),
        contenu, commentaire: commentaire || "", photo: photo || null,
        statut: "a_valider", reponse: "", cree: serverTimestamp(),
      });
      toast("Relevé envoyé ✓");
      vue = "mes"; rendre();
    } catch (e) { $("#err").textContent = erreurFr(e); $("#go").disabled = false; }
  };
}

const LIBELLES = {
  designation: "Désignation", fabricant: "Fabricant", reference: "Référence", caracteristiques: "Caractéristiques",
  ou: "Organe", action: "Action", periode: "Périodicité", typePoint: "Type", chaineTitre: "Chaîne", bloc: "Fonction",
  organe: "Organe", explication: "Explication", description: "Description",
};
function blocContenu(p) {
  const c = p.contenu || {};
  const lignes = Object.entries(c).filter(([, v]) => String(v).trim()).map(([k, v]) => `<b>${E(LIBELLES[k] || k)}</b><span>${E(v)}</span>`).join("");
  return `${lignes ? `<div class="kv">${lignes}</div>` : ""}
    ${p.commentaire ? `<p class="m" style="margin:6px 0 0">💬 ${E(p.commentaire)}</p>` : ""}
    ${p.photo ? `<img class="photo" src="${p.photo}" alt="Photo du relevé">` : ""}`;
}

// ======================================================================= Apprenant : mes relevés
async function vueMes(el) {
  const s = await getDocs(query(collection(db, "propositions"), where("auteur", "==", user.uid)));
  const l = s.docs.map(d => ({ id: d.id, ...d.data() })).sort((a, b) => (b.cree?.seconds || 0) - (a.cree?.seconds || 0));
  if (!l.length) { el.innerHTML = `<div class="note">Aucun relevé pour l'instant. Commencez par « Nouveau relevé ».</div>`; return; }
  el.innerHTML = l.map(p => `
    <div class="card item">
      <div><div class="t">${E(TYPES_PROP[p.type] || p.type)} · ${E(p.machineCode)}${p.repereNom ? " · " + E(p.repereNom) : ""}</div>
        <div class="m">${fmt(p.cree)}</div>${blocContenu(p)}
        ${p.reponse ? `<div class="note ${p.statut === "a_reprendre" ? "warn" : "ok"}" style="margin-bottom:0">Formateur : ${E(p.reponse)}</div>` : ""}</div>
      <div style="text-align:right"><span class="st ${p.statut}">${STATUTS[p.statut] || p.statut}</span>
        ${p.statut !== "integree" ? `<div style="margin-top:10px"><button class="x" data-del="${p.id}" title="Supprimer">🗑</button></div>` : ""}</div>
    </div>`).join("");
  $$("[data-del]").forEach(b => b.onclick = async () => {
    if (!confirm("Supprimer ce relevé ?")) return;
    try { await deleteDoc(doc(db, "propositions", b.dataset.del)); rendre(); } catch (e) { toast(erreurFr(e)); }
  });
}

// ======================================================================= Formateur : valider
function texteIntegration(p) {
  const c = p.contenu || {};
  if (p.type === "releve") return [c.designation, c.fabricant && "Fabricant : " + c.fabricant, c.reference && "Réf. : " + c.reference, c.caracteristiques].filter(Boolean).join(" · ");
  if (p.type === "chaine") return [c.chaineTitre, c.bloc && c.bloc.toUpperCase(), c.organe, c.explication].filter(Boolean).join(" · ");
  if (p.type === "remarque") return c.description || "";
  return "";
}

async function vueValider(el) {
  const filtre = el.dataset.f || "a_valider";
  const s = await getDocs(query(collection(db, "propositions"), where("statut", "==", filtre)));
  const l = s.docs.map(d => ({ id: d.id, ...d.data() })).sort((a, b) => (b.cree?.seconds || 0) - (a.cree?.seconds || 0));
  el.innerHTML = `<div class="row" style="margin:0 0 14px">${Object.entries(STATUTS).map(([k, v]) =>
      `<button class="btn sm ${k === filtre ? "" : "ghost"}" data-f="${k}">${v}</button>`).join("")}</div>
    ${l.length ? "" : `<div class="note">Rien dans cette catégorie.</div>`}
    ${l.map(p => `
    <div class="card item" data-id="${p.id}">
      <div><div class="t">${E(TYPES_PROP[p.type] || p.type)} · ${E(p.machineCode)}${p.repereNom ? ` · repère ${p.repere + 1} « ${E(p.repereNom)} »` : ""}</div>
        <div class="m">${E(p.auteurNom)} · ${fmt(p.cree)}</div>
        ${blocContenu(p)}
        ${p.statut === "a_valider" ? `
          ${p.type === "point" ? `<p class="m" style="margin-top:10px">« Intégrer » ajoute ce point à la fiche ${E(p.machineCode)} (position à caler ensuite avec 📍 dans l'appli).</p>` :
            `<label class="f">Texte ajouté à la fiche ${p.repere != null ? `(informations du repère ${p.repere + 1})` : "(aucun repère : archivé seulement)"}</label>
             <textarea data-txt>${E(texteIntegration(p))}</textarea>`}
          <label class="f">Réponse à l'apprenant <small>(facultatif)</small></label><input data-rep placeholder="ex. Bien vu ! / Vérifie la référence sur la plaque">
          <div class="row" style="margin-top:12px">
            <button class="btn sm ok" data-act="integrer">✓ Intégrer</button>
            <button class="btn sm warn" data-act="reprendre">↺ À reprendre</button>
            <button class="btn sm ghost" data-act="suppr">Supprimer</button>
          </div>` : (p.reponse ? `<p class="m">Réponse : ${E(p.reponse)}</p>` : "")}
      </div>
      <span class="st ${p.statut}">${STATUTS[p.statut]}</span>
    </div>`).join("")}`;
  $$("[data-f]", el).forEach(b => b.onclick = () => { el.dataset.f = b.dataset.f; vueValider(el); });
  $$("[data-act]", el).forEach(b => b.onclick = async () => {
    const carte = b.closest("[data-id]"); const id = carte.dataset.id;
    const p = l.find(x => x.id === id);
    const reponse = $("[data-rep]", carte)?.value || "";
    try {
      if (b.dataset.act === "suppr") {
        if (!confirm("Supprimer définitivement ?")) return;
        await deleteDoc(doc(db, "propositions", id));
      } else if (b.dataset.act === "reprendre") {
        await updateDoc(doc(db, "propositions", id), { statut: "a_reprendre", reponse, traitePar: profil.nom, traiteLe: serverTimestamp() });
      } else {
        await integrer(p, $("[data-txt]", carte)?.value || "");
        await updateDoc(doc(db, "propositions", id), { statut: "integree", reponse, traitePar: profil.nom, traiteLe: serverTimestamp() });
      }
      toast("Enregistré ✓"); rendre();
    } catch (e) { toast(erreurFr(e)); }
  });
}

async function integrer(p, texte) {
  const ref = doc(db, "machines", p.machine);
  await runTransaction(db, async tx => {
    const s = await tx.get(ref);
    if (!s.exists()) throw new Error("Fiche machine introuvable");
    const m = s.data();
    m.entretien = m.entretien || [];
    if (p.type === "point") {
      const c = p.contenu || {};
      m.entretien.push({ ou: c.ou || "Nouveau point", action: c.action || "", periode: c.periode || "", type: c.typePoint || "", pos_cm: { x: 0, y: 0, z: 0 } });
    } else if (p.repere != null && m.entretien[p.repere] && texte.trim()) {
      const pt = m.entretien[p.repere];
      pt.infos = (pt.infos ? pt.infos + "\n" : "") + texte.trim();
    }
    tx.set(ref, { ...m, majPar: profil.nom, majLe: serverTimestamp() });
  });
}

// ======================================================================= Formateur : fiches machines
function normaliser(m) {
  // Ancien format (fonction + chaine) → chaines
  if (!m.chaines && m.fonction) {
    m.chaines = [{ titre: "Chaîne fonctionnelle", type: "action", intro: m.fonction.intro || "", blocs: m.fonction.blocs || [], maillons: m.chaine || [] }];
  }
  delete m.fonction; delete m.chaine;
  m.chaines = m.chaines || [];
  m.entretien = m.entretien || [];
  m.securite = m.securite || { titre: "", etapes: [], note: "" };
  m.securite.etapes = (m.securite.etapes || []).map(e => typeof e === "string" ? { texte: e } : e);
  if (!m.cibles && m.image) m.cibles = [{ image: m.image, face: "dessus", pos_cm: { x: 0, y: 0, z: 0 } }];
  delete m.image;
  return m;
}

const getP = (o, p) => p.split(".").reduce((a, k) => a?.[k], o);
function setP(o, p, v) {
  const ks = p.split("."); let a = o;
  ks.slice(0, -1).forEach((k, i) => { if (a[k] == null) a[k] = /^\d+$/.test(ks[i + 1]) ? [] : {}; a = a[k]; });
  a[ks[ks.length - 1]] = v;
}
const inp = (path, m, opts = {}) => {
  const v = getP(m, path) ?? "";
  if (opts.select) return `<select data-p="${path}">${opts.select.map(o => `<option ${String(o) === String(v) ? "selected" : ""}>${E(o)}</option>`).join("")}</select>`;
  if (opts.area) return `<textarea data-p="${path}">${E(v)}</textarea>`;
  return `<input data-p="${path}" ${opts.num ? `type="number" step="any" data-num` : ""} value="${E(v)}" ${opts.ph ? `placeholder="${E(opts.ph)}"` : ""}>`;
};

let edition = null; // {id, m, sale}

async function vueFiches(el) {
  if (!machines.length) {
    el.innerHTML = `<div class="note">Aucune fiche en base. ${estAdmin() ? "Initialisez-les depuis le contenu actuel de l'appli :" : "Demandez à l'administrateur d'initialiser les fiches."}</div>
      ${estAdmin() ? `<button class="btn" id="init">Initialiser les fiches depuis l'appli</button>` : ""}`;
    const b = $("#init"); if (b) b.onclick = initialiser;
    return;
  }
  const id = edition?.id && machines.find(x => x.id === edition.id) ? edition.id : machines[0].id;
  if (!edition || edition.id !== id) edition = { id, m: normaliser(structuredClone(machines.find(x => x.id === id).data)), sale: false };
  edition.modele = await chargerModele(edition.id);
  dessinerEditeur(el);
}

const pointsJumeau = m => m.entretien.map(p => ({ x: +(p.pos_cm?.x ?? 0), y: +(p.pos_cm?.y ?? 0), z: +(p.pos_cm?.z ?? 0), ou: p.ou }));

function sectionJumeau(m, editable) {
  return `
  <div class="j3d-wrap">
    <div id="j3d" class="j3d"></div>
    <div class="j3d-side">
      <div class="row" style="margin:0 0 8px;gap:6px">${[["iso", "3D"], ["face", "Face"], ["gauche", "Gauche"], ["droite", "Droite"], ["dessus", "Dessus"], ["arriere", "Arrière"]]
        .map(([k, t]) => `<button class="btn sm ghost" data-vue="${k}">${t}</button>`).join("")}</div>
      <div id="j3d-msg" class="note" style="margin:0 0 8px">${editable
        ? "Choisissez un repère ci-dessous, puis cliquez sur la maquette à l'endroit de l'organe."
        : "Faites tourner la maquette, survolez les organes, touchez un repère numéroté."}</div>
      <div class="j3d-list">${m.entretien.map((p, i) => `<button class="j3d-pt" data-pl="${i}"><span class="num">${i + 1}</span><span>${E(p.ou || "(sans nom)")}</span></button>`).join("")}</div>
    </div>
  </div>`;
}

function dessinerEditeur(el) {
  fermerJumeau();
  const m = edition.m;
  const nPts = m.entretien.length, nPlaces = m.entretien.filter(p => p.pos_cm && (p.pos_cm.x || p.pos_cm.y || p.pos_cm.z)).length;
  const nComplets = m.entretien.filter(p => p.ou && p.action && p.periode).length;
  const score = Math.round(100 * (nComplets + (m.chaines.length ? 1 : 0) + (m.securite.etapes.length ? 1 : 0)) / Math.max(1, nPts + 2));
  el.innerHTML = `
  <div class="row" style="margin:0 0 6px">
    <select id="choix" style="max-width:360px">${optionsMachines(edition.id)}</select>
    <span class="m">${m.majLe ? "Modifiée le " + fmt(m.majLe) + (m.majPar ? " par " + E(m.majPar) : "") : ""}</span>
  </div>
  <div class="card" style="margin-top:10px">
    <div class="grid g3" style="gap:12px">
      <div><label class="f" style="margin-top:0">Code</label>${inp("code", m)}</div>
      <div style="grid-column:span 2"><label class="f" style="margin-top:0">Nom</label>${inp("nom", m)}</div>
    </div>
    <div style="margin-top:14px" class="m">Complétude ${score} % · ${nPts} repères (${nComplets} complets) · ${m.chaines.length} chaîne(s) · ${m.securite.etapes.length} étape(s) de sécurité</div>
    <div class="progress" style="margin-top:6px"><i style="width:${score}%"></i></div>
  </div>

  ${edition.modele ? `<div class="ed-sec"><h3>Jumeau numérique 3D <span class="m" style="font-weight:400">— placer les repères sur la maquette</span></h3>
    ${sectionJumeau(m, true)}
    <p class="m">Maquette simplifiée : la précision dépend de la maquette (quelques cm). Retouche fine possible avec 📍 dans l'appli. N'oubliez pas d'<b>enregistrer</b> puis de <b>publier</b>.</p></div>` : ""}

  <div class="ed-sec"><h3>Marqueurs <span class="m" style="font-weight:400">(position et orientation dans le repère machine)</span></h3>
    ${(m.cibles || []).map((c, i) => `
      <div class="ed-row r-cib">
        <div><label>Image</label><input value="${E(c.image)}" disabled></div>
        <div><label>Face</label>${inp(`cibles.${i}.face`, m, { select: ["dessus", "avant", "arriere", "gauche", "droite"] })}</div>
        <div><label>x cm</label>${inp(`cibles.${i}.pos_cm.x`, m, { num: 1 })}</div>
        <div><label>y cm</label>${inp(`cibles.${i}.pos_cm.y`, m, { num: 1 })}</div>
        <div><label>z cm</label>${inp(`cibles.${i}.pos_cm.z`, m, { num: 1 })}</div>
        <div><label>Largeur cm</label>${inp(`cibles.${i}.largeur_cm`, m, { num: 1, ph: "réglage" })}</div>
      </div>`).join("")}
    <p class="m">Repère machine : x vers la droite vu de face, y vers le haut, z vers l'opérateur. Un nouveau marqueur (nouvelle image) se demande à l'administrateur.</p>
  </div>

  <div class="ed-sec"><h3>Repères / points d'entretien <button class="btn sm ghost" data-add="pt">+ Ajouter</button></h3>
    ${m.entretien.map((p, i) => `
      <div class="ed-row r-pt">
        <span class="num">${i + 1}</span>
        <div><label>Organe (où)</label>${inp(`entretien.${i}.ou`, m)}</div>
        <div><label>Action d'entretien</label>${inp(`entretien.${i}.action`, m)}</div>
        <div><label>Périodicité</label>${inp(`entretien.${i}.periode`, m)}</div>
        <div><label>Type</label>${inp(`entretien.${i}.type`, m, { select: ["", ...TYPES_POINT] })}</div>
        <div><label>x cm</label>${inp(`entretien.${i}.pos_cm.x`, m, { num: 1 })}</div>
        <div><label>y cm</label>${inp(`entretien.${i}.pos_cm.y`, m, { num: 1 })}</div>
        <div><label>z cm</label>${inp(`entretien.${i}.pos_cm.z`, m, { num: 1 })}</div>
        <button class="x" data-del="entretien.${i}" title="Supprimer">✕</button>
        <div style="grid-column:2 / -2"><label>Informations (caractéristiques, relevés…)</label>${inp(`entretien.${i}.infos`, m, { area: 1 })}</div>
      </div>`).join("")}
    <details style="margin-top:12px"><summary>Importer les positions placées avec l'appli (⚙ → Exporter les positions)</summary>
      <p class="m">Collez le texte reçu de l'appli : les positions des repères de cette machine seront mises à jour (par numéro de repère).</p>
      <textarea id="imp" placeholder='{ "machines": [ … ] }'></textarea>
      <div class="row"><button class="btn sm" id="impgo">Importer</button></div>
    </details>
  </div>

  <div class="ed-sec"><h3>Chaînes d'action et d'acquisition <button class="btn sm ghost" data-add="ch">+ Ajouter une chaîne</button></h3>
    ${m.chaines.map((c, k) => `
      <div class="chaine">
        <div class="grid" style="grid-template-columns:2fr 1fr auto;gap:10px;align-items:end">
          <div><label class="f" style="margin-top:0">Titre</label>${inp(`chaines.${k}.titre`, m)}</div>
          <div><label class="f" style="margin-top:0">Type</label>${inp(`chaines.${k}.type`, m, { select: ["action", "acquisition"] })}</div>
          <button class="x" data-del="chaines.${k}" title="Supprimer la chaîne">✕</button>
        </div>
        <label class="f">Introduction</label>${inp(`chaines.${k}.intro`, m)}
        <h4 style="margin:14px 0 0">Blocs fonctionnels <button class="btn sm ghost" data-add="bloc:${k}">+ Bloc</button></h4>
        ${(c.blocs || []).map((b, j) => `
          <div class="ed-row r-bloc"><span class="num">${j + 1}</span>
            <div><label>Fonction</label>${inp(`chaines.${k}.blocs.${j}.titre`, m, { ph: "Alimenter, Acquérir…" })}</div>
            <div><label>Description</label>${inp(`chaines.${k}.blocs.${j}.texte`, m)}</div>
            <button class="x" data-del="chaines.${k}.blocs.${j}">✕</button></div>`).join("")}
        <h4 style="margin:14px 0 0">Maillons posés sur la machine <button class="btn sm ghost" data-add="mai:${k}">+ Maillon</button></h4>
        <p class="m" style="margin:2px 0 0">Indiquer le n° de bloc, et soit un n° de repère (position suivie automatiquement), soit une position x / y / z.</p>
        ${(c.maillons || []).map((a, j) => `
          <div class="ed-row r-mai"><span class="num">${j + 1}</span>
            <div><label>N° bloc</label>${inp(`chaines.${k}.maillons.${j}.bloc`, m, { num: 1 })}</div>
            <div><label>Organe</label>${inp(`chaines.${k}.maillons.${j}.nom`, m)}</div>
            <div><label>N° repère</label>${inp(`chaines.${k}.maillons.${j}.repere`, m, { num: 1, ph: "—" })}</div>
            <div><label>x</label>${inp(`chaines.${k}.maillons.${j}.pos_cm.x`, m, { num: 1 })}</div>
            <div><label>y</label>${inp(`chaines.${k}.maillons.${j}.pos_cm.y`, m, { num: 1 })}</div>
            <div><label>z</label>${inp(`chaines.${k}.maillons.${j}.pos_cm.z`, m, { num: 1 })}</div>
            <button class="x" data-del="chaines.${k}.maillons.${j}">✕</button></div>`).join("")}
      </div>`).join("")}
  </div>

  <div class="ed-sec"><h3>Sécurité <button class="btn sm ghost" data-add="et">+ Étape</button></h3>
    <label class="f" style="margin-top:0">Titre de la procédure</label>${inp("securite.titre", m)}
    ${m.securite.etapes.map((e, i) => `
      <div class="ed-row r-et"><span class="num">${i + 1}</span>
        <div><label>Étape</label>${inp(`securite.etapes.${i}.texte`, m)}</div>
        <div><label>N° repère</label>${inp(`securite.etapes.${i}.repere`, m, { num: 1, ph: "—" })}</div>
        <button class="x" data-del="securite.etapes.${i}">✕</button></div>`).join("")}
    <label class="f">Note d'avertissement</label>${inp("securite.note", m, { area: 1 })}
  </div>

  <div class="sticky-bar">
    <button class="btn" id="save">Enregistrer le brouillon</button>
    ${estAdmin() ? `<button class="btn ghost sm" id="reinit" title="Recharge la fiche telle qu'elle est dans l'appli (positions par défaut à jour)">Recharger la fiche d'origine</button>` : ""}
    <span class="m" id="etat">${edition.sale ? "Modifications non enregistrées" : "À jour"}</span>
    <span style="margin-left:auto" class="m">Les apprenants voient les fiches enregistrées ; l'appli voit la dernière version <b>publiée</b>.</span>
  </div>`;

  $("#choix").onchange = e => {
    if (edition.sale && !confirm("Abandonner les modifications non enregistrées ?")) { e.target.value = edition.id; return; }
    edition = { id: e.target.value, m: normaliser(structuredClone(machines.find(x => x.id === e.target.value).data)), sale: false };
    chargerModele(edition.id).then(mod => { edition.modele = mod; dessinerEditeur(el); });
  };
  $$("[data-p]", el).forEach(i => i.oninput = () => {
    let v = i.value;
    if (i.hasAttribute("data-num")) v = v === "" ? null : Number(v);
    setP(edition.m, i.dataset.p, v);
    edition.sale = true; $("#etat").textContent = "Modifications non enregistrées";
    if (jumeau && /^entretien\.\d+\.(pos_cm|ou)/.test(i.dataset.p)) jumeau.setPoints(pointsJumeau(edition.m));
  });
  $$("[data-del]", el).forEach(b => b.onclick = () => {
    const ks = b.dataset.del.split("."); const idx = Number(ks.pop());
    const arr = getP(edition.m, ks.join(".")); arr.splice(idx, 1);
    edition.sale = true; dessinerEditeur(el);
  });
  $$("[data-add]", el).forEach(b => b.onclick = () => {
    const [t, k] = b.dataset.add.split(":");
    if (t === "pt") m.entretien.push({ ou: "", action: "", periode: "", type: "", pos_cm: { x: 0, y: 0, z: 0 } });
    if (t === "ch") m.chaines.push({ titre: "Nouvelle chaîne", type: "action", intro: "", blocs: [], maillons: [] });
    if (t === "bloc") m.chaines[k].blocs.push({ titre: "", texte: "" });
    if (t === "mai") m.chaines[k].maillons.push({ bloc: 1, nom: "", pos_cm: { x: 0, y: 0, z: 0 } });
    if (t === "et") m.securite.etapes.push({ texte: "" });
    edition.sale = true; dessinerEditeur(el);
  });
  $("#impgo").onclick = () => {
    try {
      const j = JSON.parse($("#imp").value);
      const src = (j.machines || []).find(x => x.id === edition.id);
      if (!src) throw new Error("Cette machine n'est pas dans le texte collé.");
      let n = 0;
      (src.entretien || []).forEach((p, i) => { if (m.entretien[i] && p.pos_cm) { m.entretien[i].pos_cm = p.pos_cm; n++; } });
      edition.sale = true; toast(n + " position(s) importée(s) — pensez à enregistrer"); dessinerEditeur(el);
    } catch (e) { toast("Import impossible : " + e.message); }
  };
  const ri = $("#reinit");
  if (ri) ri.onclick = async () => {
    if (!confirm("Remplacer le brouillon de cette fiche par la version d'origine de l'appli ? (rien n'est enregistré tant que vous ne cliquez pas sur « Enregistrer »)")) return;
    const j = await (await fetch("data/machines.json", { cache: "no-store" })).json();
    const src = j.machines.find(x => x.id === edition.id);
    if (!src) { toast("Fiche absente de l'appli"); return; }
    const { id, ...reste } = normaliser(structuredClone(src));
    edition.m = { ...reste, ordre: edition.m.ordre }; edition.sale = true; dessinerEditeur(el);
    toast("Fiche d'origine chargée — pensez à enregistrer");
  };
  if (edition.modele) brancherJumeau(edition.m, true);

  $("#save").onclick = async () => {
    try {
      const propre = nettoyer(structuredClone(edition.m));
      await setDoc(doc(db, "machines", edition.id), { ...propre, majPar: profil.nom, majLe: serverTimestamp() });
      edition.sale = false; toast("Brouillon enregistré ✓");
      await chargerMachines();
      edition.m = normaliser(structuredClone(machines.find(x => x.id === edition.id).data));
      dessinerEditeur(el);
    } catch (e) { toast(erreurFr(e)); }
  };
}

function brancherJumeau(m, editable, surSelection) {
  const msg = $("#j3d-msg");
  const marquer = i => $$(".j3d-pt").forEach(b => b.classList.toggle("on", Number(b.dataset.pl) === i));
  jumeau = creerJumeau($("#j3d"), {
    modele: edition && editable ? edition.modele : surSelection.modele,
    points: pointsJumeau(m),
    editable,
    onPlace: (i, pos, nom) => {
      setP(m, `entretien.${i}.pos_cm`, pos);
      ["x", "y", "z"].forEach(k => { const f = $(`[data-p="entretien.${i}.pos_cm.${k}"]`); if (f) f.value = pos[k]; });
      edition.sale = true; $("#etat").textContent = "Modifications non enregistrées";
      msg.innerHTML = `✓ Repère ${i + 1} placé sur <b>${E(nom || "la maquette")}</b> (x ${pos.x}, y ${pos.y}, z ${pos.z}). Choisissez le suivant ou enregistrez.`;
      marquer(-1);
    },
    onSelect: i => {
      marquer(-1);
      $$(".j3d-pt").forEach(b => b.classList.toggle("sel", Number(b.dataset.pl) === i));
      if (surSelection) surSelection.afficher(i);
      else msg.innerHTML = `Repère ${i + 1} : <b>${E(m.entretien[i]?.ou || "")}</b>. Cliquez sur « ${i + 1} » dans la liste pour le replacer.`;
    },
  });
  $$("[data-vue]").forEach(b => b.onclick = () => jumeau.vue(b.dataset.vue));
  $$(".j3d-pt").forEach(b => b.onclick = () => {
    const i = Number(b.dataset.pl);
    if (editable) {
      jumeau.placer(i); marquer(i);
      msg.innerHTML = `Cliquez sur la maquette à l'endroit du repère <b>${i + 1} · ${E(m.entretien[i]?.ou || "")}</b>.`;
    } else {
      jumeau.setSelection(i);
      $$(".j3d-pt").forEach(x => x.classList.toggle("sel", x === b));
      surSelection.afficher(i);
    }
  });
}

// ======================================================================= Apprenant : jumeau 3D (consultation)
async function vueJumeau(el) {
  const dispo = [];
  for (const m of machines) if (await chargerModele(m.id)) dispo.push(m);
  if (!dispo.length) { el.innerHTML = `<div class="note">Aucune machine n'a encore de maquette 3D.</div>`; return; }
  const id = el.dataset.m && dispo.find(x => x.id === el.dataset.m) ? el.dataset.m : dispo[0].id;
  const m = normaliser(structuredClone(dispo.find(x => x.id === id).data));
  const modele = await chargerModele(id);
  el.innerHTML = `
    <div class="row" style="margin:0 0 10px"><select id="jm" style="max-width:360px">${dispo.map(x => `<option value="${x.id}" ${x.id === id ? "selected" : ""}>${E(x.data.code)} — ${E(x.data.nom)}</option>`).join("")}</select>
      <span class="m">Préparez votre intervention : repérez les organes avant d'aller à la machine.</span></div>
    ${sectionJumeau(m, false)}
    <div class="card" id="jdet" style="margin-top:14px"><p class="m" style="margin:0">Touchez un repère pour afficher sa fiche.</p></div>`;
  $("#jm").onchange = e => { el.dataset.m = e.target.value; fermerJumeau(); vueJumeau(el); };
  brancherJumeau(m, false, {
    modele,
    afficher: i => {
      const p = m.entretien[i]; if (!p) return;
      $("#jdet").innerHTML = `<div class="eyebrow">Repère ${i + 1}${p.type ? " · " + E(p.type) : ""}</div>
        <h3 style="margin:4px 0">${E(p.ou)}</h3>
        <div class="kv"><b>Entretien</b><span>${E(p.action)}</span><b>Périodicité</b><span>${E(p.periode)}</span>
        ${p.infos ? `<b>Informations</b><span style="white-space:pre-line">${E(p.infos)}</span>` : ""}</div>`;
    },
  });
}

/** Retire les champs vides et normalise les numéros avant enregistrement / publication. */
function nettoyer(m) {
  delete m.majLe; delete m.majPar;
  (m.entretien || []).forEach(p => { if (!p.infos) delete p.infos; if (!p.type) delete p.type; p.pos_cm = p.pos_cm || { x: 0, y: 0, z: 0 }; });
  (m.chaines || []).forEach(c => (c.maillons || []).forEach(a => {
    if (a.repere == null || a.repere === "") { delete a.repere; a.pos_cm = a.pos_cm || { x: 0, y: 0, z: 0 }; }
    else { delete a.pos_cm; }
    a.bloc = Number(a.bloc) || 1;
  }));
  (m.securite?.etapes || []).forEach(e => { if (e.repere == null || e.repere === "") delete e.repere; });
  (m.cibles || []).forEach(c => { if (c.largeur_cm == null || c.largeur_cm === "") delete c.largeur_cm; });
  return m;
}

async function initialiser() {
  try {
    const r = await fetch("data/machines.json", { cache: "no-store" });
    const j = await r.json();
    let i = 0;
    for (const m of j.machines) {
      const { id, ...reste } = normaliser(structuredClone(m));
      await setDoc(doc(db, "machines", id), { ...nettoyer(reste), id, ordre: i++, majPar: profil.nom, majLe: serverTimestamp() });
    }
    toast(i + " fiches initialisées ✓"); rendre();
  } catch (e) { toast(erreurFr(e)); }
}

// ======================================================================= Formateur : classes
function codeAleatoire() {
  const a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  return Array.from(crypto.getRandomValues(new Uint8Array(6)), x => a[x % a.length]).join("");
}

async function vueClasses(el) {
  const [cs, us] = await Promise.all([getDocs(collection(db, "classes")), getDocs(query(collection(db, "users"), where("role", "==", "apprenant")))]);
  const classes = cs.docs.map(d => ({ id: d.id, ...d.data() }));
  const app = us.docs.map(d => ({ id: d.id, ...d.data() }));
  el.innerHTML = `
    <div class="card" style="max-width:640px">
      <h3 style="margin-top:0">Créer une classe</h3>
      <form id="f" class="row" style="margin-top:6px"><input name="nom" required placeholder="ex. BTS MS 1re année — 2026" style="flex:1;min-width:220px"><button class="btn sm">Créer</button></form>
    </div>
    <div class="grid g2" style="margin-top:18px">
    ${classes.map(c => {
      const membres = app.filter(u => u.classe === c.id);
      return `<div class="card"><div class="eyebrow">Code classe</div>
        <div style="font:800 30px 'JetBrains Mono';letter-spacing:.12em;color:var(--acc)">${E(c.code)}</div>
        <h3 style="margin-top:6px">${E(c.nom)}</h3>
        <p class="m">${E(c.formateurNom || "")} · ${membres.length} apprenant(s)</p>
        <ul style="margin:8px 0 0;padding-left:18px;color:var(--mut)">${membres.map(u => `<li>${E(u.nom)} <span class="m">${E(u.email)}</span></li>`).join("") || "<li>Aucun inscrit pour l'instant</li>"}</ul>
        <div class="row" style="margin-top:12px"><button class="btn sm ghost" data-sup="${c.id}" data-code="${E(c.code)}">Supprimer la classe</button></div></div>`;
    }).join("") || `<div class="note">Aucune classe. Créez-en une : ses apprenants s'inscriront avec son code.</div>`}
    </div>
    ${app.filter(u => !u.classe).length ? `<h3>Apprenants sans classe</h3><div class="card">${app.filter(u => !u.classe).map(u => `<div>${E(u.nom)} <span class="m">${E(u.email)}</span></div>`).join("")}</div>` : ""}`;
  $("#f").onsubmit = async ev => {
    ev.preventDefault();
    const nom = new FormData(ev.target).get("nom").trim();
    try {
      let code = codeAleatoire();
      for (let k = 0; k < 5 && (await getDoc(doc(db, "codes", code))).exists(); k++) code = codeAleatoire();
      const ref = await addDoc(collection(db, "classes"), { nom, code, formateur: user.uid, formateurNom: profil.nom, cree: serverTimestamp() });
      await setDoc(doc(db, "codes", code), { classe: ref.id, nom });
      toast("Classe créée — code " + code); rendre();
    } catch (e) { toast(erreurFr(e)); }
  };
  $$("[data-sup]").forEach(b => b.onclick = async () => {
    if (!confirm("Supprimer cette classe ? Les comptes des apprenants sont conservés.")) return;
    try { await deleteDoc(doc(db, "classes", b.dataset.sup)); await deleteDoc(doc(db, "codes", b.dataset.code)); rendre(); }
    catch (e) { toast(erreurFr(e)); }
  });
}

// ======================================================================= Formateur : publier
async function vuePublier(el) {
  const s = await getDoc(doc(db, "publie", "contenu"));
  const p = s.exists() ? s.data() : null;
  el.innerHTML = `
  <div class="card" style="max-width:760px">
    <div class="eyebrow">Contenu de l'appli</div>
    <h2 style="margin:6px 0">${p ? "Version publiée n° " + p.version : "Rien n'est encore publié"}</h2>
    <p class="m">${p ? "Publiée le " + fmt(p.publieLe) + " par " + E(p.publiePar) : "L'appli utilise le contenu intégré à l'APK."}</p>
    <p>Publier envoie les <b>${machines.length} fiche(s)</b> enregistrées vers l'appli. Au prochain démarrage, chaque téléphone connecté à Internet télécharge le nouveau contenu et propose de recharger — sans réinstallation.</p>
    <ul class="m">${machines.map(m => `<li>${E(m.data.code)} — ${E(m.data.nom)} : ${(m.data.entretien || []).length} repères, ${(m.data.chaines || []).length} chaîne(s)${m.data.majLe ? " · modifiée le " + fmt(m.data.majLe) : ""}</li>`).join("")}</ul>
    <div class="row"><button class="btn" id="pub" ${machines.length ? "" : "disabled"}>Publier vers l'appli</button>
      <button class="btn ghost" id="dl">Télécharger machines.json</button></div>
  </div>`;
  const construire = async () => {
    const aide = (await (await fetch("data/machines.json", { cache: "no-store" })).json())._aide || "";
    return { _aide: aide, machines: machines.map(m => { const { ordre, ...d } = nettoyer(structuredClone(m.data)); return { ...d, id: m.id }; }) };
  };
  $("#pub").onclick = async () => {
    if (!confirm("Publier les fiches vers tous les téléphones ?")) return;
    try {
      const contenu = await construire();
      const version = (p?.version || 0) + 1;
      await setDoc(doc(db, "publie", "contenu"), { json: JSON.stringify(contenu), version, publieLe: serverTimestamp(), publiePar: profil.nom });
      toast("Version " + version + " publiée ✓"); rendre();
    } catch (e) { toast(erreurFr(e)); }
  };
  $("#dl").onclick = async () => {
    const blob = new Blob([JSON.stringify(await construire(), null, 2)], { type: "application/json" });
    const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = "machines.json"; a.click();
  };
}

// ======================================================================= Admin : utilisateurs
async function vueUsers(el) {
  const [us, cs] = await Promise.all([getDocs(collection(db, "users")), getDocs(collection(db, "classes"))]);
  const classes = cs.docs.map(d => ({ id: d.id, ...d.data() }));
  const l = us.docs.map(d => ({ id: d.id, ...d.data() })).sort((a, b) => (a.role + a.nom).localeCompare(b.role + b.nom));
  el.innerHTML = `<div class="card"><table>
    <tr><th>Nom</th><th>E-mail</th><th>Rôle</th><th>Classe</th></tr>
    ${l.map(u => `<tr data-id="${u.id}"><td>${E(u.nom)}</td><td class="m">${E(u.email)}</td>
      <td><select data-role ${u.id === user.uid ? "disabled" : ""}>${Object.entries(ROLES).map(([k, v]) => `<option value="${k}" ${k === u.role ? "selected" : ""}>${v}</option>`).join("")}</select></td>
      <td><select data-classe><option value="">—</option>${classes.map(c => `<option value="${c.id}" ${c.id === u.classe ? "selected" : ""}>${E(c.nom)}</option>`).join("")}</select></td></tr>`).join("")}
  </table></div>
  <p class="m">Les nouveaux comptes sont créés en « Apprenant ». Passez ici en « Formateur » les collègues qui s'inscrivent.</p>`;
  $$("tr[data-id] select").forEach(s => s.onchange = async () => {
    const id = s.closest("tr").dataset.id;
    const champ = s.hasAttribute("data-role") ? { role: s.value } : { classe: s.value || null };
    try { await updateDoc(doc(db, "users", id), champ); toast("Mis à jour ✓"); } catch (e) { toast(erreurFr(e)); }
  });
}

// ======================================================================= Mon compte
async function vueCompte(el) {
  let nomClasse = "—";
  if (profil.classe) { const c = await getDoc(doc(db, "classes", profil.classe)); if (c.exists()) nomClasse = c.data().nom; }
  el.innerHTML = `
  <div class="card" style="max-width:560px">
    <div class="kv" style="font-size:15px"><b>Nom</b><span>${E(profil.nom)}</span><b>E-mail</b><span>${E(profil.email)}</span>
      <b>Rôle</b><span>${ROLES[profil.role]}</span><b>Classe</b><span>${E(nomClasse)}</span></div>
    ${profil.role === "apprenant" ? `
    <form id="f" style="margin-top:16px"><label class="f">Rejoindre une classe <small>(code fourni par le formateur)</small></label>
      <div class="row" style="margin-top:0"><input name="code" required style="flex:1;text-transform:uppercase"><button class="btn sm">Rejoindre</button></div></form>` : ""}
  </div>`;
  const f = $("#f");
  if (f) f.onsubmit = async ev => {
    ev.preventDefault();
    const code = new FormData(f).get("code").trim().toUpperCase();
    try {
      const c = await getDoc(doc(db, "codes", code));
      if (!c.exists()) { toast("Code inconnu"); return; }
      await updateDoc(doc(db, "users", user.uid), { classe: c.data().classe });
      profil.classe = c.data().classe; toast("Classe rejointe ✓"); rendre();
    } catch (e) { toast(erreurFr(e)); }
  };
}

// ======================================================================= Démarrage
onAuthStateChanged(auth, async u => {
  user = u; profil = null; vue = null; edition = null;
  document.body.classList.toggle("connecte", !!u);
  if (!u) { vueConnexion(); return; }
  if (inscription) return; // l'inscription termine elle-même le chargement du profil
  racine.innerHTML = `<p class="sub" style="padding:40px 0">Chargement du profil…</p>`;
  try { await chargerProfil(); }
  catch (e) { racine.innerHTML = `<div class="note warn" style="margin-top:30px">${E(erreurFr(e))}</div>`; }
});
