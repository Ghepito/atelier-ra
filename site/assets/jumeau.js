// Atelier RA — jumeau numérique 3D (three.js)
// Repère = repère machine de l'appli, en cm : origine au centre du marqueur AV,
// x vers la droite vu de face, y vers le haut, z vers l'opérateur.
import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";

const cacheModeles = {};
export async function chargerModele(id) {
  if (id in cacheModeles) return cacheModeles[id];
  try {
    const r = await fetch(`data/modeles/${id}.json`, { cache: "no-store" });
    cacheModeles[id] = r.ok ? await r.json() : null;
  } catch { cacheModeles[id] = null; }
  return cacheModeles[id];
}

function texteNumero(n, fond, encre) {
  const c = document.createElement("canvas"); c.width = c.height = 128;
  const g = c.getContext("2d");
  g.beginPath(); g.arc(64, 64, 56, 0, Math.PI * 2); g.fillStyle = fond; g.fill();
  g.lineWidth = 8; g.strokeStyle = "#1d1400"; g.stroke();
  g.fillStyle = encre; g.font = "bold 66px Archivo, sans-serif"; g.textAlign = "center"; g.textBaseline = "middle";
  g.fillText(String(n), 64, 68);
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
}

/**
 * Crée la vue 3D dans `el`.
 * opts : { modele, points: [{x,y,z,ou}], editable, onPlace(i, {x,y,z}), onSelect(i) }
 */
export function creerJumeau(el, opts) {
  const modele = opts.modele;
  el.style.position = "relative";
  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
  renderer.setSize(el.clientWidth, el.clientHeight);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  el.appendChild(renderer.domElement);
  renderer.domElement.style.display = "block";
  renderer.domElement.style.borderRadius = "12px";

  const info = document.createElement("div");
  info.style.cssText = "position:absolute;left:12px;bottom:12px;padding:6px 10px;border-radius:8px;background:rgba(15,20,24,.82);color:#fff;font:600 13px Archivo,sans-serif;pointer-events:none;display:none";
  el.appendChild(info);

  const scene = new THREE.Scene();
  scene.background = new THREE.Color("#E6EBEE");
  const camera = new THREE.PerspectiveCamera(38, el.clientWidth / el.clientHeight, 1, 5000);
  const cible = new THREE.Vector3(...(modele.centre || [0, 30, -56]));
  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true; controls.target.copy(cible);
  controls.maxPolarAngle = Math.PI * 0.52;

  scene.add(new THREE.HemisphereLight(0xffffff, 0x8a949b, 1.7));
  const sun = new THREE.DirectionalLight(0xffffff, 1.9); sun.position.set(-220, 320, 260); scene.add(sun);
  const grid = new THREE.GridHelper(500, 50, 0x9aa5ad, 0xc9d1d6); grid.position.y = modele.sol ?? -75; scene.add(grid);

  // ---- Pièces de la maquette
  const pickables = [];
  for (const p of modele.pieces) {
    let geo;
    if (p.t === "box") geo = new THREE.BoxGeometry(p.s[0], p.s[1], p.s[2]);
    else if (p.t === "cyl") geo = new THREE.CylinderGeometry(p.r, p.r, p.h, 32);
    else if (p.t === "cone") geo = new THREE.CylinderGeometry(p.r1, p.r2, p.h, 32);
    else continue;
    const op = p.op ?? 1;
    const mat = new THREE.MeshStandardMaterial({ color: p.col, roughness: 0.6, metalness: 0.05, transparent: op < 1, opacity: op, depthWrite: op >= 1 });
    const m = new THREE.Mesh(geo, mat);
    m.position.set(p.c[0], p.c[1], p.c[2]);
    if (p.t === "cyl" && p.axe === "x") m.rotation.z = Math.PI / 2;
    if (p.t === "cyl" && p.axe === "z") m.rotation.x = Math.PI / 2;
    m.userData.nom = p.nom;
    if (p.aretes) m.add(new THREE.LineSegments(new THREE.EdgesGeometry(geo), new THREE.LineBasicMaterial({ color: p.aretes })));
    scene.add(m);
    if (op >= 0.5) pickables.push(m); // le carter transparent ne bloque pas les clics
  }
  // ---- Marqueurs (images réelles)
  const loader = new THREE.TextureLoader();
  for (const mk of modele.marqueurs || []) {
    const tex = loader.load(mk.image); tex.colorSpace = THREE.SRGBColorSpace;
    const pl = new THREE.Mesh(new THREE.PlaneGeometry(mk.taille, mk.taille), new THREE.MeshBasicMaterial({ map: tex }));
    pl.position.set(mk.c[0], mk.c[1], mk.c[2]);
    if (mk.face === "arriere") pl.rotation.y = Math.PI;
    if (mk.face === "gauche") pl.rotation.y = -Math.PI / 2;
    if (mk.face === "droite") pl.rotation.y = Math.PI / 2;
    if (mk.face === "dessus") pl.rotation.x = -Math.PI / 2;
    pl.userData.nom = "Marqueur " + (mk.face === "arriere" ? "arrière" : mk.face === "avant" ? "avant (origine)" : mk.face);
    scene.add(pl); pickables.push(pl);
  }

  // ---- Repères
  const groupePins = new THREE.Group(); scene.add(groupePins);
  let points = opts.points || [], selection = -1, placement = -1;
  const pinMeshes = [];
  function dessinerPins() {
    groupePins.clear(); pinMeshes.length = 0;
    points.forEach((p, i) => {
      if (!p) return;
      const sel = i === selection || i === placement;
      const coul = i === placement ? "#FF6B4A" : sel ? "#FFFFFF" : "#FFB020";
      const g = new THREE.Group(); g.position.set(p.x, p.y, p.z);
      const mat = new THREE.MeshStandardMaterial({ color: coul, emissive: coul, emissiveIntensity: 0.35 });
      const pointe = new THREE.Mesh(new THREE.ConeGeometry(2, 6, 16), mat); pointe.rotation.x = Math.PI; pointe.position.y = 3;
      const tige = new THREE.Mesh(new THREE.CylinderGeometry(0.4, 0.4, 10, 8), mat); tige.position.y = 11;
      const spr = new THREE.Sprite(new THREE.SpriteMaterial({ map: texteNumero(i + 1, coul, "#1d1400"), depthTest: false }));
      spr.scale.set(sel ? 10 : 7, sel ? 10 : 7, 1); spr.position.y = 21; spr.renderOrder = 10;
      g.add(pointe, tige, spr);
      [pointe, tige, spr].forEach(o => { o.userData.pin = i; pinMeshes.push(o); });
      groupePins.add(g);
    });
  }
  dessinerPins();

  // ---- Interaction
  const ray = new THREE.Raycaster(); const souris = new THREE.Vector2();
  let down = null;
  function viser(ev) {
    const r = renderer.domElement.getBoundingClientRect();
    souris.set(((ev.clientX - r.left) / r.width) * 2 - 1, -((ev.clientY - r.top) / r.height) * 2 + 1);
    ray.setFromCamera(souris, camera);
  }
  renderer.domElement.addEventListener("pointerdown", ev => { down = [ev.clientX, ev.clientY]; });
  renderer.domElement.addEventListener("pointermove", ev => {
    viser(ev);
    const hp = ray.intersectObjects(pinMeshes, false)[0];
    const hit = hp || ray.intersectObjects(pickables, false)[0];
    if (hit) {
      info.style.display = "block";
      info.textContent = hp ? `${hp.object.userData.pin + 1} · ${points[hp.object.userData.pin]?.ou || ""}` : hit.object.userData.nom;
      renderer.domElement.style.cursor = placement >= 0 && !hp ? "crosshair" : "pointer";
    } else { info.style.display = "none"; renderer.domElement.style.cursor = placement >= 0 ? "crosshair" : "grab"; }
  });
  renderer.domElement.addEventListener("pointerup", ev => {
    if (!down || Math.hypot(ev.clientX - down[0], ev.clientY - down[1]) > 5) { down = null; return; }
    down = null; viser(ev);
    if (placement >= 0 && opts.editable) {
      const hit = ray.intersectObjects(pickables, false)[0];
      if (hit) {
        const r = v => Math.round(v * 2) / 2;
        const pos = { x: r(hit.point.x), y: r(hit.point.y), z: r(hit.point.z) };
        const i = placement; placement = -1;
        points[i] = { ...(points[i] || {}), ...pos };
        selection = i; dessinerPins();
        opts.onPlace && opts.onPlace(i, pos, hit.object.userData.nom);
      }
      return;
    }
    const hp = ray.intersectObjects(pinMeshes, false)[0];
    if (hp) { selection = hp.object.userData.pin; dessinerPins(); opts.onSelect && opts.onSelect(selection); }
  });

  // ---- Vues
  function vue(nom) {
    const d = 460, [cx, cy, cz] = cible.toArray();
    const rel = { iso: [-250, 130, 320], face: [0, 10, d], gauche: [-d, 10, 0], droite: [d, 10, 0], dessus: [0, d + 60, 6], arriere: [0, 10, -d] }[nom] || [-250, 130, 320];
    camera.position.set(cx + rel[0], cy + rel[1], cz + rel[2]); controls.target.copy(cible); controls.update();
  }
  vue("iso");

  const ro = new ResizeObserver(() => {
    const w = el.clientWidth, h = el.clientHeight; if (!w || !h) return;
    renderer.setSize(w, h); camera.aspect = w / h; camera.updateProjectionMatrix();
  });
  ro.observe(el);

  let actif = true;
  (function boucle() { if (!actif) return; controls.update(); renderer.render(scene, camera); requestAnimationFrame(boucle); })();

  return {
    vue,
    setPoints(p) { points = p; dessinerPins(); },
    setSelection(i) { selection = i; dessinerPins(); },
    placer(i) { placement = i; selection = i; dessinerPins(); },
    annulerPlacement() { placement = -1; dessinerPins(); },
    dispose() { actif = false; ro.disconnect(); controls.dispose(); renderer.dispose(); el.innerHTML = ""; },
  };
}
