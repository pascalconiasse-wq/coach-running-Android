package fr.pascal.appelstation

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

/**
 * Notice et aide contextuelle, lues dans assets/notice.txt
 * (même fichier que celui qui sert à fabriquer la notice PDF).
 * Format : « # [id] Titre », « ## Sous-titre », « - puce », texte libre.
 */
object Notice {
    private data class Section(val id: String, val titre: String, val lignes: List<String>)

    private fun sections(activity: Activity): List<Section> {
        val texte = activity.assets.open("notice.txt").bufferedReader().use { it.readText() }
        val res = mutableListOf<Section>()
        var id = ""; var titre = ""; var lignes = mutableListOf<String>()
        for (l in texte.lines()) {
            val m = Regex("""^# \[(\w+)] (.*)$""").find(l)
            if (m != null) {
                if (titre.isNotEmpty()) res.add(Section(id, titre, lignes))
                id = m.groupValues[1]; titre = m.groupValues[2]; lignes = mutableListOf()
            } else lignes.add(l)
        }
        if (titre.isNotEmpty()) res.add(Section(id, titre, lignes))
        return res
    }

    private fun mettreEnForme(secs: List<Section>, avecTitres: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        fun gras(t: String, taille: Float) {
            val d = sb.length
            sb.append(t).append("\n")
            sb.setSpan(StyleSpan(Typeface.BOLD), d, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(taille), d, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (s in secs) {
            if (avecTitres) gras(s.titre, 1.15f)
            for (l in s.lignes) when {
                l.startsWith("## ") -> gras(l.removePrefix("## "), 1.0f)
                l.startsWith("- ") -> sb.append("•  ").append(l.removePrefix("- ")).append("\n")
                l.isBlank() -> sb.append("\n")
                else -> sb.append(l).append("\n")
            }
        }
        return sb
    }

    private fun afficher(activity: Activity, titre: String, contenu: CharSequence) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val tv = TextView(activity).apply {
            text = contenu; textSize = 15f; setPadding(pad, pad / 2, pad, pad)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(activity)
            .setTitle(titre)
            .setView(ScrollView(activity).apply { addView(tv) })
            .setPositiveButton("OK", null)
            .show()
    }

    /** Notice complète. */
    fun complete(activity: Activity) {
        val v = MiseAJour.versionNom(activity)
        afficher(activity, "Notice Appel Station $v", mettreEnForme(sections(activity), true))
    }

    /** Aide contextuelle : une ou plusieurs sections de la notice. */
    fun aide(activity: Activity, vararg ids: String) {
        val secs = sections(activity).filter { it.id in ids }
        if (secs.isEmpty()) return
        afficher(activity, secs.first().titre, mettreEnForme(secs, secs.size > 1))
    }

    /** Petit bouton « ? » qui ouvre l'aide des sections données. */
    fun bouton(activity: Activity, vararg ids: String) = Button(activity).apply {
        text = "?"
        textSize = 16f
        minWidth = 0; minimumWidth = 0
        setOnClickListener { aide(activity, *ids) }
    }
}
