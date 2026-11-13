package fr.coach.running

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.health.connect.client.PermissionController
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var health: HealthBridge
    private var pageReady = false
    private val pending = mutableListOf<String>()   // fichiers partagés en attente du chargement de la page

    // Sélecteur de fichiers : sans lui, les boutons « Importer » de la page ne font rien dans la WebView.
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        val uris: Array<Uri>? = if (r.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            val one = data.data
            when {
                clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                one != null -> arrayOf(one)
                else -> null
            }
        } else null
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    // Position (GPS) : demandée à Android, puis transmise à la page
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private val locLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        geoCallback?.invoke(geoOrigin, ok, false)
        geoCallback = null
    }

    // Autorisations demandées par la page (position, notifications, Bluetooth)
    private var permCallback: (() -> Unit)? = null
    private val multiPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val cb = permCallback
        permCallback = null
        cb?.invoke()
    }
    /** Sélecteur de contact système : ne demande aucune autorisation sur les contacts. */
    private val contactPick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            try {
                contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val js = "window.onContact && onContact(" + org.json.JSONObject.quote(c.getString(0) ?: "") + "," + org.json.JSONObject.quote(c.getString(1) ?: "") + ")"
                        web.post { web.evaluateJavascript(js, null) }
                    }
                }
            } catch (e: Exception) { }
        }
    }
    /** Sélecteur de fichier ouvert directement dans Téléchargements/Coach Running ; le contenu revient à la page via onBackupText. */
    private val backupPick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            try {
                val t = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                web.post { web.evaluateJavascript("window.onBackupText && onBackupText(" + org.json.JSONObject.quote(t) + ")", null) }
            } catch (e: Exception) { }
        }
    }
    fun pickBackup() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FCoach%20Running"))
            }
            backupPick.launch(i)
        } catch (e: Exception) { }
    }
    fun pickContact() {
        try { contactPick.launch(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) } catch (e: Exception) { }
    }
    private fun askPerms(p: Array<String>, then: () -> Unit) {
        val missing = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else { permCallback = then; multiPerm.launch(missing.toTypedArray()) }
    }

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
        web.settings.setGeolocationEnabled(true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pending.forEach { push(it) }
                pending.clear()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted) { callback?.invoke(origin, true, false); return }
                geoOrigin = origin
                geoCallback = callback
                locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                return try {
                    chooser.launch(Intent.createChooser(intent, "Choisir un fichier"))
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        health = HealthBridge(this, web) { perms -> permLauncher.launch(perms) }
        web.addJavascriptInterface(health, "AndroidHealth")
        // Pont minimal (écran allumé, sauvegarde). Si vous avez déjà votre propre pont
        // "AndroidBle" avec la connexion Bluetooth, gardez le vôtre et supprimez cette ligne.
        web.addJavascriptInterface(DeviceBridge(this, web), "AndroidBle")
        web.addJavascriptInterface(TrackBridge(this, web) { p, t -> askPerms(p, t) }, "AndroidTrack")
        web.addJavascriptInterface(AppBridge(this, web), "AndroidApp")   // numéro de version + ouverture du lien de mise à jour
        if (Build.VERSION.SDK_INT >= 33) askPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { }   // notification du dimanche soir
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
        // Pendant une séance, la page décide (confirmation avant de quitter)
        web.evaluateJavascript("window.onAndroidBack ? onAndroidBack() : false") { r ->
            if (r != "true") {
                if (web.canGoBack()) web.goBack() else super@MainActivity.onBackPressed()
            }
        }
    }
}

/** Pont pour la mise à jour : la page lit la version installée et ouvre le téléchargement de l'APK. */
class AppBridge(private val act: MainActivity, private val web: android.webkit.WebView) {
    /** Interroge GitHub depuis Android (pas de limite CORS) et rappelle la page : onLatest(tag, erreur). */
    @android.webkit.JavascriptInterface
    fun checkLatest(repo: String) {
        Thread {
            var tag = ""
            var err = ""
            try {
                val c = java.net.URL("https://api.github.com/repos/" + repo + "/releases/latest").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "CoachRunning")
                if (c.responseCode == 200) {
                    val body = c.inputStream.bufferedReader().readText()
                    tag = org.json.JSONObject(body).optString("tag_name", "")
                } else err = "HTTP " + c.responseCode
            } catch (e: Exception) {
                err = e.javaClass.simpleName
            }
            web.post { web.evaluateJavascript("window.onLatest && onLatest(" + org.json.JSONObject.quote(tag) + "," + org.json.JSONObject.quote(err) + ")", null) }
        }.start()
    }

    /** Programme la notification du dimanche soir avec le texte fourni par la page. */
    @android.webkit.JavascriptInterface
    fun scheduleWeekly(text: String, atMs: Long) {
        try {
            val ctx = act.applicationContext
            ctx.getSharedPreferences("weekly", android.content.Context.MODE_PRIVATE).edit().putString("text", text).apply()
            val am = ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, WeeklyReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (e: Exception) { }
    }

    @android.webkit.JavascriptInterface
    fun version(): String = try {
        act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    @android.webkit.JavascriptInterface
    fun open(url: String) {
        act.runOnUiThread {
            try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
        }
    }

    @android.webkit.JavascriptInterface
    fun pickBackup() { act.runOnUiThread { act.pickBackup() } }

    @android.webkit.JavascriptInterface
    fun pickContact() { act.runOnUiThread { act.pickContact() } }

    @Suppress("DEPRECATION")
    private fun smsManager(): android.telephony.SmsManager =
        if (Build.VERSION.SDK_INT >= 31) act.getSystemService(android.telephony.SmsManager::class.java) else android.telephony.SmsManager.getDefault()

    /** Dernière position connue ("lat,lon") ou "" si pas d'autorisation / pas de position. */
    @android.webkit.JavascriptInterface
    fun lastLocation(): String {
        try {
            val cc = androidx.core.content.ContextCompat.checkSelfPermission(act, android.Manifest.permission.ACCESS_FINE_LOCATION)
            val cd = androidx.core.content.ContextCompat.checkSelfPermission(act, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (cc != android.content.pm.PackageManager.PERMISSION_GRANTED && cd != android.content.pm.PackageManager.PERMISSION_GRANTED) return ""
            val lm = act.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            var best: android.location.Location? = null
            for (p in lm.getProviders(true)) {
                val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
                if (l != null && (best == null || l.time > best.time)) best = l
            }
            val b = best ?: return ""
            return b.latitude.toString() + "," + b.longitude.toString()
        } catch (e: Exception) {
            return ""
        }
    }

    @android.webkit.JavascriptInterface
    fun smsOk(): Boolean = androidx.core.content.ContextCompat.checkSelfPermission(act, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @android.webkit.JavascriptInterface
    fun callOk(): Boolean = androidx.core.content.ContextCompat.checkSelfPermission(act, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Ouvre l'écran Android « Infos de l'appli » (autorisations, paramètres restreints). */
    @android.webkit.JavascriptInterface
    fun openSettings() {
        act.runOnUiThread {
            try {
                act.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + act.packageName)))
            } catch (e: Exception) { }
        }
    }

    /** Autorisation d'appeler (SOS). Renvoie "ok" si déjà accordée, sinon "perm" (demande affichée). */
    @android.webkit.JavascriptInterface
    fun askCall(): String {
        if (androidx.core.content.ContextCompat.checkSelfPermission(act, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) return "ok"
        act.runOnUiThread { androidx.core.app.ActivityCompat.requestPermissions(act, arrayOf(android.Manifest.permission.CALL_PHONE), 78) }
        return "perm"
    }

    /** Envoie un SMS à chaque numéro (séparés par des virgules). Renvoie "ok", "perm" (autorisation demandée) ou "err". */
    @android.webkit.JavascriptInterface
    fun sendSms(numbers: String, text: String): String {
        if (androidx.core.content.ContextCompat.checkSelfPermission(act, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.runOnUiThread { androidx.core.app.ActivityCompat.requestPermissions(act, arrayOf(android.Manifest.permission.SEND_SMS), 77) }
            return "perm"
        }
        if (text.isEmpty()) return "ok"
        return try {
            val sm = smsManager()
            var n = 0
            for (num in numbers.split(",", ";")) {
                val t = num.trim().replace(" ", "")
                if (t.length < 4) continue
                sm.sendMultipartTextMessage(t, null, sm.divideMessage(text), null, null)
                n++
            }
            if (n > 0) "ok" else "err"
        } catch (e: Exception) { "err" }
    }
}

