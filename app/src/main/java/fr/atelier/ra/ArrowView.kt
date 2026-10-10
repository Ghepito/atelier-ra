package fr.atelier.ra

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/**
 * Surcouche 2D : quand le repère sélectionné est hors de l'écran (ou derrière l'utilisateur),
 * une flèche au bord de l'écran indique la direction à suivre, avec la distance.
 */
class ArrowView(context: Context, private val source: () -> FloatArray, private val numero: () -> Int?) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB020") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D1400"); style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D1400"); textAlign = Paint.Align.CENTER; textSize = 13f * d; typeface = Typeface.DEFAULT_BOLD
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    private val path = Path()

    /** Marges hautes et basses à éviter (barre du haut, panneau). */
    var margeHaut = 0f
    var margeBas = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        postInvalidateOnAnimation()
        val c = source()
        val n = numero() ?: return
        if (c[0] < 0.5f) return
        val w = width.toFloat(); val h = height.toFloat()
        val m = 34f * d
        val top = margeHaut + m; val bottom = h - margeBas - m
        val behind = c[3] > 0.5f
        val onScreen = !behind && c[1] in m..(w - m) && c[2] in top..bottom
        if (onScreen) return

        // Direction depuis le centre de l'écran vers le repère, ramenée sur le bord
        val cx = w / 2; val cy = (top + bottom) / 2
        var dx = c[1] - cx; var dy = c[2] - cy
        if (abs(dx) < 1f && abs(dy) < 1f) { dx = 0f; dy = 1f }
        val sx = if (dx != 0f) (w / 2 - m) / abs(dx) else Float.MAX_VALUE
        val sy = if (dy != 0f) ((bottom - top) / 2) / abs(dy) else Float.MAX_VALUE
        val k = min(sx, sy)
        val px = cx + dx * k; val py = cy + dy * k
        val ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

        canvas.save()
        canvas.translate(px, py)
        canvas.rotate(ang)
        val r = 22f * d
        path.reset()
        path.moveTo(r * 1.25f, 0f)
        path.lineTo(-r * 0.7f, -r * 0.85f)
        path.lineTo(-r * 0.35f, 0f)
        path.lineTo(-r * 0.7f, r * 0.85f)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        canvas.restore()

        // Numéro + distance à côté de la flèche, toujours lisibles (pas tournés)
        val bx = px - dx * (40f * d) / maxOf(abs(dx), abs(dy))
        val by = py - dy * (40f * d) / maxOf(abs(dx), abs(dy))
        canvas.drawCircle(bx, by, 13f * d, fill)
        canvas.drawCircle(bx, by, 13f * d, stroke)
        canvas.drawText(n.toString(), bx, by + 4.5f * d, txt)
        val dist = if (c[4] < 1f) "${(c[4] * 100).toInt()} cm" else String.format(Locale.FRANCE, "%.1f m", c[4])
        val ly = if (by + 34f * d > height - margeBas) by - 20f * d else by + 30f * d
        val lx = bx.coerceIn(70f * d, width - 70f * d)
        canvas.drawText(if (behind) "$dist · derrière vous" else dist, lx, ly, label)
    }
}
