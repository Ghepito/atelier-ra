package fr.atelier.ra

import android.content.Context
import org.json.JSONObject

/** Un point d'entretien, positionné en centimètres depuis le CENTRE du marqueur posé à plat. */
data class PointEntretien(
    val ou: String,
    val action: String,
    val periode: String,
    /** vers la droite du marqueur (+) */
    val xCm: Float,
    /** au-dessus de la surface (+) */
    val yCm: Float,
    /** vers le bas du marqueur, côté opposé au bandeau « HAUT » (+) */
    val zCm: Float,
)

data class Bloc(val titre: String, val texte: String)

data class Machine(
    val key: String,
    val code: String,
    val nom: String,
    val image: String,
    val fonctionIntro: String,
    val blocs: List<Bloc>,
    val entretien: List<PointEntretien>,
    val securiteTitre: String,
    val etapes: List<String>,
    val note: String,
)

object Machines {
    fun load(context: Context): List<Machine> {
        val txt = context.assets.open("machines.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(txt).getJSONArray("machines")
        return (0 until root.length()).map { i ->
            val o = root.getJSONObject(i)
            val f = o.getJSONObject("fonction")
            val s = o.getJSONObject("securite")
            val blocs = f.getJSONArray("blocs").let { a ->
                (0 until a.length()).map { Bloc(a.getJSONObject(it).getString("titre"), a.getJSONObject(it).getString("texte")) }
            }
            val pts = o.getJSONArray("entretien").let { a ->
                (0 until a.length()).map {
                    val p = a.getJSONObject(it)
                    val pos = p.getJSONObject("pos_cm")
                    PointEntretien(
                        p.getString("ou"), p.getString("action"), p.getString("periode"),
                        pos.getDouble("x").toFloat(), pos.getDouble("y").toFloat(), pos.getDouble("z").toFloat()
                    )
                }
            }
            val etapes = s.getJSONArray("etapes").let { a -> (0 until a.length()).map { a.getString(it) } }
            Machine(
                key = o.getString("id"),
                code = o.getString("code"),
                nom = o.getString("nom"),
                image = o.getString("image"),
                fonctionIntro = f.getString("intro"),
                blocs = blocs,
                entretien = pts,
                securiteTitre = s.getString("titre"),
                etapes = etapes,
                note = s.getString("note"),
            )
        }
    }
}
