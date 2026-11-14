# Appel Station

1. Au lancement : active OpenVPN s'il est coupé.
2. Liste les contacts dont le nom commence par « Station » (filtre de recherche).
3. Au choix d'une station : envoie le SMS « Tut » au numéro de la fiche.
4. Attend 20 s (réglable, bouton « Maintenant » pour ne pas attendre).
5. Ouvre `http://IP`, l'IP étant lue dans le champ **e-mail** de la fiche.
6. Bouton rouge « Fermer » (en bas) ou touche Retour : coupe le VPN et ferme l'appli.
   (Fermer l'appli en la balayant depuis les applis récentes ne coupe pas le VPN :
   Android interdit d'agir sur une autre appli à ce moment-là.)

## Réglages (menu ⋮ > Réglages)
- Début du nom (Station), texte du SMS (Tut), délai (20 s)
- Adresse de connexion : `http://{ip}` par défaut (ex. `https://{ip}:8080`, `telnet://{ip}`)
- Appli VPN : OpenVPN Connect ou OpenVPN for Android
- Nom du profil VPN : s'il est renseigné, la connexion démarre seule ;
  s'il est vide, l'appli VPN s'ouvre et il suffit d'appuyer sur le bouton.

## Compiler
- **Android Studio** : ouvrir le dossier, puis Build > Build APK(s).
- **Sans rien installer** : déposer le dossier sur un dépôt GitHub ; l'onglet
  *Actions* compile l'APK, à récupérer dans « Artifacts ».

Installer l'APK sur le téléphone (autoriser « sources inconnues »), puis
accepter les autorisations Contacts et SMS au premier lancement.
