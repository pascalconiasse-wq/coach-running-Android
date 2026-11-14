package fr.pascal.appelstation

import android.content.Context

/** Réglages de l'appli, stockés dans SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    var prefixe: String
        get() = sp.getString("prefixe", "Station") ?: "Station"
        set(v) = sp.edit().putString("prefixe", v).apply()

    var texteSms: String
        get() = sp.getString("texteSms", "Tut") ?: "Tut"
        set(v) = sp.edit().putString("texteSms", v).apply()

    var delaiSecondes: Int
        get() = sp.getInt("delai", 20)
        set(v) = sp.edit().putInt("delai", v).apply()

    /** Modèle d'adresse ; {ip} est remplacé par l'IP de la fiche. */
    var modeleUrl: String
        get() = sp.getString("modeleUrl", "http://{ip}") ?: "http://{ip}"
        set(v) = sp.edit().putString("modeleUrl", v).apply()

    /** 0 = OpenVPN Connect, 1 = OpenVPN for Android */
    var appliVpn: Int
        get() = sp.getInt("appliVpn", 1)
        set(v) = sp.edit().putInt("appliVpn", v).apply()

    /** Nom du profil (facultatif : vide = juste ouvrir l'appli VPN). */
    var profilVpn: String
        get() = sp.getString("profilVpn", "") ?: ""
        set(v) = sp.edit().putString("profilVpn", v).apply()

    var vpnCoupeEnQuittant: Boolean
        get() = sp.getBoolean("vpnCoupeEnQuittant", true)
        set(v) = sp.edit().putBoolean("vpnCoupeEnQuittant", v).apply()

    var vpnAuDemarrage: Boolean
        get() = sp.getBoolean("vpnAuDemarrage", true)
        set(v) = sp.edit().putBoolean("vpnAuDemarrage", v).apply()
}
