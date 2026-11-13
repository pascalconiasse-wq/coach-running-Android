package fr.coach.running

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import org.json.JSONObject

/** Remplace le pont "AndroidBle" de la page : écran allumé et sauvegarde dans Téléchargements.
 *  Le Bluetooth direct n'est pas géré ici : la page affiche un message. */
class DeviceBridge(private val act: ComponentActivity, private val web: WebView) {

    @JavascriptInterface
    fun keepScreen(on: Boolean) {
        act.runOnUiThread {
            if (on) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @JavascriptInterface
    fun connect(kind: String) {
        val t = "Bluetooth direct non disponible dans cette appli : utilisez Chrome pour la séance en direct."
        web.post { web.evaluateJavascript("window.onNativeMsg && onNativeMsg(${JSONObject.quote(t)})", null) }
    }

    @JavascriptInterface
    fun disconnect() {}

    /** Texte (sauvegarde JSON, GPX…) dans Téléchargements/Coach Running. */
    @JavascriptInterface
    fun saveFile(name: String, data: String): Boolean {
        val mime = if (name.endsWith(".gpx")) "application/gpx+xml" else "application/json"
        return write(name, mime, data.toByteArray())
    }

    /** Partage un fichier texte (GPX…) avec une autre appli : messagerie, mail, Drive… */
    @JavascriptInterface
    fun shareFile(name: String, mime: String, data: String): Boolean {
        return try {
            val dir = java.io.File(act.cacheDir, "share")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val f = java.io.File(dir, safe)
            f.writeText(data)
            val uri = androidx.core.content.FileProvider.getUriForFile(act, act.packageName + ".files", f)
            val i = android.content.Intent(android.content.Intent.ACTION_SEND)
            i.type = mime
            i.putExtra(android.content.Intent.EXTRA_STREAM, uri)
            i.putExtra(android.content.Intent.EXTRA_SUBJECT, safe)
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.runOnUiThread { act.startActivity(android.content.Intent.createChooser(i, "Envoyer la trace")) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Fichier binaire (image de la carte…) fourni en base64, dans Téléchargements/Coach Running. */
    @JavascriptInterface
    fun saveBase64(name: String, mime: String, b64: String): Boolean {
        return try {
            write(name, mime, android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            false
        }
    }

    /** Sauvegarde automatique : écrase le même fichier à chaque fois au lieu d'en créer un nouveau. */
    @JavascriptInterface
    fun saveBackup(name: String, data: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        try {
            val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            act.contentResolver.query(
                col, arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                arrayOf(name, "Download/Coach Running%"), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val uri = android.content.ContentUris.withAppendedId(col, c.getLong(0))
                    act.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data.toByteArray()) }
                    return true
                }
            }
        } catch (e: Exception) { }
        return write(name, "application/json", data.toByteArray())
    }

    /** Relit la sauvegarde la plus récente de Téléchargements/Coach Running (automatique ou manuelle). Renvoie "" si aucune. */
    @JavascriptInterface
    fun readBackup(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        return try {
            val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            act.contentResolver.query(
                col, arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                arrayOf("%.json", "Download/Coach Running%"), MediaStore.Downloads.DATE_MODIFIED + " DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val uri = android.content.ContentUris.withAppendedId(col, c.getLong(0))
                    return act.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                }
            }
            ""
        } catch (e: Exception) { "" }
    }

    private fun write(name: String, mime: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Coach Running")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            act.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            act.contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}
