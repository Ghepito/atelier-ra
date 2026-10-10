package fr.atelier.ra

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import fr.atelier.ra.common.CameraPermissionHelper
import fr.atelier.ra.common.DisplayRotationHelper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), ArEvents {

    // Palette
    private val BG = Color.parseColor("#0F1418")
    private val PANEL = Color.parseColor("#F2101A1F")
    private val LINE = Color.parseColor("#2A343C")
    private val TXT = Color.parseColor("#EEF2F4")
    private val MUT = Color.parseColor("#9AA7B0")
    private val ACC = Color.parseColor("#FFB020")
    private val OK = Color.parseColor("#3CCF8E")
    private val SPACE = Color.parseColor("#4AA8FF")
    private val WARN = Color.parseColor("#FF6B4A")
    private val DARK = Color.parseColor("#1D1400")

    private val DEPOT = "Ghepito/atelier-ra"

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var rotationHelper: DisplayRotationHelper
    private lateinit var machines: Map<String, Machine>
    private lateinit var store: Store

    private lateinit var top: LinearLayout
    private lateinit var status: TextView
    private lateinit var prox: TextView
    private lateinit var hint: TextView
    private lateinit var banner: TextView
    private lateinit var arrows: ArrowView
    private lateinit var panel: LinearLayout
    private lateinit var fab: TextView
    private lateinit var code: TextView
    private lateinit var name: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var tabs: List<TextView>
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView

    private var session: Session? = null
    private var installRequested = false
    private var current: String? = null
    private var tab = 0
    private var replie = false
    private var masque = false
    private var modePlacement = false
    private var etat: Etat = Etat.INIT
    /** Étape courante de la procédure de sécurité, par machine */
    private val etapes = HashMap<String, Int>()

    private val prefs by lazy { getSharedPreferences("atelier-ra", Context.MODE_PRIVATE) }
    private val tailles = listOf(5, 8, 12, 18)
    private val rayons = listOf(0.5f, 1f, 2f, 3f, 5f, 10f)
    private val rayon get() = prefs.getFloat("rayon_m", 2f)

    private val horloge = Handler(Looper.getMainLooper())
    private var dernierBloc = -2
    private val tic = object : Runnable {
        override fun run() {
            val n = renderer.proches.count { it.distance <= rayon }
            prox.text = "⌖ $n"
            if (!masque && tab == 0 && renderer.animation != null && renderer.blocActif != dernierBloc) {
                dernierBloc = renderer.blocActif
                afficher()
            }
            horloge.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        machines = Machines.load(this).associateBy { it.key }
        store = Store(this, machines)
        rotationHelper = DisplayRotationHelper(this)
        renderer = ArRenderer(this, machines, store, rotationHelper, this)
        setContentView(buildUi())
        verifierMiseAJour()
    }

    // ------------------------------------------------------------------ Événements du rendu (fil OpenGL)

    override fun etat(etat: Etat, machine: String?, message: String?) = runOnUiThread { majEtat(etat, machine, message) }

    override fun marqueurRetrouve(machine: String) = runOnUiThread {
        val premier = current == null
        if (machine != current) choisirMachine(machine)
        if (masque || premier) montrerPanneau()
    }

    override fun repereTouche(machine: String, index: Int) = runOnUiThread {
        if (machine != current) choisirMachine(machine)
        renderer.selection = Pair(machine, index)
        tab = 1; replie = false
        montrerPanneau()
        afficher()
    }

    override fun repereplace(machine: String, index: Int, ok: Boolean) = runOnUiThread {
        if (ok) {
            renderer.placement = null
            Toast.makeText(this, "Point ${index + 1} placé", Toast.LENGTH_SHORT).show()
            montrerPanneau()
            majAide()
        } else {
            Toast.makeText(this, "Surface non reconnue à cet endroit : balayez la zone quelques secondes puis réessayez.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ Outils d'interface

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Int) = dp(v.toFloat()).toInt()

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius)
        if (stroke != null) setStroke(dpi(1), stroke)
    }

    private fun text(s: String, size: Float, color: Int = TXT, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun bouton(s: String, fond: Int, texte: Int, onClick: () -> Unit) = text(s, 14f, texte, true).apply {
        gravity = Gravity.CENTER
        setPadding(dpi(14), dpi(10), dpi(14), dpi(10))
        background = rounded(fond, 10f)
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dpi(top); bottomMargin = dpi(bottom) }

    // ------------------------------------------------------------------ Construction de l'interface

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_UP) renderer.toucher(e.x, e.y)
                true
            }
        }
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        arrows = ArrowView(this, { renderer.cible }, { (renderer.placement ?: renderer.selection)?.second?.plus(1) })
        root.addView(arrows, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Barre du haut
        val haut = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(36), dpi(12), dpi(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT))
        }
        top.addView(text("ATELIER RA", 15f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        status = text("Démarrage…", 12f, MUT).apply {
            setPadding(dpi(10), dpi(4), dpi(10), dpi(4))
            background = rounded(Color.argb(40, 255, 255, 255), 99f)
        }
        prox = text("⌖ 0", 13f, TXT, true).apply {
            setPadding(dpi(10), dpi(4), dpi(10), dpi(4))
            background = rounded(Color.argb(40, 255, 255, 255), 99f)
            setOnClickListener { listeProximite() }
        }
        top.addView(prox, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dpi(6) })
        top.addView(status)
        val gear = text("⚙", 18f).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(40, 255, 255, 255), 99f)
            setOnClickListener { reglages() }
        }
        top.addView(gear, LinearLayout.LayoutParams(dpi(34), dpi(34)).apply { leftMargin = dpi(8) })
        haut.addView(top)
        banner = text("", 13f, DARK, true).apply {
            setPadding(dpi(14), dpi(9), dpi(14), dpi(9))
            background = rounded(ACC, 10f)
            visibility = View.GONE
        }
        haut.addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = dpi(12); rightMargin = dpi(12) })
        root.addView(haut, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // Aide centrale
        hint = text("Visez un marqueur imprimé", 15f).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(24), 0, dpi(24), 0)
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
        }
        root.addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        // Bouton flottant (panneau masqué)
        fab = text("", 14f, DARK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(16), dpi(12), dpi(18), dpi(12))
            background = rounded(ACC, 99f)
            elevation = dp(6f)
            visibility = View.GONE
            setOnClickListener { montrerPanneau() }
        }
        root.addView(fab, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dpi(16); bottomMargin = dpi(28)
        })

        // Panneau d'information
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadii = floatArrayOf(dp(18f), dp(18f), dp(18f), dp(18f), 0f, 0f, 0f, 0f)
            }
            setPadding(dpi(16), dpi(8), dpi(16), dpi(16))
            visibility = View.GONE
        }
        val grip = View(this).apply { background = rounded(LINE, 2f) }
        panel.addView(grip, LinearLayout.LayoutParams(dpi(40), dpi(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dpi(8) })

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { replie = !replie; appliquerRepli() }
        }
        code = text("", 11f, ACC, true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dpi(6), dpi(2), dpi(6), dpi(2))
            background = rounded(Color.TRANSPARENT, 5f, ACC)
        }
        name = text("", 16f, bold = true).apply { setPadding(dpi(10), 0, dpi(8), 0); maxLines = 1 }
        head.addView(code)
        head.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val hide = text("Masquer ▾", 12f, MUT, true).apply {
            setPadding(dpi(10), dpi(6), dpi(10), dpi(6))
            background = rounded(Color.TRANSPARENT, 99f, LINE)
            setOnClickListener { masquerPanneau() }
        }
        head.addView(hide)
        panel.addView(head)

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(10), 0, dpi(10)) }
        tabs = listOf("Fonction", "Entretien", "Sécurité", "Journal").mapIndexed { i, label ->
            text(label, 12.5f, MUT, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dpi(9), 0, dpi(9))
                setOnClickListener { tab = i; replie = false; appliquerRepli(); afficher() }
            }.also {
                tabRow.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dpi(5) })
            }
        }
        panel.addView(tabRow)

        scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        val maxH = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, maxH))

        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        // Zones à éviter pour la flèche
        root.viewTreeObserver.addOnGlobalLayoutListener {
            arrows.margeHaut = haut.height.toFloat()
            arrows.margeBas = if (panel.visibility == View.VISIBLE) panel.height.toFloat() else dp(80f)
        }
        return root
    }

    // ------------------------------------------------------------------ Panneau : masquer / montrer

    private fun masquerPanneau() {
        masque = true
        panel.visibility = View.GONE
        majFab()
    }

    private fun montrerPanneau() {
        if (current == null) return
        masque = false
        panel.visibility = View.VISIBLE
        appliquerRepli()
        majFab()
        afficher()
    }

    private fun majFab() {
        val m = machines[current]
        fab.text = if (m != null) "ⓘ  ${m.code}" else ""
        fab.visibility = if (masque && m != null) View.VISIBLE else View.GONE
    }

    private fun appliquerRepli() {
        val v = if (replie) View.GONE else View.VISIBLE
        scroll.visibility = v
        tabRow.visibility = v
    }

    private fun choisirMachine(key: String) {
        current = key
        renderer.selection = null
        renderer.placement = null
        majFab()
        afficher()
        majAide()
    }

    private fun majEtat(e: Etat, key: String?, msg: String?) {
        etat = e
        val (label, bg, fg) = when (e) {
            Etat.INIT -> Triple(msg ?: "Initialisation…", Color.argb(40, 255, 255, 255), MUT)
            Etat.RECHERCHE -> Triple("Recherche…", Color.argb(40, 255, 255, 255), MUT)
            Etat.MARQUEUR -> Triple("Marqueur suivi", OK, Color.parseColor("#06251A"))
            Etat.ESPACE -> Triple("Suivi par l'espace", SPACE, Color.parseColor("#04213D"))
        }
        status.text = label
        status.setTextColor(fg)
        status.background = rounded(bg, 99f)

        if (key != null && key != current) {
            val premier = current == null
            choisirMachine(key)
            if (premier) montrerPanneau()
        }
        majAide(msg)
    }

    private fun majAide(msg: String? = null) {
        val plc = renderer.placement
        when {
            plc != null -> {
                hint.visibility = View.VISIBLE
                hint.setTextColor(ACC)
                hint.text = "Touchez l'endroit réel du point ${plc.second + 1}\n(balayez d'abord la zone pour qu'elle soit reconnue)"
            }
            etat == Etat.MARQUEUR || etat == Etat.ESPACE -> hint.visibility = View.GONE
            else -> {
                hint.visibility = View.VISIBLE
                hint.setTextColor(TXT)
                hint.text = if (etat == Etat.INIT) (msg ?: "Balayez doucement la pièce") else "Visez un marqueur imprimé"
            }
        }
    }

    // ------------------------------------------------------------------ Contenu des onglets

    private fun afficher() {
        val m = machines[current] ?: return
        code.text = m.code
        name.text = m.nom
        tabs.forEachIndexed { i, t ->
            val on = i == tab
            t.setTextColor(if (on) DARK else MUT)
            t.background = if (on) rounded(ACC, 10f) else rounded(Color.TRANSPARENT, 10f, LINE)
        }
        content.removeAllViews()
        when (tab) {
            0 -> ongletFonction(m)
            1 -> ongletEntretien(m)
            2 -> ongletSecurite(m)
            else -> ongletJournal(m)
        }
    }

    private val couleursBlocs = intArrayOf(
        Color.rgb(255, 107, 74), Color.rgb(255, 176, 32), Color.rgb(245, 230, 99), Color.rgb(60, 207, 142), Color.rgb(74, 168, 255))
    private val couleursInfo = intArrayOf(
        Color.rgb(74, 168, 255), Color.rgb(158, 128, 255), Color.rgb(51, 217, 230), Color.rgb(242, 115, 217))
    /** Chaîne affichée, par machine */
    private val chaineChoisie = HashMap<String, Int>()

    private fun ongletFonction(m: Machine) {
        if (m.chaines.isEmpty()) {
            content.addView(text("Aucune chaîne décrite pour cet équipement.", 14f, MUT)); return
        }
        val idx = (chaineChoisie[m.key] ?: 0).coerceIn(0, m.chaines.size - 1)
        val ch = m.chaines[idx]

        // Sélecteur de chaîne (défilement horizontal)
        if (m.chaines.size > 1) {
            val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            m.chaines.forEachIndexed { i, c ->
                val on = i == idx
                val coul = if (c.acquisition) SPACE else ACC
                row.addView(text((if (c.acquisition) "◉ " else "⚡ ") + c.titre, 12.5f, if (on) DARK else TXT, true).apply {
                    setPadding(dpi(12), dpi(7), dpi(12), dpi(7))
                    background = if (on) rounded(coul, 99f) else rounded(Color.TRANSPARENT, 99f, LINE)
                    setOnClickListener {
                        chaineChoisie[m.key] = i
                        if (renderer.animation?.first == m.key) renderer.animation = Pair(m.key, i)
                        dernierBloc = -2
                        afficher()
                    }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dpi(6) })
            }
            hs.addView(row)
            content.addView(hs, lp(bottom = 8))
        }

        val anime = renderer.animation?.let { it.first == m.key && it.second == idx } == true
        if (ch.maillons.isNotEmpty()) {
            content.addView(bouton(if (anime) "■  Arrêter l'animation" else "▶  Voir la chaîne sur la machine",
                if (anime) Color.argb(30, 255, 255, 255) else (if (ch.acquisition) SPACE else ACC), if (anime) TXT else DARK) {
                renderer.animation = if (anime) null else Pair(m.key, idx)
                dernierBloc = -2
                afficher()
            }, lp(bottom = 8))
            if (anime) content.addView(text(
                if (ch.acquisition) "L'information circule du capteur vers la partie commande ; le bloc parcouru s'allume ci-dessous."
                else "L'énergie circule de maillon en maillon sur la machine ; le bloc parcouru s'allume ci-dessous.", 12.5f, MUT), lp(bottom = 6))
        }
        if (ch.intro.isNotBlank()) content.addView(text(ch.intro, 14f, MUT).apply { setPadding(0, 0, 0, dpi(8)) })
        val actif = if (anime) renderer.blocActif else -1
        val pal = if (ch.acquisition) couleursInfo else couleursBlocs
        ch.blocs.forEachIndexed { i, b ->
            if (i > 0) content.addView(text("▼", 11f, MUT).apply { gravity = Gravity.CENTER })
            val coul = pal[i % pal.size]
            val on = i == actif
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(12), dpi(8), dpi(10), dpi(8))
                background = if (on) rounded(Color.argb(60, Color.red(coul), Color.green(coul), Color.blue(coul)), 8f, coul)
                             else rounded(Color.argb(20, Color.red(coul), Color.green(coul), Color.blue(coul)), 8f, LINE)
            }
            val organes = ch.maillons.filter { it.bloc == i }.joinToString(", ") { it.nom }
            card.addView(text(b.titre.uppercase() + if (organes.isNotEmpty()) "  ·  $organes" else "", 12f, coul, true))
            card.addView(text(b.texte, 14f))
            content.addView(card, lp(top = 2, bottom = 2))
        }
    }

    private fun barre(fait: Int, total: Int, couleur: Int): View {
        val f = FrameLayout(this).apply { background = rounded(Color.argb(30, 255, 255, 255), 99f) }
        val v = View(this).apply { background = rounded(couleur, 99f) }
        f.addView(v, FrameLayout.LayoutParams(0, MATCH_PARENT))
        f.post {
            v.layoutParams = FrameLayout.LayoutParams((f.width * fait / maxOf(1, total)), MATCH_PARENT)
        }
        return f
    }

    private fun ongletEntretien(m: Machine) {
        val der = store.derniere(m.key, "Tournée")
        content.addView(text(if (der != null) "Dernière tournée : ${Store.date(der.date)}" else "Aucune tournée enregistrée", 13f, MUT))
        val n = store.nbFaits(m)
        content.addView(text("Tournée en cours : $n / ${m.entretien.size} points", 14f, bold = true), lp(top = 8, bottom = 4))
        content.addView(barre(n, m.entretien.size, OK), lp(h = dpi(6), bottom = 8))
        content.addView(text(
            if (modePlacement) "Mode placement : touchez 📍 puis l'endroit réel du point."
            else "Touchez un point pour le repérer · touchez ☐ quand il est fait.", 12.5f, MUT), lp(bottom = 4))

        val sel = renderer.selection
        m.entretien.forEachIndexed { i, p ->
            val isSel = sel != null && sel.first == m.key && sel.second == i
            val fait = store.estFait(m.key, i)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(8), dpi(8), dpi(6), dpi(8))
                background = if (isSel) rounded(Color.argb(30, 255, 176, 32), 10f, ACC) else rounded(Color.TRANSPARENT, 10f)
                setOnClickListener {
                    renderer.selection = if (isSel) null else Pair(m.key, i)
                    afficher()
                }
            }
            val num = text("${i + 1}", 13f, DARK, true).apply {
                gravity = Gravity.CENTER
                background = rounded(if (fait) OK else ACC, 99f)
            }
            row.addView(num, LinearLayout.LayoutParams(dpi(26), dpi(26)).apply { rightMargin = dpi(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(p.ou, 14f, bold = true))
            col.addView(text(p.action, 13f, MUT))
            val tag = (if (p.type.isNotBlank()) p.type.uppercase() + "  ·  " else "") + "⟳ ${p.periode}" + if (store.estPlace(m.key, i)) "  ·  📍 placé" else ""
            col.addView(text(tag, 11f, TXT).apply {
                setPadding(dpi(7), dpi(1), dpi(7), dpi(1))
                background = rounded(Color.argb(26, 255, 255, 255), 99f)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dpi(3) })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            if (modePlacement) {
                val enCours = renderer.placement?.let { it.first == m.key && it.second == i } == true
                row.addView(text("📍", 18f).apply {
                    gravity = Gravity.CENTER
                    background = rounded(if (enCours) WARN else Color.argb(30, 255, 255, 255), 10f)
                    setOnClickListener {
                        renderer.placement = if (enCours) null else Pair(m.key, i)
                        if (!enCours) masquerPanneau()
                        afficher(); majAide()
                    }
                }, LinearLayout.LayoutParams(dpi(42), dpi(42)).apply { leftMargin = dpi(6) })
            }
            row.addView(text(if (fait) "✓" else "", 20f, DARK, true).apply {
                gravity = Gravity.CENTER
                background = if (fait) rounded(OK, 10f) else rounded(Color.TRANSPARENT, 10f, MUT)
                setOnClickListener { store.basculer(m.key, i); afficher() }
            }, LinearLayout.LayoutParams(dpi(42), dpi(42)).apply { leftMargin = dpi(6) })
            content.addView(row)
        }

        if (n > 0) {
            content.addView(bouton("Valider la tournée ($n/${m.entretien.size})", OK, Color.parseColor("#06251A")) {
                store.validerTournee(m)
                Toast.makeText(this, "Tournée enregistrée dans le journal", Toast.LENGTH_SHORT).show()
                afficher()
            }, lp(top = 10))
        }
    }

    private fun ongletSecurite(m: Machine) {
        val cur = etapes[m.key] ?: 0
        content.addView(text(m.securiteTitre, 14f, bold = true))
        content.addView(text("Étape ${minOf(cur + 1, m.etapes.size)} / ${m.etapes.size}" + if (cur >= m.etapes.size) " — terminé" else "", 12.5f, MUT), lp(top = 2, bottom = 4))
        content.addView(barre(cur, m.etapes.size, WARN), lp(h = dpi(6), bottom = 8))

        m.etapes.forEachIndexed { i, e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpi(8), dpi(9), dpi(8), dpi(9))
                background = when {
                    i == cur -> rounded(Color.argb(28, 255, 107, 74), 10f, WARN)
                    else -> rounded(Color.TRANSPARENT, 10f)
                }
                alpha = if (i > cur) 0.45f else 1f
            }
            val num = text(if (i < cur) "✓" else "${i + 1}", 13f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = rounded(if (i < cur) OK else WARN, 99f)
            }
            row.addView(num, LinearLayout.LayoutParams(dpi(26), dpi(26)).apply { rightMargin = dpi(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(e.texte, 14f, bold = i == cur))
            if (e.repere != null && e.repere in m.entretien.indices) {
                col.addView(text("→ repère ${e.repere + 1} : ${m.entretien[e.repere].ou}", 12f, ACC).apply {
                    setPadding(0, dpi(3), 0, 0)
                    setOnClickListener { renderer.selection = Pair(m.key, e.repere); afficher() }
                })
            }
            if (i == cur) {
                col.addView(bouton("Fait ✓", WARN, Color.WHITE) {
                    etapes[m.key] = cur + 1
                    val next = m.etapes.getOrNull(cur + 1)
                    renderer.selection = next?.repere?.let { Pair(m.key, it) }
                    afficher()
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dpi(8) })
            }
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            content.addView(row)
        }

        if (cur >= m.etapes.size) {
            val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            ligne.addView(bouton("Enregistrer au journal", OK, Color.parseColor("#06251A")) {
                store.ajouter(m.key, Entree(System.currentTimeMillis(), "Procédure : ${m.securiteTitre}",
                    m.etapes.joinToString("\n") { "✓ " + it.texte }))
                etapes[m.key] = 0
                renderer.selection = null
                Toast.makeText(this, "Procédure enregistrée dans le journal", Toast.LENGTH_SHORT).show()
                afficher()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            ligne.addView(bouton("Recommencer", Color.argb(30, 255, 255, 255), TXT) {
                etapes[m.key] = 0
                renderer.selection = m.etapes.firstOrNull()?.repere?.let { Pair(m.key, it) }
                afficher()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dpi(8) })
            content.addView(ligne, lp(top = 10))
        }

        content.addView(text("⚠ ${m.note}", 12f, MUT).apply {
            setPadding(dpi(10), dpi(8), dpi(10), dpi(8))
            background = GradientDrawable().apply { cornerRadius = dp(8f); setStroke(dpi(1), WARN, dp(4f), dp(3f)) }
        }, lp(top = 12))
    }

    private fun ongletJournal(m: Machine) {
        val j = store.journal(m.key)
        if (j.isEmpty()) {
            content.addView(text("Aucune intervention enregistrée pour cet équipement.\nValidez une tournée (onglet Entretien) ou une procédure (onglet Sécurité) pour remplir le journal.", 14f, MUT))
            return
        }
        content.addView(bouton("Partager le journal", Color.argb(30, 255, 255, 255), TXT) {
            val txt = "Journal ${m.code} — ${m.nom}\n\n" + j.joinToString("\n\n") { "${Store.date(it.date)} — ${it.titre}\n${it.detail}" }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Journal ${m.code}"); putExtra(Intent.EXTRA_TEXT, txt)
            }, "Partager le journal"))
        }, lp(bottom = 8))
        j.forEach { e ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(12), dpi(8), dpi(10), dpi(8))
                background = rounded(Color.argb(14, 255, 255, 255), 8f, LINE)
            }
            card.addView(text(Store.date(e.date), 11.5f, ACC, true))
            card.addView(text(e.titre, 14f, bold = true))
            if (e.detail.isNotBlank()) card.addView(text(e.detail, 12.5f, MUT))
            content.addView(card, lp(top = 3, bottom = 3))
        }
    }

    // ------------------------------------------------------------------ Réglages

    private fun reglages() {
        val taille = prefs.getInt("taille_cm", 8)
        val items = arrayOf(
            "Taille des marqueurs table / bureau : $taille cm",
            "Rayon de la liste « À proximité » : ${fmtM(rayon)}",
            if (modePlacement) "Mode placement des repères : ACTIVÉ" else "Mode placement des repères : désactivé",
            "Exporter les positions (fichier machines.json)",
            "Effacer les positions placées",
            "Réinitialiser le suivi",
        )
        AlertDialog.Builder(this)
            .setTitle("Réglages — version ${BuildConfig.VERSION_NAME}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> choisirTaille()
                    1 -> choisirRayon()
                    2 -> {
                        modePlacement = !modePlacement
                        if (!modePlacement) renderer.placement = null
                        tab = 1; replie = false
                        montrerPanneau(); majAide()
                        if (modePlacement) Toast.makeText(this, "Onglet Entretien : touchez 📍 à côté d'un point, puis l'endroit réel.", Toast.LENGTH_LONG).show()
                    }
                    3 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "machines.json — Atelier RA")
                        putExtra(Intent.EXTRA_TEXT, store.exporterJson(this@MainActivity))
                    }, "Exporter les positions"))
                    4 -> AlertDialog.Builder(this)
                        .setMessage("Revenir aux positions du fichier pour tous les repères ?")
                        .setPositiveButton("Effacer") { _, _ -> store.effacerPositions(); afficher() }
                        .setNegativeButton("Annuler", null).show()
                    5 -> recreate()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun fmtM(d: Float) = if (d < 1f) "${(d * 100).toInt()} cm" else String.format(Locale.FRANCE, "%.1f m", d).replace(",0 m", " m")

    private fun choisirRayon() {
        AlertDialog.Builder(this)
            .setTitle("Rayon de la liste « À proximité »")
            .setSingleChoiceItems(rayons.map { fmtM(it) }.toTypedArray(), rayons.indexOf(rayon).coerceAtLeast(0)) { d, which ->
                prefs.edit().putFloat("rayon_m", rayons[which]).apply()
                d.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Liste de tous les repères localisés à moins de `rayon`, du plus proche au plus lointain. */
    private fun listeProximite() {
        val r = rayon
        val liste = renderer.proches.filter { it.distance <= r }.sortedBy { it.distance }
        val connues = renderer.proches.map { it.machine }.distinct().size
        val titre = "À moins de ${fmtM(r)} : ${liste.size} point" + if (liste.size > 1) "s" else ""
        val b = AlertDialog.Builder(this).setTitle(titre)
        if (liste.isEmpty()) {
            b.setMessage(if (connues == 0) "Aucun équipement localisé pour l'instant : visez un marqueur."
                         else "Aucun point dans ce rayon. Rapprochez-vous ou augmentez le rayon dans ⚙.")
        } else {
            val items = liste.map { p ->
                val m = machines[p.machine]!!
                val pt = m.entretien[p.index]
                val etat = if (store.estFait(m.key, p.index)) "  ✓ fait" else ""
                "${p.index + 1} · ${pt.ou}\n${m.code} · ${fmtM(p.distance)} · ⟳ ${pt.periode}$etat"
            }.toTypedArray()
            b.setItems(items) { _, which ->
                val p = liste[which]
                if (p.machine != current) choisirMachine(p.machine)
                renderer.selection = Pair(p.machine, p.index)
                tab = 1; replie = false
                montrerPanneau()
            }
        }
        b.setNegativeButton("Fermer", null)
        if (connues > 0) b.setNeutralButton("Rayon…") { _, _ -> choisirRayon() }
        b.show()
    }

    private fun choisirTaille() {
        val actuelle = prefs.getInt("taille_cm", 8)
        AlertDialog.Builder(this)
            .setTitle("Taille des marqueurs imprimés")
            .setSingleChoiceItems(tailles.map { "$it cm" }.toTypedArray(), tailles.indexOf(actuelle).coerceAtLeast(0)) { d, which ->
                prefs.edit().putInt("taille_cm", tailles[which]).apply()
                d.dismiss()
                recreate()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ------------------------------------------------------------------ Mise à jour

    private fun verifierMiseAJour() {
        thread(isDaemon = true) {
            try {
                val c = URL("https://api.github.com/repos/$DEPOT/releases/tags/derniere-version").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val version = Regex("version (\\d+)").find(json.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: return@thread
                if (version <= BuildConfig.VERSION_CODE) return@thread
                val assets = json.getJSONArray("assets")
                var url = "https://github.com/$DEPOT/releases/latest"
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                runOnUiThread {
                    banner.text = "⬇  Nouvelle version $version disponible — touchez pour la télécharger"
                    banner.visibility = View.VISIBLE
                    banner.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            } catch (e: Exception) {
                Log.i("AtelierRA", "Vérification de mise à jour impossible : ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ Cycle de vie ARCore

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    else -> {}
                }
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }
                val s = Session(this)
                configurer(s)
                session = s
                renderer.session = s
            } catch (e: Exception) {
                val msg = when (e) {
                    is UnavailableArcoreNotInstalledException, is UnavailableUserDeclinedInstallationException ->
                        "Installez « Services Google Play pour la RA » depuis le Play Store."
                    is UnavailableApkTooOldException -> "Mettez à jour « Services Google Play pour la RA »."
                    is UnavailableSdkTooOldException -> "Cette appli est trop ancienne pour ce téléphone."
                    is UnavailableDeviceNotCompatibleException -> "Ce téléphone n'est pas compatible ARCore."
                    else -> "Impossible de démarrer la RA : ${e.message}"
                }
                erreur(msg)
                Log.e("AtelierRA", "Création de session", e)
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            erreur("Caméra indisponible. Fermez les autres applis qui l'utilisent puis relancez.")
            session = null
            renderer.session = null
            return
        }
        surface.onResume()
        rotationHelper.onResume()
        horloge.removeCallbacks(tic)
        horloge.post(tic)
    }

    private fun configurer(s: Session) {
        val largeur = prefs.getInt("taille_cm", 8) / 100f
        val db = AugmentedImageDatabase(s)
        for (m in machines.values) {
            m.cibles.forEachIndexed { k, c ->
                assets.open(c.image).use { input ->
                    val bmp = BitmapFactory.decodeStream(input)
                    db.addImage("${m.key}#$k", bmp, c.largeurCm?.let { it / 100f } ?: largeur)
                }
            }
        }
        val config = Config(s).apply {
            augmentedImageDatabase = db
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            // La profondeur améliore le placement au toucher sur des surfaces quelconques
            depthMode = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }
        s.configure(config)
    }

    override fun onPause() {
        super.onPause()
        horloge.removeCallbacks(tic)
        if (session != null) {
            rotationHelper.onPause()
            surface.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        renderer.session = null
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "L'accès à la caméra est nécessaire.", Toast.LENGTH_LONG).show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    private fun erreur(msg: String) {
        status.text = "Erreur"
        hint.visibility = View.VISIBLE
        hint.text = msg
        hint.setTextColor(WARN)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
