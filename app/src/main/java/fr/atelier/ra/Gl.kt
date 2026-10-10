package fr.atelier.ra

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/** Petits outils OpenGL ES 2.0 : programmes, maillages simples, textures de pastilles. */
object Gl {
    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader : " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Programme : " + GLES20.glGetProgramInfoLog(p))
        return p
    }
}

/** Programme à couleur unie. */
class FlatProgram {
    private val prog = Gl.program(
        """
        uniform mat4 u_Mvp;
        attribute vec4 a_Pos;
        void main() { gl_Position = u_Mvp * a_Pos; }
        """.trimIndent(),
        """
        precision mediump float;
        uniform vec4 u_Color;
        void main() { gl_FragColor = u_Color; }
        """.trimIndent()
    )
    private val uMvp = GLES20.glGetUniformLocation(prog, "u_Mvp")
    private val uColor = GLES20.glGetUniformLocation(prog, "u_Color")
    private val aPos = GLES20.glGetAttribLocation(prog, "a_Pos")

    fun draw(mesh: Mesh, mvp: FloatArray, rgba: FloatArray) {
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4fv(uColor, 1, rgba, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, mesh.pos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.count)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}

/** Programme texturé (pastilles numérotées). */
class SpriteProgram {
    private val prog = Gl.program(
        """
        uniform mat4 u_Mvp;
        attribute vec4 a_Pos;
        attribute vec2 a_Uv;
        varying vec2 v_Uv;
        void main() { gl_Position = u_Mvp * a_Pos; v_Uv = a_Uv; }
        """.trimIndent(),
        """
        precision mediump float;
        uniform sampler2D u_Tex;
        uniform vec4 u_Tint;
        varying vec2 v_Uv;
        void main() { gl_FragColor = texture2D(u_Tex, v_Uv) * u_Tint; }
        """.trimIndent()
    )
    private val uMvp = GLES20.glGetUniformLocation(prog, "u_Mvp")
    private val uTex = GLES20.glGetUniformLocation(prog, "u_Tex")
    private val uTint = GLES20.glGetUniformLocation(prog, "u_Tint")
    private val aPos = GLES20.glGetAttribLocation(prog, "a_Pos")
    private val aUv = GLES20.glGetAttribLocation(prog, "a_Uv")

    // Carré centré, dans le plan XY, face à +Z
    private val quad = Gl.floatBuffer(floatArrayOf(
        -0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f,
        -0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f))
    private val uv = Gl.floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 0f))

    fun draw(tex: Int, mvp: FloatArray, tint: FloatArray) {
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4fv(uTint, 1, tint, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }
}

class Mesh(data: FloatArray) {
    val pos: FloatBuffer = Gl.floatBuffer(data)
    val count = data.size / 3

    companion object {
        /** Cône pointe en bas à l'origine, base à y = h. */
        fun cone(r: Float, h: Float, seg: Int = 16): Mesh {
            val v = ArrayList<Float>()
            for (i in 0 until seg) {
                val a0 = (2 * Math.PI * i / seg).toFloat()
                val a1 = (2 * Math.PI * (i + 1) / seg).toFloat()
                val x0 = r * cos(a0); val z0 = r * sin(a0)
                val x1 = r * cos(a1); val z1 = r * sin(a1)
                v += listOf(0f, 0f, 0f, x1, h, z1, x0, h, z0)   // flanc
                v += listOf(0f, h, 0f, x0, h, z0, x1, h, z1)    // base
            }
            return Mesh(v.toFloatArray())
        }

        /** Pavé centré en (cx, cy, cz). */
        fun box(sx: Float, sy: Float, sz: Float, cx: Float = 0f, cy: Float = 0f, cz: Float = 0f): Mesh {
            val x0 = cx - sx / 2; val x1 = cx + sx / 2
            val y0 = cy - sy / 2; val y1 = cy + sy / 2
            val z0 = cz - sz / 2; val z1 = cz + sz / 2
            fun q(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) =
                listOf(*a.toTypedArray(), *b.toTypedArray(), *c.toTypedArray(), *a.toTypedArray(), *c.toTypedArray(), *d.toTypedArray())
            val p = arrayOf(
                floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
                floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
            val v = ArrayList<Float>()
            v += q(p[0], p[1], p[2], p[3]); v += q(p[5], p[4], p[7], p[6])
            v += q(p[4], p[0], p[3], p[7]); v += q(p[1], p[5], p[6], p[2])
            v += q(p[3], p[2], p[6], p[7]); v += q(p[4], p[5], p[1], p[0])
            return Mesh(v.toFloatArray())
        }

        /** Cadre carré à plat (plan XZ), côté extérieur = 1, épaisseur relative t. */
        fun frame(t: Float): Mesh {
            val o = 0.5f; val i = 0.5f - t; val y = 0.001f
            val v = ArrayList<Float>()
            fun rect(xa: Float, za: Float, xb: Float, zb: Float) {
                v += listOf(xa, y, za, xb, y, za, xb, y, zb, xa, y, za, xb, y, zb, xa, y, zb)
            }
            rect(-o, -o, o, -i); rect(-o, i, o, o); rect(-o, -i, -i, i); rect(i, -i, o, i)
            return Mesh(v.toFloatArray())
        }
    }
}

/** Textures des pastilles numérotées (cercle blanc, chiffre sombre ; teinte appliquée au rendu). */
object LabelTextures {
    private val cache = HashMap<Int, Int>()

    fun get(n: Int): Int = cache.getOrPut(n) {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        c.drawCircle(64f, 64f, 58f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 8f; p.color = Color.rgb(29, 20, 0)
        c.drawCircle(64f, 64f, 56f, p)
        p.style = Paint.Style.FILL
        p.textSize = if (n < 10) 76f else 60f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val fm = p.fontMetrics
        c.drawText(n.toString(), 64f, 64f - (fm.ascent + fm.descent) / 2, p)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        t[0]
    }

    fun clear() = cache.clear()
}

/** Étiquettes de texte (pilule colorée) ; renvoie l'identifiant de texture et le rapport largeur/hauteur. */
object TextTextures {
    private val cache = HashMap<String, Pair<Int, Float>>()

    fun get(texte: String, fond: Int, encre: Int): Pair<Int, Float> = cache.getOrPut("$texte|$fond|$encre") {
        val h = 64
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val w = (p.measureText(texte) + 40f).toInt().coerceAtLeast(h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fond }
        c.drawRoundRect(2f, 2f, w - 2f, h - 2f, 30f, 30f, bg)
        p.color = encre
        val fm = p.fontMetrics
        c.drawText(texte, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, p)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        Pair(t[0], w.toFloat() / h)
    }

    fun clear() = cache.clear()
}
