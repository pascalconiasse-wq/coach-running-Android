package fr.pascal.appelstation

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone

data class Station(
    val id: Long,
    val nom: String,
    val telephone: String?,
    val ip: String?,
    /** Champs bruts de la fiche, pour le diagnostic si l'IP est introuvable. */
    val champs: List<String> = emptyList()
)

object Stations {
    private val regexIp = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})(?::\d{1,5})?\b""")

    fun charger(context: Context, prefixe: String): List<Station> {
        val cr = context.contentResolver

        // 1. Contacts dont le nom commence par le préfixe
        val noms = HashMap<Long, String>()
        cr.query(ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("$prefixe%"), null)?.use { c ->
            while (c.moveToNext()) noms[c.getLong(0)] = c.getString(1) ?: ""
        }
        if (noms.isEmpty()) return emptyList()

        // 2. Tous les champs de ces fiches (téléphone, e-mail, site, notes…)
        val tels = HashMap<Long, String>()
        val ipsMail = HashMap<Long, String>()
        val ipsAutres = HashMap<Long, String>()
        val champs = HashMap<Long, MutableList<String>>()
        val ids = noms.keys.joinToString(",")

        cr.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.IS_PRIMARY),
            "${ContactsContract.Data.CONTACT_ID} IN ($ids)", null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val type = c.getString(1) ?: ""
                val valeur = c.getString(2)?.trim() ?: continue
                if (valeur.isEmpty()) continue
                champs.getOrPut(id) { mutableListOf() }
                    .add("${type.substringAfterLast('/')} : $valeur")

                when (type) {
                    Phone.CONTENT_ITEM_TYPE ->
                        if (tels[id] == null || c.getInt(3) == 1) tels[id] = valeur
                    else -> {
                        // l'IP peut être saisie avec des espaces, des virgules, http://…
                        val propre = valeur.replace(',', '.').replace(" ", "")
                        val ip = regexIp.find(propre)?.value ?: continue
                        if (type == Email.CONTENT_ITEM_TYPE) ipsMail.putIfAbsent(id, ip)
                        else ipsAutres.putIfAbsent(id, ip)
                    }
                }
            }
        }

        return noms.map { (id, nom) ->
            Station(id, nom, tels[id], ipsMail[id] ?: ipsAutres[id], champs[id] ?: emptyList())
        }.sortedBy { it.nom.lowercase() }
    }
}
