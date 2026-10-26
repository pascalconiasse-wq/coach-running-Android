package fr.coach.running

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.health.connect.client.PermissionController
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var health: HealthBridge
    private var pageReady = false
    private val pending = mutableListOf<String>()   // fichiers partagés en attente du chargement de la page

    private val permLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> health.onPermissions(granted) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pending.forEach { push(it) }
                pending.clear()
            }
        }
        health = HealthBridge(this, web) { perms -> permLauncher.launch(perms) }
        web.addJavascriptInterface(health, "AndroidHealth")
        // Pont minimal (écran allumé, sauvegarde). Si vous avez déjà votre propre pont
        // "AndroidBle" avec la connexion Bluetooth, gardez le vôtre et supprimez cette ligne.
        web.addJavascriptInterface(DeviceBridge(this, web), "AndroidBle")
        web.loadUrl("file:///android_asset/index.html")

        addOnNewIntentListener { handleShare(it) }   // appli déjà ouverte
        handleShare(intent)                          // appli lancée par un partage
    }

    /** Reçoit un ou plusieurs fichiers (FIT, GPX, TCX) via « Partager » et les envoie à la page. */
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> return
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Aucun fichier reçu : partagez le fichier de la sortie (FIT, GPX ou TCX).", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val arr = JSONArray()
            for (u in uris) {
                try {
                    val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: continue
                    var name = "sortie"
                    contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) name = it.getString(0) ?: name
                    }
                    arr.put(JSONObject().put("name", name).put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                } catch (e: Exception) { /* fichier illisible : ignoré */ }
            }
            runOnUiThread { push(arr.toString()) }
        }.start()
    }

    private fun push(json: String) {
        if (!pageReady) { pending.add(json); return }
        web.evaluateJavascript("window.onSharedFiles && onSharedFiles($json)", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
