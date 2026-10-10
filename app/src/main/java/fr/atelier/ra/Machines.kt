package fr.atelier.ra

import android.content.Context
import com.google.ar.core.Pose
import org.json.JSONObject

/*
 * REPÈRE MACHINE (toutes les positions pos_cm sont exprimées dedans), origine = centre du marqueur principal :
 *   x : vers la droite quand on regarde la machine de face (+)
 *   y : vers le haut (+)
 *   z : vers l'opérateur, c'est-à-dire vers l'avant de la machine (+) ; vers l'arrière (−)
 * Pour un marqueur posé à plat sur le dessus (bandeau HAUT vers le fond), c'est exactement le repère du marqueur.
 */

/** Un point (organe, élément) positionné dans le repère machine. */
data class PointEntretien(
    val ou: String,
    val action: String,
    val periode: String,
    val xCm: Float,
    val yCm: Float,
    val zCm: Float,
    /** actionneur, capteur, préactionneur, commande, sécurité, énergie… (facultatif) */
    val type: String = "",
)

data class Bloc(val titre: String, val texte: String)

/** Étape de procédure ; `repere` = index (0, 1, …) du point à montrer, ou null. */
data class Etape(val texte: String, val repere: Int?)

/** Maillon d'une chaîne posé sur la machine ; position donnée par un repère (`repere`) ou en cm. */
data class Maillon(val bloc: Int, val nom: String, val repere: Int?, val xCm: Float, val yCm: Float, val zCm: Float)

/** Une chaîne fonctionnelle : d'action (énergie) ou d'acquisition (information). */
data class Chaine(
    val titre: String,
    val acquisition: Boolean,
    val intro: String,
    val blocs: List<Bloc>,
    val maillons: List<Maillon>,
)

/**
 * Un marqueur collé sur la machine. `face` : dessus, avant, arriere, gauche, droite (bandeau HAUT vers le haut,
 * ou vers le fond pour « dessus »). `pose` = position et orientation du marqueur dans le repère machine.
 */
data class Cible(val image: String, val face: String, val pose: Pose, val largeurCm: Float?)

data class Machine(
    val key: String,
    val code: String,
    val nom: String,
    val cibles: List<Cible>,
    val chaines: List<Chaine>,
    val entretien: List<PointEntretien>,
    val securiteTitre: String,
    val etapes: List<Etape>,
    val note: String,
)

object Machines {
    private const val S = 0.70710677f

    /** Rotation « repère du marqueur → repère machine » selon la face où il est collé. */
    fun rotationFace(face: String): Pose {
        val rx90 = Pose.makeRotation(S, 0f, 0f, S)                // marqueur vertical, face à l'avant
        fun ry(deg: Float): Pose {
            val a = Math.toRadians(deg / 2.0)
            return Pose.makeRotation(0f, Math.sin(a).toFloat(), 0f, Math.cos(a).toFloat())
        }
        return when (face.lowercase()) {
            "avant" -> rx90
            "arriere", "arrière" -> ry(180f).compose(rx90)
            "gauche" -> ry(-90f).compose(rx90)
            "droite" -> ry(90f).compose(rx90)
            else -> Pose.IDENTITY                                   // dessus
        }
    }

    private fun maillons(a: org.json.JSONArray?): List<Maillon> = a?.let {
        (0 until it.length()).map { k ->
            val c = it.getJSONObject(k)
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

    private fun blocs(a: org.json.JSONArray): List<Bloc> =
        (0 until a.length()).map { Bloc(a.getJSONObject(it).getString("titre"), a.getJSONObject(it).getString("texte")) }

    fun load(context: Context): List<Machine> {
        val txt = context.assets.open("machines.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(txt).getJSONArray("machines")
        return (0 until root.length()).map { i ->
            val o = root.getJSONObject(i)
            val s = o.getJSONObject("securite")

            // Marqueurs : liste « cibles », ou ancien champ « image » (un marqueur à plat sur le dessus)
            val cibles = o.optJSONArray("cibles")?.let { a ->
                (0 until a.length()).map {
                    val c = a.getJSONObject(it)
                    val face = c.optString("face", "dessus")
                    val p = c.optJSONObject("pos_cm")
                    val t = Pose.makeTranslation(
                        (p?.optDouble("x", 0.0) ?: 0.0).toFloat() / 100f,
                        (p?.optDouble("y", 0.0) ?: 0.0).toFloat() / 100f,
                        (p?.optDouble("z", 0.0) ?: 0.0).toFloat() / 100f)
                    Cible(c.getString("image"), face, t.compose(rotationFace(face)),
                        if (c.has("largeur_cm")) c.getDouble("largeur_cm").toFloat() else null)
                }
            } ?: listOf(Cible(o.getString("image"), "dessus", Pose.IDENTITY, null))

            // Chaînes : liste « chaines », ou anciens champs « fonction » + « chaine »
            val chaines = o.optJSONArray("chaines")?.let { a ->
                (0 until a.length()).map {
                    val c = a.getJSONObject(it)
                    Chaine(
                        titre = c.getString("titre"),
                        acquisition = c.optString("type") == "acquisition",
                        intro = c.optString("intro"),
                        blocs = blocs(c.getJSONArray("blocs")),
                        maillons = maillons(c.optJSONArray("maillons")),
                    )
                }
            } ?: o.optJSONObject("fonction")?.let { f ->
                listOf(Chaine("Chaîne fonctionnelle", false, f.optString("intro"), blocs(f.getJSONArray("blocs")),
                    maillons(o.optJSONArray("chaine"))))
            } ?: emptyList()

            val pts = o.getJSONArray("entretien").let { a ->
                (0 until a.length()).map {
                    val p = a.getJSONObject(it)
                    val pos = p.getJSONObject("pos_cm")
                    PointEntretien(
                        p.getString("ou"), p.getString("action"), p.getString("periode"),
                        pos.getDouble("x").toFloat(), pos.getDouble("y").toFloat(), pos.getDouble("z").toFloat(),
                        p.optString("type"),
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
            Machine(
                key = o.getString("id"),
                code = o.getString("code"),
                nom = o.getString("nom"),
                cibles = cibles,
                chaines = chaines,
                entretien = pts,
                securiteTitre = s.getString("titre"),
                etapes = etapes,
                note = s.getString("note"),
            )
        }
    }
}
