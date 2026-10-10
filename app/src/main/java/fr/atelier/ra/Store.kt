package fr.atelier.ra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Une entrée du journal d'un équipement. */
data class Entree(val date: Long, val titre: String, val detail: String)

/**
 * Mémoire locale de l'appli (sur le téléphone) :
 * - positions des repères placées au toucher (remplacent celles du fichier) ;
 * - points cochés de la tournée en cours ;
 * - journal des tournées et procédures réalisées.
 * Les lectures depuis le fil de rendu passent par des tables concurrentes.
 */
class Store(context: Context, private val machines: Map<String, Machine>) {
    private val prefs = context.getSharedPreferences("atelier-ra-donnees", Context.MODE_PRIVATE)
    private val positions = ConcurrentHashMap<String, FloatArray>()
    private val faits = ConcurrentHashMap<String, Long>()

    init {
        for ((k, v) in prefs.all) {
            if (k.startsWith("pos_") && v is String) {
                val p = v.split(";").mapNotNull { it.toFloatOrNull() }
                if (p.size == 3) positions[k.removePrefix("pos_")] = p.toFloatArray()
            }
            if (k.startsWith("fait_") && v is Long) faits[k.removePrefix("fait_")] = v
        }
    }

    private fun id(m: String, i: Int) = "${m}_$i"

    // ---------- Positions (cm, repère du marqueur)
    fun position(m: Machine, i: Int): FloatArray =
        positions[id(m.key, i)] ?: m.entretien[i].let { floatArrayOf(it.xCm, it.yCm, it.zCm) }

    fun estPlace(m: String, i: Int) = positions.containsKey(id(m, i))

    fun placer(m: String, i: Int, xyz: FloatArray) {
        positions[id(m, i)] = xyz
        prefs.edit().putString("pos_" + id(m, i), xyz.joinToString(";")).apply()
    }

    fun effacerPositions() {
        positions.clear()
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("pos_") }.forEach { remove(it) } }.apply()
    }

    // ---------- Tournée en cours
    fun estFait(m: String, i: Int) = faits.containsKey(id(m, i))

    fun basculer(m: String, i: Int) {
        val k = id(m, i)
        if (faits.remove(k) != null) prefs.edit().remove("fait_$k").apply()
        else { val t = System.currentTimeMillis(); faits[k] = t; prefs.edit().putLong("fait_$k", t).apply() }
    }

    fun nbFaits(m: Machine) = m.entretien.indices.count { estFait(m.key, it) }

    fun validerTournee(m: Machine) {
        val detail = m.entretien.mapIndexed { i, p -> (if (estFait(m.key, i)) "✓ " else "✗ ") + p.ou }.joinToString("\n")
        ajouter(m.key, Entree(System.currentTimeMillis(), "Tournée d'entretien (${nbFaits(m)}/${m.entretien.size})", detail))
        m.entretien.indices.forEach { i ->
            faits.remove(id(m.key, i)); prefs.edit().remove("fait_" + id(m.key, i)).apply()
        }
    }

    // ---------- Journal
    fun journal(m: String): List<Entree> {
        val a = JSONArray(prefs.getString("journal_$m", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Entree(o.getLong("date"), o.getString("titre"), o.optString("detail"))
        }.sortedByDescending { it.date }
    }

    fun ajouter(m: String, e: Entree) {
        val a = JSONArray(prefs.getString("journal_$m", "[]"))
        a.put(JSONObject().put("date", e.date).put("titre", e.titre).put("detail", e.detail))
        prefs.edit().putString("journal_$m", a.toString()).apply()
    }

    fun derniere(m: String, prefixe: String) = journal(m).firstOrNull { it.titre.startsWith(prefixe) }

    // ---------- Export : machines.json avec les positions placées
    fun exporterJson(context: Context): String {
        val root = JSONObject(Machines.texteCourant(context))
        val arr = root.getJSONArray("machines")
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            val m = machines[o.getString("id")] ?: continue
            val pts = o.getJSONArray("entretien")
            for (i in 0 until pts.length()) {
                val p = position(m, i)
                pts.getJSONObject(i).put("pos_cm", JSONObject()
                    .put("x", Math.round(p[0] * 10) / 10.0)
                    .put("y", Math.round(p[1] * 10) / 10.0)
                    .put("z", Math.round(p[2] * 10) / 10.0))
            }
        }
        return root.toString(2)
    }

    companion object {
        private val fmt = SimpleDateFormat("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE)
        fun date(t: Long): String = fmt.format(Date(t))
    }
}
