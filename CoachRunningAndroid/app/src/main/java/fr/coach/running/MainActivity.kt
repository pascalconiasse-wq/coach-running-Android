package fr.coach.running

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.health.connect.client.PermissionController

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var health: HealthBridge

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
        web.webViewClient = WebViewClient()
        health = HealthBridge(this, web) { perms -> permLauncher.launch(perms) }
        web.addJavascriptInterface(health, "AndroidHealth")
        // Pont minimal (écran allumé, sauvegarde). Si vous avez déjà votre propre pont
        // "AndroidBle" avec la connexion Bluetooth, gardez le vôtre et supprimez cette ligne.
        web.addJavascriptInterface(DeviceBridge(this, web), "AndroidBle")
        web.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
