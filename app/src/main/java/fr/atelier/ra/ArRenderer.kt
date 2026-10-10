package fr.atelier.ra

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import fr.atelier.ra.common.BackgroundRenderer
import fr.atelier.ra.common.DisplayRotationHelper
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class Etat { INIT, RECHERCHE, MARQUEUR, ESPACE }

/** Événements envoyés à l'interface (appelés depuis le fil de rendu). */
interface ArEvents {
    fun etat(etat: Etat, machine: String?, message: String?)
    /** Le marqueur vient d'être (re)vu après une perte. */
    fun marqueurRetrouve(machine: String)
    fun repereTouche(machine: String, index: Int)
    fun repereplace(machine: String, index: Int, ok: Boolean)
}

/**
 * Rendu OpenGL + logique de suivi :
 * - tant que le marqueur est vu (FULL_TRACKING), les repères suivent sa position exacte ;
 * - une ancre ARCore est posée dans l'espace à chaque détection ;
 * - quand le marqueur sort du champ, les repères restent accrochés à l'ancre (suivi par l'espace).
 */
class ArRenderer(
    private val context: Context,
    private val machines: Map<String, Machine>,
    private val store: Store,
    private val rotationHelper: DisplayRotationHelper,
    private val events: ArEvents,
) : GLSurfaceView.Renderer {

    @Volatile var session: Session? = null
    /** Repère mis en avant : (machine, index) */
    @Volatile var selection: Pair<String, Int>? = null
    /** Repère en attente de placement au toucher : (machine, index) */
    @Volatile var placement: Pair<String, Int>? = null

    /**
     * Position à l'écran du repère sélectionné, pour la flèche de guidage :
     * [valide (0/1), x px, y px, derrière (0/1), distance m]
     */
    @Volatile var cible = FloatArray(5)

    private val touches = ConcurrentLinkedQueue<FloatArray>()
    fun toucher(x: Float, y: Float) { touches.add(floatArrayOf(x, y)) }

    private val background = BackgroundRenderer()
    private lateinit var flat: FlatProgram
    private lateinit var sprite: SpriteProgram
    private lateinit var cone: Mesh
    private lateinit var stem: Mesh
    private lateinit var frameMesh: Mesh
    private var textureSet = false
    private var width = 1
    private var height = 1

    private class Suivi(val key: String) {
        var image: AugmentedImage? = null
        var anchor: Anchor? = null
        var lastFull = 0L
        var extent = 0.08f
        var etaitVu = false
        var pose: Pose? = null
    }
    private val suivis = HashMap<String, Suivi>()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val base = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val vIn = FloatArray(4)
    private val vOut = FloatArray(4)
    private val clip = FloatArray(4)

    private var lastEtat: Etat? = null
    private var lastKey: String? = null
    private var lastMsg: String? = null

    // Couleurs (RGBA, alpha = 1)
    private val ACC = floatArrayOf(1f, 0.69f, 0.125f, 1f)
    private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
    private val DONE = floatArrayOf(0.235f, 0.81f, 0.557f, 1f)
    private val PLACE = floatArrayOf(1f, 0.42f, 0.29f, 1f)
    private val FRAME_FULL = floatArrayOf(0.235f, 0.81f, 0.557f, 1f)
    private val FRAME_SPACE = floatArrayOf(0.29f, 0.66f, 1f, 1f)

    /** Positions écran des pastilles dessinées à cette image : machine, index, x, y */
    private data class Ecran(val key: String, val i: Int, val x: Float, val y: Float)
    private val ecran = ArrayList<Ecran>()

    companion object {
        private const val TAG = "AtelierRA"
        private const val CONE_R = 0.007f
        private const val CONE_H = 0.022f
        private const val STEM_H = 0.05f
        private const val LABEL_Y = CONE_H + STEM_H + 0.018f
        private const val LABEL_SIZE = 0.035f
        private const val REANCRAGE_M = 0.015f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            background.createOnGlThread(context)
            flat = FlatProgram()
            sprite = SpriteProgram()
            cone = Mesh.cone(CONE_R, CONE_H)
            stem = Mesh.box(0.003f, STEM_H, 0.003f, 0f, CONE_H + STEM_H / 2, 0f)
            frameMesh = Mesh.frame(0.06f)
            LabelTextures.clear()
            textureSet = false
        } catch (e: Exception) {
            Log.e(TAG, "Initialisation OpenGL", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        rotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
        this.width = width; this.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        rotationHelper.updateSessionIfNeeded(s)
        try {
            if (!textureSet) {
                s.setCameraTextureName(background.textureId)
                textureSet = true
            }
            val frame = s.update()
            val camera = frame.camera
            background.draw(frame)

            if (camera.trackingState != TrackingState.TRACKING) {
                touches.clear()
                cible = FloatArray(5)
                report(Etat.INIT, lastKey, raison(camera.trackingFailureReason))
                return
            }

            mettreAJourImages(frame.getUpdatedTrackables(AugmentedImage::class.java))

            camera.getProjectionMatrix(proj, 0, 0.02f, 50f)
            camera.getViewMatrix(view, 0)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            val camPose = camera.displayOrientedPose

            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            var anyFull = false
            var anySpace = false
            var current: Suivi? = null
            val t = SystemClock.uptimeMillis()
            ecran.clear()
            var nouvelleCible = FloatArray(5)

            for (sv in suivis.values) {
                val img = sv.image
                val full = img != null && img.trackingState == TrackingState.TRACKING &&
                        img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                if (full && !sv.etaitVu) events.marqueurRetrouve(sv.key)
                sv.etaitVu = full
                val pose: Pose = when {
                    full -> img!!.centerPose
                    sv.anchor?.trackingState == TrackingState.TRACKING -> sv.anchor!!.pose
                    else -> { sv.pose = null; continue }
                }
                sv.pose = pose
                if (full) anyFull = true else anySpace = true
                if (current == null || sv.lastFull > current.lastFull) current = sv
                val c = dessinerMachine(sv, pose, full, camPose, t)
                if (c != null) nouvelleCible = c
            }
            cible = nouvelleCible

            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)

            traiterTouches(frame)

            val etat = when {
                anyFull -> Etat.MARQUEUR
                anySpace -> Etat.ESPACE
                else -> Etat.RECHERCHE
            }
            report(etat, current?.key ?: lastKey, null)
        } catch (e: Throwable) {
            Log.e(TAG, "Erreur de rendu", e)
        }
    }

    private fun mettreAJourImages(updated: Collection<AugmentedImage>) {
        val now = SystemClock.uptimeMillis()
        for (img in updated) {
            val key = img.name
            when (img.trackingState) {
                TrackingState.TRACKING -> {
                    val sv = suivis.getOrPut(key) { Suivi(key) }
                    sv.image = img
                    if (img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                        sv.lastFull = now
                        sv.extent = max(img.extentX, img.extentZ)
                        val cp = img.centerPose
                        val a = sv.anchor
                        if (a == null || a.trackingState != TrackingState.TRACKING || distance(a.pose, cp) > REANCRAGE_M) {
                            a?.detach()
                            sv.anchor = img.createAnchor(cp)
                        }
                    }
                }
                TrackingState.STOPPED -> suivis.remove(key)?.anchor?.detach()
                else -> {}
            }
        }
    }

    /** Dessine une machine ; renvoie la position écran du repère sélectionné s'il appartient à cette machine. */
    private fun dessinerMachine(sv: Suivi, pose: Pose, full: Boolean, camPose: Pose, t: Long): FloatArray? {
        val machine = machines[sv.key] ?: return null
        pose.toMatrix(base, 0)
        var res: FloatArray? = null

        Matrix.scaleM(model, 0, base, 0, sv.extent, 1f, sv.extent)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        flat.draw(frameMesh, mvp, if (full) FRAME_FULL else FRAME_SPACE)

        val sel = selection
        val plc = placement
        machine.entretien.indices.forEach { i ->
            val p = store.position(machine, i)
            val x = p[0] / 100f; val y = p[1] / 100f; val z = p[2] / 100f
            val isSel = sel != null && sel.first == sv.key && sel.second == i
            val isPlc = plc != null && plc.first == sv.key && plc.second == i
            val color = when {
                isPlc -> PLACE
                isSel -> WHITE
                store.estFait(sv.key, i) -> DONE
                else -> ACC
            }

            Matrix.translateM(model, 0, base, 0, x, y, z)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            flat.draw(cone, mvp, color)
            flat.draw(stem, mvp, color)

            // Pastille numérotée face à l'utilisateur, visible à travers les objets
            vIn[0] = x; vIn[1] = y + LABEL_Y; vIn[2] = z; vIn[3] = 1f
            Matrix.multiplyMV(vOut, 0, base, 0, vIn, 0)
            camPose.toMatrix(tmp, 0)
            tmp[12] = vOut[0]; tmp[13] = vOut[1]; tmp[14] = vOut[2]
            val pulse = 1.35f + 0.12f * sin(t / 160.0).toFloat()
            val size = if (isSel || isPlc) LABEL_SIZE * pulse else LABEL_SIZE
            Matrix.scaleM(model, 0, tmp, 0, size, size, size)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            sprite.draw(LabelTextures.get(i + 1), mvp, color)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)

            // Projection écran de la pastille
            vOut[3] = 1f
            Matrix.multiplyMV(clip, 0, vp, 0, vOut, 0)
            val behind = clip[3] <= 0f
            val sx = (clip[0] / clip[3] + 1f) / 2f * width
            val sy = (1f - clip[1] / clip[3]) / 2f * height
            if (!behind) ecran.add(Ecran(sv.key, i, sx, sy))
            if (isSel || isPlc) {
                val d = sqrt((vOut[0] - camPose.tx()).let { it * it } + (vOut[1] - camPose.ty()).let { it * it } + (vOut[2] - camPose.tz()).let { it * it })
                // Derrière la caméra : on inverse pour que la flèche indique le bon côté
                val fx = if (behind) width - sx else sx
                val fy = if (behind) height - sy else sy
                res = floatArrayOf(1f, fx, fy, if (behind) 1f else 0f, d)
            }
        }
        return res
    }

    private fun traiterTouches(frame: Frame) {
        while (true) {
            val tc = touches.poll() ?: break
            val plc = placement
            if (plc != null) {
                events.repereplace(plc.first, plc.second, placer(frame, plc, tc[0], tc[1]))
            } else {
                val r = 0.09f * width
                val proche = ecran.minByOrNull { hypot(it.x - tc[0], it.y - tc[1]) }
                if (proche != null && hypot(proche.x - tc[0], proche.y - tc[1]) < r) {
                    events.repereTouche(proche.key, proche.i)
                }
            }
        }
    }

    /** Place un repère à l'endroit réel touché, exprimé dans le repère du marqueur. */
    private fun placer(frame: Frame, plc: Pair<String, Int>, x: Float, y: Float): Boolean {
        val markerPose = suivis[plc.first]?.pose ?: return false
        for (hit in frame.hitTest(x, y)) {
            val tr = hit.trackable
            val ok = (tr is Plane && tr.isPoseInPolygon(hit.hitPose)) ||
                    tr is DepthPoint ||
                    (tr is Point && tr.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
            if (!ok) continue
            val local = markerPose.inverse().compose(hit.hitPose)
            store.placer(plc.first, plc.second, floatArrayOf(local.tx() * 100f, local.ty() * 100f, local.tz() * 100f))
            return true
        }
        return false
    }

    private fun distance(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx(); val dy = a.ty() - b.ty(); val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun raison(r: TrackingFailureReason): String = when (r) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Pas assez de lumière"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Mouvement trop rapide"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Visez une zone plus détaillée"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Caméra indisponible"
        else -> "Balayez doucement la pièce avec le téléphone"
    }

    private fun report(etat: Etat, key: String?, msg: String?) {
        if (etat == lastEtat && key == lastKey && msg == lastMsg) return
        lastEtat = etat; lastKey = key; lastMsg = msg
        events.etat(etat, key, msg)
    }
}
