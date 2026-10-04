# Déploiement de Surprise ♥️ (téléphone seulement, sans carte bancaire)

- **Supabase** (gratuit, sans carte) stocke les surprises. Pas de serveur à gérer.
- **GitHub Actions** compile l'APK.

> Astuce : dans Chrome, menu ⋮ > **Version pour ordinateur** sur GitHub et Supabase. Tous les menus y sont disponibles.

## 0. Ce qu'il vous faut
- Un compte **GitHub** (gratuit)
- Les fichiers : `Surprise.zip`, `unpack.yml`, `build-apk.yml`, `supabase.sql`

---

## Phase 1. Le dépôt GitHub

*(Si vous avez déjà fait les phases 1 à 3 avant, passez à « Si le projet est déjà importé ».)*

1. github.com > **+** > **New repository**. Nom : `surprise`, **Public**, cochez **Add a README file**, **Create repository**.
2. **Add file** > **Create new file**. Nom : `.github/workflows/unpack.yml` (taper les `/` crée les dossiers). Collez le contenu de `unpack.yml`, puis **Commit changes**.
3. Recommencez avec `.github/workflows/build-apk.yml` et le contenu de `build-apk.yml`.
4. **Add file** > **Upload files** > `Surprise.zip` (à la racine) > **Commit changes**.
5. Onglet **Actions** > **Importer le projet** > **Run workflow** (bouton vert). Attendez la coche verte ✅ (30 s).

**Si le projet est déjà importé** (ancienne version avec Render) : refaites seulement les étapes 4 et 5 avec le nouveau zip. Les anciens fichiers sont remplacés. Le dossier `server` et le fichier `render.yaml` restent dans le dépôt, vous pouvez les ignorer.

✅ Contrôle : l'onglet **Code** affiche `app`, `supabase.sql`, `DEPLOIEMENT.md`.

> Erreur de permission à l'étape 5 ? **Settings > Actions > General > Workflow permissions > Read and write permissions**, enregistrez, relancez.

---

## Phase 2. Créer la base Supabase

1. Allez sur **supabase.com** > **Start your project** > **Sign in with GitHub**.
2. **New project** :
   - Name : `surprise`
   - Database Password : touchez **Generate a password** et gardez-le quelque part (vous n'en aurez pas besoin pour l'appli)
   - Region : la plus proche de chez vous
   - Plan : **Free**
3. **Create new project**. Attendez 1 à 3 minutes.
4. Menu de gauche : **SQL Editor** > **New query**.
5. Ouvrez `supabase.sql`, copiez tout, collez-le dans l'éditeur, puis touchez **Run**.
6. Vous devez voir **Success. No rows returned**.

> Supabase ne devrait pas demander de carte pour le plan gratuit. Vérifiez ce qui s'affiche à l'écran.

### Récupérer l'adresse et la clé
1. **Project Settings** (roue dentée) > **API Keys** (ou **API**).
2. Notez **Project URL** : `https://xxxxxxxx.supabase.co`.
3. Copiez la clé **anon** (onglet *Legacy API Keys*, elle commence par `eyJ`) ou la clé **publishable** (elle commence par `sb_publishable_`).

⚠️ N'utilisez JAMAIS la clé **secret** ni **service_role**. Seule la clé publique va dans l'appli.

---

## Phase 3. Donner l'adresse et la clé à l'appli

1. Sur GitHub : dossier `app` > fichier `config.properties` > crayon ✏️.
2. Remplacez les deux lignes, sans espace ni guillemets :
   ```
   SUPABASE_URL=https://xxxxxxxx.supabase.co
   SUPABASE_KEY=eyJ...votre_cle_publique
   ```
3. **Commit changes**.

---

## Phase 4. Compiler l'APK

1. Onglet **Actions**. Une compilation a peut-être démarré seule après votre modification. Sinon : **Construire l'APK** > **Run workflow**.
2. Attendez de 4 à 8 minutes (rechargez la page de temps en temps).
3. Si ❌ : ouvrez l'exécution, touchez l'étape rouge et copiez-moi les dernières lignes.

---

## Phase 5. Installer l'APK

1. Sur la page du dépôt, ouvrez **Releases** (colonne de droite en version ordinateur).
2. Dernière release > touchez **app-debug.apk** pour le télécharger.
3. Ouvrez le fichier, autorisez l'installation depuis votre navigateur. Si Play Protect avertit : **Installer quand même**.

> Chaque compilation utilise une clé de signature différente : **désinstallez l'ancienne version avant d'installer la nouvelle**. L'appli ne garde aucune donnée en local.

---

## Phase 6. Tester

1. Ouvrez l'appli, autorisez la localisation, activez le GPS.
2. **Cacher une surprise** : touchez la carte, prenez les 3 photos, écrivez l'indice et le message, **FAIT**. Notez le code à 14 chiffres.
3. **Trouver une surprise** : saisissez le code, suivez le chemin. À moins de 30 m, photos, indice et message s'affichent.

Vous pouvez voir les données dans Supabase : **Table Editor** > `surprises`.

---

## Limites du plan gratuit (à vérifier sur supabase.com)

- Un projet gratuit est **mis en pause après environ 7 jours sans activité**. Dans ce cas : connectez-vous sur supabase.com et touchez **Restore project**. Les données sont conservées.
- Stockage limité (environ 500 Mo). Une surprise pèse environ 300 Ko, ce qui laisse de la place pour plus d'un millier de surprises.

---

## Modifier le projet plus tard

Sur GitHub : ouvrez un fichier, crayon ✏️, modifiez, **Commit changes**. Une modification dans `app/` relance la compilation et publie une nouvelle release.

---

## Dépannage

| Problème | Cause probable | Solution |
|---|---|---|
| « Connexion impossible » | Mauvais `config.properties`, projet en pause, pas d'Internet | Vérifiez l'URL et la clé, restaurez le projet, recompilez |
| « Code introuvable » | Code mal saisi | Vérifiez les 14 chiffres |
| Erreur au SQL | Collage incomplet | Collez tout `supabase.sql` d'un seul bloc |
| « Envoi impossible » | SQL non exécuté ou mauvaise clé | Refaites la phase 2 puis vérifiez la phase 3 |
| Compilation ❌ | Erreur de build | Actions > étape rouge : envoyez-moi les dernières lignes |
| Installation impossible (conflit) | Ancienne version signée autrement | Désinstallez l'ancienne appli |
| Écran « Activez votre GPS » | GPS désactivé ou permission refusée | Activez la localisation |
| Carte vide | Pas d'Internet | Vérifiez la connexion |

---

## Sécurité

- La clé publique ne permet pas de lister ni de modifier la table. Elle permet seulement de créer une surprise et d'en lire une avec son code exact (14 chiffres, soit 100 000 milliards de combinaisons).
- Ne mettez pas d'informations très sensibles dans les messages : ils sont stockés sur Supabase.
