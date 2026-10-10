package fr.atelier.ra

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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

class MainActivity : AppCompatActivity() {

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

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var rotationHelper: DisplayRotationHelper
    private lateinit var machines: Map<String, Machine>

    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var panel: LinearLayout
    private lateinit var code: TextView
    private lateinit var name: TextView
    private lateinit var tabs: List<TextView>
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView

    private var session: Session? = null
    private var installRequested = false
    private var current: String? = null
    private var tab = 0
    private var minimized = false

    private val prefs by lazy { getSharedPreferences("atelier-ra", Context.MODE_PRIVATE) }
    private val tailles = listOf(5, 8, 12, 18)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        machines = Machines.load(this).associateBy { it.key }
        rotationHelper = DisplayRotationHelper(this)
        renderer = ArRenderer(this, machines, rotationHelper) { etat, key, msg ->
            runOnUiThread { majEtat(etat, key, msg) }
        }
        setContentView(buildUi())
    }

    // ------------------------------------------------------------------ Interface

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

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Barre du haut
        val top = LinearLayout(this).apply {
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
        top.addView(status)
        val gear = text("⚙", 18f).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(40, 255, 255, 255), 99f)
            setOnClickListener { reglages() }
        }
        top.addView(gear, LinearLayout.LayoutParams(dpi(34), dpi(34)).apply { leftMargin = dpi(8) })
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // Aide de visée
        hint = text("Visez un marqueur imprimé", 15f).apply {
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
        }
        root.addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

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
            setOnClickListener { minimized = !minimized; appliquerMin() }
        }
        code = text("", 11f, ACC, true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dpi(6), dpi(2), dpi(6), dpi(2))
            background = rounded(Color.TRANSPARENT, 5f, ACC)
        }
        name = text("", 16f, bold = true).apply { setPadding(dpi(10), 0, 0, 0) }
        head.addView(code); head.addView(name)
        panel.addView(head)

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(10), 0, dpi(10)) }
        tabs = listOf("Fonction", "Entretien", "Sécurité").mapIndexed { i, label ->
            text(label, 13f, MUT, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dpi(9), 0, dpi(9))
                setOnClickListener { tab = i; minimized = false; appliquerMin(); afficher() }
            }.also {
                tabRow.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dpi(6) })
            }
        }
        panel.addView(tabRow)

        scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        val maxH = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, maxH))

        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    private fun appliquerMin() {
        val v = if (minimized) View.GONE else View.VISIBLE
        scroll.visibility = v
        (tabs.first().parent as View).visibility = v
    }

    private fun majEtat(etat: Etat, key: String?, msg: String?) {
        val (label, bg, fg) = when (etat) {
            Etat.INIT -> Triple(msg ?: "Initialisation…", Color.argb(40, 255, 255, 255), MUT)
            Etat.RECHERCHE -> Triple("Recherche…", Color.argb(40, 255, 255, 255), MUT)
            Etat.MARQUEUR -> Triple("Marqueur suivi", OK, Color.parseColor("#06251A"))
            Etat.ESPACE -> Triple("Suivi par l'espace", SPACE, Color.parseColor("#04213D"))
        }
        status.text = label
        status.setTextColor(fg)
        status.background = rounded(bg, 99f)
        hint.visibility = if (etat == Etat.MARQUEUR || etat == Etat.ESPACE) View.GONE else View.VISIBLE
        hint.text = if (etat == Etat.INIT) (msg ?: "Balayez doucement la pièce") else "Visez un marqueur imprimé"

        if (key != null && key != current) {
            current = key
            renderer.selection = null
            panel.visibility = View.VISIBLE
            afficher()
        }
    }

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
            0 -> {
                content.addView(text(m.fonctionIntro, 14f, MUT).apply { setPadding(0, 0, 0, dpi(8)) })
                m.blocs.forEachIndexed { i, b ->
                    if (i > 0) content.addView(text("▼", 11f, MUT).apply { gravity = Gravity.CENTER })
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dpi(12), dpi(8), dpi(10), dpi(8))
                        background = rounded(Color.argb(20, 255, 176, 32), 8f, LINE)
                    }
                    card.addView(text(b.titre.uppercase(), 12f, ACC, true))
                    card.addView(text(b.texte, 14f))
                    content.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(2); bottomMargin = dpi(2) })
                }
            }
            1 -> {
                content.addView(text("Touchez un point pour le repérer sur l'équipement.", 14f, MUT).apply { setPadding(0, 0, 0, dpi(6)) })
                val sel = renderer.selection
                m.entretien.forEachIndexed { i, p ->
                    val isSel = sel != null && sel.first == m.key && sel.second == i
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dpi(8), dpi(9), dpi(8), dpi(9))
                        background = if (isSel) rounded(Color.argb(30, 255, 176, 32), 10f, ACC) else rounded(Color.TRANSPARENT, 10f)
                        setOnClickListener {
                            renderer.selection = if (isSel) null else Pair(m.key, i)
                            afficher()
                        }
                    }
                    val num = text("${i + 1}", 13f, DARK, true).apply {
                        gravity = Gravity.CENTER
                        background = rounded(ACC, 99f)
                    }
                    row.addView(num, LinearLayout.LayoutParams(dpi(26), dpi(26)).apply { rightMargin = dpi(10) })
                    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(text(p.ou, 14f, bold = true))
                    col.addView(text(p.action, 13f, MUT))
                    col.addView(text("⟳ ${p.periode}", 11f, TXT).apply {
                        setPadding(dpi(7), dpi(1), dpi(7), dpi(1))
                        background = rounded(Color.argb(26, 255, 255, 255), 99f)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dpi(3) })
                    row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    content.addView(row)
                }
            }
            else -> {
                content.addView(text(m.securiteTitre, 14f, bold = true).apply { setPadding(0, 0, 0, dpi(6)) })
                m.etapes.forEachIndexed { i, e ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(7), 0, dpi(7)) }
                    val num = text("${i + 1}", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rounded(WARN, 99f) }
                    row.addView(num, LinearLayout.LayoutParams(dpi(26), dpi(26)).apply { rightMargin = dpi(10) })
                    row.addView(text(e, 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    content.addView(row)
                }
                content.addView(text("⚠ ${m.note}", 12f, MUT).apply {
                    setPadding(dpi(10), dpi(8), dpi(10), dpi(8))
                    background = GradientDrawable().apply { cornerRadius = dp(8f); setStroke(dpi(1), WARN, dp(4f), dp(3f)) }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(10) })
            }
        }
    }

    private fun reglages() {
        val actuelle = prefs.getInt("taille_cm", 8)
        val labels = tailles.map { "$it cm" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Taille des marqueurs imprimés")
            .setSingleChoiceItems(labels, tailles.indexOf(actuelle).coerceAtLeast(0)) { d, which ->
                prefs.edit().putInt("taille_cm", tailles[which]).apply()
                d.dismiss()
                recreate()
            }
            .setNeutralButton("Réinitialiser le suivi") { _, _ -> recreate() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    // ------------------------------------------------------------------ Cycle de vie ARCore

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
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
    }

    private fun configurer(s: Session) {
        val largeur = prefs.getInt("taille_cm", 8) / 100f
        val db = AugmentedImageDatabase(s)
        for (m in machines.values) {
            assets.open(m.image).use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                db.addImage(m.key, bmp, largeur)
            }
        }
        val config = Config(s).apply {
            augmentedImageDatabase = db
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            lightEstimationMode = Config.LightEstimationMode.DISABLED
        }
        s.configure(config)
    }

    override fun onPause() {
        super.onPause()
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
