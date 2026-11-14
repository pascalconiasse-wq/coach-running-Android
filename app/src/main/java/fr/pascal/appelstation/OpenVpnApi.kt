package fr.pascal.appelstation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel

/**
 * Pilotage d'« OpenVPN for Android » par son service AIDL officiel
 * (de.blinkt.openvpn.api.IOpenVPNAPIService) : connexion / déconnexion
 * en arrière-plan, sans afficher l'appli OpenVPN.
 */
object OpenVpnApi {
    private const val PKG = "de.blinkt.openvpn"
    private const val DESC = "de.blinkt.openvpn.api.IOpenVPNAPIService"
    // Ordre des méthodes de l'interface AIDL
    private const val TX_GET_PROFILES = IBinder.FIRST_CALL_TRANSACTION + 0
    private const val TX_START_PROFILE = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val TX_PREPARE = IBinder.FIRST_CALL_TRANSACTION + 4
    private const val TX_DISCONNECT = IBinder.FIRST_CALL_TRANSACTION + 6

    data class Profil(val uuid: String, val nom: String)

    private var binder: IBinder? = null
    private var connexion: ServiceConnection? = null
    private val enAttente = mutableListOf<(IBinder?) -> Unit>()

    /** Exécute [action] une fois connecté au service (null si impossible). */
    fun avecService(context: Context, action: (IBinder?) -> Unit) {
        binder?.takeIf { it.isBinderAlive }?.let { action(it); return }
        enAttente.add(action)
        if (connexion != null) return
        val cx = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                enAttente.toList().also { enAttente.clear() }.forEach { it(service) }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
        val ok = try {
            context.applicationContext.bindService(
                Intent(DESC).setPackage(PKG), cx, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) { false }
        if (ok) connexion = cx else {
            enAttente.toList().also { enAttente.clear() }.forEach { it(null) }
        }
    }

    private fun <T> appel(b: IBinder, code: Int, ecrire: (Parcel) -> Unit, lire: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC)
            ecrire(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            return lire(reply)
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** null = Appel Station est autorisée ; sinon Intent de la fenêtre d'autorisation. */
    fun prepare(b: IBinder, monPaquet: String): Intent? =
        appel(b, TX_PREPARE, { it.writeString(monPaquet) }) { r ->
            if (r.readInt() != 0) Intent.CREATOR.createFromParcel(r) else null
        }

    fun profils(b: IBinder): List<Profil> = appel(b, TX_GET_PROFILES, {}) { r ->
        val n = r.readInt()
        val liste = mutableListOf<Profil>()
        repeat(maxOf(n, 0)) {
            if (r.readInt() == 0) return@repeat          // élément null
            val uuid = r.readString() ?: ""
            val nom = r.readString() ?: ""
            r.readInt()                                   // modifiable
            // Certaines versions ajoutent un champ texte (créateur du profil)
            if (it < n - 1) {
                val pos = r.dataPosition()
                if (r.readInt() != 1) { r.setDataPosition(pos); r.readString() }
                else r.setDataPosition(pos)
            }
            liste.add(Profil(uuid, nom))
        }
        liste
    }

    fun demarrer(b: IBinder, uuid: String) {
        appel(b, TX_START_PROFILE, { it.writeString(uuid) }) { }
    }

    fun deconnecter(b: IBinder) {
        appel(b, TX_DISCONNECT, {}) { }
    }
}
