package fr.pascal.appelstation

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast

object Vpn {
    const val PKG_CONNECT = "net.openvpn.openvpn"
    const val PKG_BLINKT = "de.blinkt.openvpn"

    /** Vrai si un VPN est actuellement actif sur le téléphone. */
    @Suppress("DEPRECATION")
    fun estActif(activity: Activity): Boolean {
        val cm = activity.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    /** Appli VPN réellement installée : celle des réglages, sinon l'autre. */
    private fun appli(activity: Activity, prefs: Prefs): Int {
        fun installe(p: String) = try { activity.packageManager.getPackageInfo(p, 0); true } catch (_: Exception) { false }
        val voulue = if (prefs.appliVpn == 1) PKG_BLINKT else PKG_CONNECT
        val autre = if (prefs.appliVpn == 1) PKG_CONNECT else PKG_BLINKT
        return if (!installe(voulue) && installe(autre)) 1 - prefs.appliVpn else prefs.appliVpn
    }

    /** Coupe la connexion OpenVPN. */
    fun desactiver(activity: Activity, prefs: Prefs) {
        val choix = appli(activity, prefs)
        val intent = if (choix == 1) {
            Intent(Intent.ACTION_MAIN).apply {
                setClassName(PKG_BLINKT, "de.blinkt.openvpn.api.DisconnectVPN")
            }
        } else {
            Intent("net.openvpn.openvpn.DISCONNECT").apply {
                setClassName(PKG_CONNECT, "net.openvpn.unified.MainActivity")
                putExtra("net.openvpn.openvpn.STOP", true)
            }
        }
        try {
            if (choix == 1) activity.startActivityForResult(intent, 4243)
            else activity.startActivity(intent)
        } catch (_: Exception) {
            // à défaut : ouvrir l'appli VPN pour couper à la main
            activity.packageManager.getLaunchIntentForPackage(
                if (choix == 1) PKG_BLINKT else PKG_CONNECT
            )?.let {
                activity.startActivity(it)
                Toast.makeText(activity, "Coupez le VPN dans l'appli OpenVPN", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Au bout de 15 s, prévient si le VPN n'est toujours pas monté. */
    private fun verifierPlusTard(activity: Activity, profil: String) {
        activity.window.decorView.postDelayed({
            if (!activity.isFinishing && !estActif(activity))
                Toast.makeText(activity,
                    "VPN toujours coupé : vérifiez que le profil s'appelle exactement « $profil » dans OpenVPN for Android",
                    Toast.LENGTH_LONG).show()
        }, 15000)
    }

    /** Lance la connexion OpenVPN selon les réglages. */
    fun activer(activity: Activity, prefs: Prefs) {
        val profil = prefs.profilVpn.trim()
        val choix = appli(activity, prefs)
        val pkg = if (choix == 1) PKG_BLINKT else PKG_CONNECT

        if (profil.isNotEmpty()) {
            val intent = if (choix == 1) {
                // OpenVPN for Android : démarre directement le profil nommé
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName(PKG_BLINKT, "de.blinkt.openvpn.api.ConnectVPN")
                    putExtra("de.blinkt.openvpn.api.profileName", profil)
                }
            } else {
                // OpenVPN Connect : démarrage automatique du profil
                Intent("net.openvpn.openvpn.CONNECT").apply {
                    setClassName(PKG_CONNECT, "net.openvpn.unified.MainActivity")
                    putExtra("net.openvpn.openvpn.AUTOSTART_PROFILE_NAME", profil)
                    putExtra("net.openvpn.openvpn.AUTOSTART", true)
                    putExtra("net.openvpn.openvpn.APP_SECTION", "PC")
                }
            }
            try {
                // startActivityForResult obligatoire : OpenVPN for Android vérifie
                // quelle appli l'appelle (getCallingPackage), sinon il ignore la demande.
                if (choix == 1) activity.startActivityForResult(intent, 4242)
                else activity.startActivity(intent)
                verifierPlusTard(activity, profil)
                return
            } catch (_: Exception) {
                // on retombe sur la simple ouverture de l'appli
            }
        }

        val lancement = activity.packageManager.getLaunchIntentForPackage(pkg)
        if (lancement != null) {
            activity.startActivity(lancement)
            Toast.makeText(activity, "Activez le VPN puis revenez dans l'appli", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(activity, "Appli OpenVPN introuvable (vérifiez les réglages)", Toast.LENGTH_LONG).show()
        }
    }
}
