package fr.pascal.appelstation

import android.app.Activity
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Cherche sur GitHub une version d'Appel Station plus récente que celle installée. */
object MiseAJour {
    private const val API =
        "https://api.github.com/repos/pascalconiasse-wq/coach-running-Android/releases?per_page=100"
    private const val PREFIXE = "appel-station-v"

    data class Version(val numero: Int, val lienApk: String)

    fun versionInstallee(activity: Activity): Int =
        activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode.toInt()

    /** [resultat] reçoit la nouvelle version, ou null si à jour / pas de réseau. */
    fun verifier(activity: Activity, resultat: (Version?) -> Unit) {
        Thread {
            val trouvee = try {
                val cx = URL(API).openConnection() as HttpURLConnection
                cx.connectTimeout = 8000
                cx.readTimeout = 8000
                cx.setRequestProperty("Accept", "application/vnd.github+json")
                val texte = cx.inputStream.bufferedReader().use { it.readText() }
                val versions = JSONArray(texte)
                var meilleure: Version? = null
                for (i in 0 until versions.length()) {
                    val r = versions.getJSONObject(i)
                    val tag = r.optString("tag_name")
                    if (!tag.startsWith(PREFIXE)) continue
                    val n = tag.removePrefix(PREFIXE).toIntOrNull() ?: continue
                    val assets = r.optJSONArray("assets") ?: continue
                    val lien = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                        ?.optString("browser_download_url") ?: continue
                    if (meilleure == null || n > meilleure.numero) meilleure = Version(n, lien)
                }
                meilleure?.takeIf { it.numero > versionInstallee(activity) }
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread { if (!activity.isFinishing) resultat(trouvee) }
        }.start()
    }
}
