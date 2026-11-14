package fr.pascal.appelstation

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var edPrefixe: EditText
    private lateinit var edSms: EditText
    private lateinit var edDelai: EditText
    private lateinit var edUrl: EditText
    private lateinit var spVpn: Spinner
    private lateinit var edProfil: EditText
    private lateinit var cbAuto: CheckBox
    private lateinit var cbCoupe: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String) = col.addView(TextView(this).apply {
            text = t; setPadding(0, pad, 0, 0); textSize = 14f
        })
        fun champ(valeur: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            setText(valeur); inputType = type; col.addView(this, MATCH_PARENT, WRAP_CONTENT)
        }

        label("Début du nom des contacts")
        edPrefixe = champ(prefs.prefixe)
        label("Texte du SMS")
        edSms = champ(prefs.texteSms)
        label("Attente avant connexion (secondes)")
        edDelai = champ(prefs.delaiSecondes.toString(), InputType.TYPE_CLASS_NUMBER)
        label("Adresse de connexion ({ip} = IP de la fiche)")
        edUrl = champ(prefs.modeleUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        label("Appli VPN")
        spVpn = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("OpenVPN Connect", "OpenVPN for Android"))
            setSelection(prefs.appliVpn)
        }
        col.addView(spVpn, MATCH_PARENT, WRAP_CONTENT)
        label("Nom du profil VPN (vide = ouvrir l'appli seulement)")
        edProfil = champ(prefs.profilVpn)
        cbAuto = CheckBox(this).apply {
            text = "Activer le VPN au lancement s'il est coupé"
            isChecked = prefs.vpnAuDemarrage
        }
        col.addView(cbAuto)
        cbCoupe = CheckBox(this).apply {
            text = "Couper le VPN en quittant l'appli"
            isChecked = prefs.vpnCoupeEnQuittant
        }
        col.addView(cbCoupe)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onPause() {
        super.onPause()
        prefs.prefixe = edPrefixe.text.toString().ifBlank { "Station" }
        prefs.texteSms = edSms.text.toString().ifBlank { "Tut" }
        prefs.delaiSecondes = edDelai.text.toString().toIntOrNull()?.coerceIn(0, 600) ?: 20
        prefs.modeleUrl = edUrl.text.toString().ifBlank { "http://{ip}" }
        prefs.appliVpn = spVpn.selectedItemPosition
        prefs.profilVpn = edProfil.text.toString()
        prefs.vpnAuDemarrage = cbAuto.isChecked
        prefs.vpnCoupeEnQuittant = cbCoupe.isChecked
    }
}
