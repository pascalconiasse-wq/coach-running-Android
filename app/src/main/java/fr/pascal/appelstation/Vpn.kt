package fr.pascal.appelstation

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast

object Vpn {
    const val PKG_CONNECT = "net.openvpn.openvpn"
    const val PKG_BLINKT = "de.blinkt.openvpn"
    const val REQ_AUTORISATION = 4242

    /** Vrai si un VPN est actuellement actif sur le téléphone. */
    @Suppress("DEPRECATION")
    fun estActif(activity: Activity): Boolean {
        val cm = activity.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun installe(activity: Activity, p: String) =
        try { activity.packageManager.getPackageInfo(p, 0); true } catch (_: Exception) { false }

    /** Appli VPN réellement installée : OpenVPN for Android en priorité si présent. */
    private fun appli(activity: Activity, prefs: Prefs): Int {
        val voulue = if (prefs.appliVpn == 1) PKG_BLINKT else PKG_CONNECT
        if (installe(activity, PKG_BLINKT) && (prefs.appliVpn == 1 || !installe(activity, voulue))) return 1
        return if (installe(activity, PKG_CONNECT)) 0 else prefs.appliVpn
    }

    private fun message(activity: Activity, txt: String) =
        activity.runOnUiThread { Toast.makeText(activity, txt, Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------------
    /** Lance la connexion OpenVPN, sans afficher l'appli quand c'est possible. */
    fun activer(activity: Activity, prefs: Prefs) {
        if (appli(activity, prefs) == 1) activerBlinkt(activity, prefs)
        else activerConnect(activity, prefs)
    }

    private fun activerBlinkt(activity: Activity, prefs: Prefs) {
        OpenVpnApi.avecService(activity) { b ->
            if (b == null) { ouvrirAppli(activity, PKG_BLINKT); return@avecService }
            try {
                // 1re fois : OpenVPN demande d'autoriser Appel Station
                OpenVpnApi.prepare(b, activity.packageName)?.let {
                    activity.startActivityForResult(it, REQ_AUTORISATION)
                    return@avecService
                }
                val profils = OpenVpnApi.profils(b)
                if (profils.isEmpty()) { message(activity, "Aucun profil dans OpenVPN for Android"); return@avecService }
                val voulu = prefs.profilVpn.trim()
                val p = profils.firstOrNull { it.nom.trim().equals(voulu, true) }
                    ?: profils.firstOrNull { voulu.isNotEmpty() && it.nom.contains(voulu, true) }
                    ?: profils.first()
                if (voulu.isNotEmpty() && !p.nom.trim().equals(voulu, true))
                    message(activity, "Profil « $voulu » introuvable, utilisation de « ${p.nom} »")
                OpenVpnApi.demarrer(b, p.uuid)
                message(activity, "Connexion VPN « ${p.nom} »…")
            } catch (e: Exception) {
                message(activity, "OpenVPN : ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun activerConnect(activity: Activity, prefs: Prefs) {
        val profil = prefs.profilVpn.trim()
        if (profil.isNotEmpty()) {
            try {
                activity.startActivity(Intent("net.openvpn.openvpn.CONNECT").apply {
                    setClassName(PKG_CONNECT, "net.openvpn.unified.MainActivity")
                    putExtra("net.openvpn.openvpn.AUTOSTART_PROFILE_NAME", profil)
                    putExtra("net.openvpn.openvpn.AUTOSTART", true)
                    putExtra("net.openvpn.openvpn.APP_SECTION", "PC")
                })
                return
            } catch (_: Exception) { }
        }
        ouvrirAppli(activity, PKG_CONNECT)
    }

    // ------------------------------------------------------------------
    /** Coupe la connexion OpenVPN, puis exécute [ensuite]. */
    fun desactiver(activity: Activity, prefs: Prefs, ensuite: () -> Unit) {
        if (appli(activity, prefs) == 1) {
            OpenVpnApi.avecService(activity) { b ->
                try { if (b != null) OpenVpnApi.deconnecter(b) } catch (_: Exception) { }
                activity.runOnUiThread(ensuite)
            }
        } else {
            try {
                activity.startActivity(Intent("net.openvpn.openvpn.DISCONNECT").apply {
                    setClassName(PKG_CONNECT, "net.openvpn.unified.MainActivity")
                    putExtra("net.openvpn.openvpn.STOP", true)
                })
            } catch (_: Exception) {
                ouvrirAppli(activity, PKG_CONNECT)
            }
            ensuite()
        }
    }

    private fun ouvrirAppli(activity: Activity, pkg: String) {
        val lancement = activity.packageManager.getLaunchIntentForPackage(pkg)
        if (lancement != null) {
            activity.startActivity(lancement)
            message(activity, "Activez le VPN puis revenez dans l'appli")
        } else {
            message(activity, "Appli OpenVPN introuvable")
        }
    }
}
