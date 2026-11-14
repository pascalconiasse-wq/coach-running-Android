package fr.pascal.appelstation

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone

data class Station(val id: Long, val nom: String, val telephone: String?, val ip: String?)

object Stations {
    /** Contacts dont le nom commence par le préfixe ; l'IP est lue dans le champ e-mail. */
    fun charger(context: Context, prefixe: String): List<Station> {
        val cr = context.contentResolver
        val noms = HashMap<Long, String>()
        val tels = HashMap<Long, String>()
        val ips = HashMap<Long, String>()
        val sel = "${Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$prefixe%")

        cr.query(Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.IS_PRIMARY),
            sel, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                noms[id] = c.getString(1) ?: ""
                val num = c.getString(2)
                if (num != null && (tels[id] == null || c.getInt(3) == 1)) tels[id] = num
            }
        }

        cr.query(Email.CONTENT_URI,
            arrayOf(Email.CONTACT_ID, Email.DISPLAY_NAME, Email.ADDRESS),
            "${Email.DISPLAY_NAME} LIKE ?", args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                noms.putIfAbsent(id, c.getString(1) ?: "")
                c.getString(2)?.trim()?.let { if (it.isNotEmpty()) ips[id] = it }
            }
        }

        return noms.map { (id, nom) -> Station(id, nom, tels[id], ips[id]) }
            .filter { it.nom.startsWith(prefixe, ignoreCase = true) }
            .sortedBy { it.nom.lowercase() }
    }
}
