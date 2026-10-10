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

/** Étape de procédure ; `repere` = index (0, 1, …) du point d'entretien à montrer, ou null. */
data class Etape(val texte: String, val repere: Int?)

/**
 * Maillon de la chaîne d'énergie posé sur la machine.
 * `bloc` = index (0, 1, …) du bloc fonctionnel ; position donnée par un repère (`repere`) ou en cm.
 */
data class Maillon(val bloc: Int, val nom: String, val repere: Int?, val xCm: Float, val yCm: Float, val zCm: Float)

data class Machine(
    val key: String,
    val code: String,
    val nom: String,
    val image: String,
    val fonctionIntro: String,
    val blocs: List<Bloc>,
    val entretien: List<PointEntretien>,
    val securiteTitre: String,
    val etapes: List<Etape>,
    val note: String,
    val chaine: List<Maillon> = emptyList(),
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
            val etapes = s.getJSONArray("etapes").let { a ->
                (0 until a.length()).map {
                    val e = a.opt(it)
                    if (e is JSONObject) Etape(e.getString("texte"), if (e.has("repere")) e.getInt("repere") - 1 else null)
                    else Etape(e.toString(), null)
                }
            }
            val chaine = o.optJSONArray("chaine")?.let { a ->
                (0 until a.length()).map {
                    val c = a.getJSONObject(it)
                    val pos = c.optJSONObject("pos_cm")
                    Maillon(
                        bloc = c.getInt("bloc") - 1,
                        nom = c.getString("nom"),
                        repere = if (c.has("repere")) c.getInt("repere") - 1 else null,
                        xCm = pos?.optDouble("x", 0.0)?.toFloat() ?: 0f,
                        yCm = pos?.optDouble("y", 0.0)?.toFloat() ?: 0f,
                        zCm = pos?.optDouble("z", 0.0)?.toFloat() ?: 0f,
                    )
                }
            } ?: emptyList()
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
                chaine = chaine,
            )
        }
    }
}
