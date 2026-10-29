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

    /** Fichier binaire (image de la carte…) fourni en base64, dans Téléchargements/Coach Running. */
    @JavascriptInterface
    fun saveBase64(name: String, mime: String, b64: String): Boolean {
        return try {
            write(name, mime, android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            false
        }
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
