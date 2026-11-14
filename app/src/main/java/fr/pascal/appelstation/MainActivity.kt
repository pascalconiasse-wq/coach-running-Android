package fr.pascal.appelstation

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.telephony.SmsManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var txtVpn: TextView
    private lateinit var btnVpn: Button
    private lateinit var recherche: EditText
    private lateinit var liste: ListView
    private lateinit var txtVide: TextView
    private var auPremierPlan = false
    /** Connexion à faire dès que l'appli revient au premier plan. */
    private var connexionEnAttente: Station? = null
    private var toutes: List<Station> = emptyList()
    private var affichees: List<Station> = emptyList()
    private var vpnDejaLance = false
    private var compteARebours: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (12 * resources.displayMetrics.density).toInt()

        val bandeau = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        txtVpn = TextView(this).apply { textSize = 16f }
        btnVpn = Button(this).apply {
            text = "Activer VPN"
            setOnClickListener { Vpn.activer(this@MainActivity, prefs) }
        }
        val btnFermer = Button(this).apply {
            text = "Fermer"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(200, 40, 40))
            setOnClickListener { quitter() }
        }
        bandeau.addView(txtVpn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bandeau.addView(btnVpn)
        bandeau.addView(btnFermer, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = pad / 2
        })

        recherche = EditText(this).apply {
            hint = "Nom de la station…"
            textSize = 20f
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = filtrer()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        liste = ListView(this).apply {
            setOnItemClickListener { _, _, pos, _ -> choisir(affichees[pos]) }
        }
        txtVide = TextView(this).apply {
            text = "Tapez le nom ou le début du nom de la station"
            gravity = Gravity.CENTER
            setPadding(pad, pad * 3, pad, pad)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bandeau, MATCH_PARENT, WRAP_CONTENT)
            addView(recherche, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(pad, 0, pad, 0)
            })
            addView(liste, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(txtVide, MATCH_PARENT, WRAP_CONTENT)
        })

        liste.emptyView = txtVide
        demanderPermissions()
        recherche.requestFocus()
    }

    override fun onPause() {
        auPremierPlan = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        auPremierPlan = true
        majVpn()
        if (toutes.isEmpty() && aLaPermission(Manifest.permission.READ_CONTACTS)) chargerStations()
        connexionEnAttente?.let { connexionEnAttente = null; connecter(it); return }
        if (prefs.vpnAuDemarrage && !vpnDejaLance && !Vpn.estActif(this)) {
            vpnDejaLance = true
            Vpn.activer(this, prefs)
        }
    }

    /** Touche Retour = quitter l'appli (et couper le VPN). */
    @Deprecated("Activity simple, sans retour prédictif")
    override fun onBackPressed() = quitter()

    private fun quitter() {
        compteARebours?.cancel()
        if (prefs.vpnCoupeEnQuittant && Vpn.estActif(this))
            Vpn.desactiver(this, prefs) { finishAndRemoveTask() }
        else finishAndRemoveTask()
    }

    override fun onDestroy() {
        compteARebours?.cancel()
        super.onDestroy()
    }

    // ---------- Menu ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Actualiser")
        menu.add(0, 2, 1, "Réglages")
        menu.add(0, 3, 2, "Diagnostic VPN")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { majVpn(); chargerStations() }
            2 -> startActivity(Intent(this, SettingsActivity::class.java))
            3 -> Vpn.diagnostic(this, prefs) { txt ->
                AlertDialog.Builder(this).setTitle("Diagnostic VPN").setMessage(txt)
                    .setPositiveButton("OK", null).show()
            }
        }
        return true
    }

    // ---------- Permissions ----------
    private fun aLaPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun demanderPermissions() {
        val manquantes = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
            .filterNot { aLaPermission(it) }
        if (manquantes.isNotEmpty()) requestPermissions(manquantes.toTypedArray(), 10)
    }

    @Deprecated("Activity simple")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Autorisation donnée dans OpenVPN for Android : on lance la connexion
        if (requestCode == Vpn.REQ_AUTORISATION) {
            if (resultCode == RESULT_OK) Vpn.activer(this, prefs)
            else Toast.makeText(this, "Autorisation OpenVPN refusée ou annulée", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (aLaPermission(Manifest.permission.READ_CONTACTS)) chargerStations()
        else Toast.makeText(this, "Accès aux contacts refusé", Toast.LENGTH_LONG).show()
    }

    // ---------- Affichage ----------
    private fun majVpn() {
        val actif = Vpn.estActif(this)
        txtVpn.text = if (actif) "● VPN actif" else "● VPN coupé"
        txtVpn.setTextColor(if (actif) Color.rgb(0, 140, 60) else Color.rgb(200, 40, 40))
    }

    private fun chargerStations() {
        toutes = Stations.charger(this, prefs.prefixe)
        filtrer()
        if (toutes.isEmpty())
            Toast.makeText(this, "Aucun contact commençant par « ${prefs.prefixe} »", Toast.LENGTH_LONG).show()
    }

    private fun filtrer() {
        val f = recherche.text.toString().trim()
        affichees = if (f.isEmpty()) emptyList() else toutes.filter { correspond(it.nom, f) }
        liste.adapter = object : ArrayAdapter<Station>(this, android.R.layout.simple_list_item_2, android.R.id.text1, affichees) {
            override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup) =
                super.getView(pos, convertView, parent).also { v ->
                    val s = affichees[pos]
                    v.findViewById<TextView>(android.R.id.text1).text = s.nom
                    v.findViewById<TextView>(android.R.id.text2).text =
                        "${s.telephone ?: "pas de n°"}   ·   IP : ${s.ip ?: "absente"}"
                }
        }
    }

    /** Vrai si le nom (avec ou sans « Station ») ou l'un de ses mots commence par la saisie. */
    private fun correspond(nom: String, saisie: String): Boolean {
        val sansPrefixe = nom.removePrefix(prefs.prefixe).trimStart(' ', '-', '_', ':')
        if (nom.startsWith(saisie, true) || sansPrefixe.startsWith(saisie, true)) return true
        return sansPrefixe.split(' ', '-', '_', '\'', '(').any { it.startsWith(saisie, true) }
    }

    // ---------- Déroulé ----------
    private fun choisir(s: Station) {
        if (s.telephone.isNullOrBlank()) { erreur("${s.nom} n'a pas de numéro."); return }
        if (s.ip.isNullOrBlank()) {
            val detail = if (s.champs.isEmpty()) "aucun champ lu" else s.champs.joinToString("\n")
            erreur("Aucune adresse IP trouvée pour ${s.nom}.\n\nChamps lus dans la fiche :\n$detail")
            return
        }

        // Tout s'enchaîne seul : VPN (si coupé), SMS, attente, connexion
        if (!Vpn.estActif(this)) Vpn.activer(this, prefs)
        envoyerSms(s)
    }

    private fun envoyerSms(s: Station) {
        if (!aLaPermission(Manifest.permission.SEND_SMS)) {
            erreur("Permission SMS refusée."); demanderPermissions(); return
        }
        try {
            val sms: SmsManager = if (android.os.Build.VERSION.SDK_INT >= 31)
                getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendTextMessage(s.telephone, null, prefs.texteSms, null, null)
            Toast.makeText(this, "SMS envoyé à ${s.nom}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            erreur("Échec de l'envoi du SMS : ${e.message}"); return
        }
        attendrePuisConnecter(s)
    }

    private fun attendrePuisConnecter(s: Station) {
        val delai = prefs.delaiSecondes
        if (delai <= 0) { connecter(s); return }

        val dlg = AlertDialog.Builder(this)
            .setTitle(s.nom)
            .setMessage("Connexion dans $delai s…")
            .setPositiveButton("Maintenant", null)
            .setNegativeButton("Annuler") { _, _ -> compteARebours?.cancel() }
            .setCancelable(false)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                compteARebours?.cancel(); dlg.dismiss(); connecter(s)
            }
        }
        dlg.show()

        compteARebours?.cancel()
        compteARebours = object : CountDownTimer(delai * 1000L, 1000L) {
            override fun onTick(reste: Long) {
                dlg.setMessage("Connexion dans ${(reste + 999) / 1000} s…")
            }
            override fun onFinish() {
                if (dlg.isShowing) dlg.dismiss()
                // Android interdit d'ouvrir le navigateur depuis l'arrière-plan :
                // si l'appli VPN est devant, on connecte au retour dans l'appli.
                if (auPremierPlan) connecter(s) else {
                    connexionEnAttente = s
                    Toast.makeText(this@MainActivity, "Revenez dans Appel Station pour la connexion", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun connecter(s: Station) {
        val url = prefs.modeleUrl.replace("{ip}", s.ip!!.trim())
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            erreur("Aucune appli ne sait ouvrir : $url")
        }
    }

    private fun erreur(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
    }
}
