# JARVIS: APK-Updates ohne Neuinstallation

## Warum ein dauerhafter Signaturschlüssel notwendig ist
Android installiert ein APK-Update über eine bestehende App nur, wenn die App-ID,
die Signaturidentität und der Versionsverlauf passen. Ein auf jedem GitHub-Runner
neu erzeugter Debug-Schlüssel ist **keine** updatefähige Release-Identität.
Darum veröffentlicht der neue Workflow ausschließlich APKs, die mit demselben
dauerhaften Zertifikat signiert und anschließend per Android apksigner geprüft wurden.

**Wichtig:** Die bisherigen Versionen (einschließlich 0.8.2) wurden als Debug-APKs
gebaut. Ein Wechsel auf die neue dauerhaft signierte Release-APK kann **einmalig**
eine Deinstallation erfordern. Vor diesem Wechsel vorhandene Einstellungen bzw.
den persönlichen API-Key sicher separat aufbewahren; bei Deinstallation können
App-Daten verloren gehen. Nach dem Wechsel sind Updates ohne Deinstallation
möglich, sofern derselbe Schlüssel weiter verwendet und der versionCode erhöht wird.

## Einmalige Einrichtung durch den Repository-Eigentümer

1. Unter https://github.com/sroroxd/TFT-Jarvis-Coach/settings/secrets/actions
   ein neues **Repository secret** mit exakt dem Namen
   `JARVIS_SIGNING_PASSWORD` anlegen. Wert: zufällig erzeugtes, einzigartiges
   Kennwort mit **mindestens 32 Zeichen**. Nicht in Issues, Chats mit anderen
   Personen, Commits oder Screenshots teilen.
2. Das Passwort dauerhaft und sicher im Passwortmanager speichern. Bei Verlust
   ist auch die verschlüsselte Signaturschlüssel-Datei nicht mehr verwendbar.
3. Auf der GitHub-Seite `Actions` den Workflow
   **Initialize JARVIS signing key (one time)** auswählen und einmal auf
   **Run workflow** (Branch `main`) tippen.
4. Nach grünem Lauf liegt in `.github/signing/` ausschließlich eine
   **passwortverschlüsselte** PKCS#12-Datei (`.asc`) und der **öffentliche**
   SHA-256-Zertifikatsfingerabdruck. Der private Schlüssel wird nicht unverschlüsselt
   in Git veröffentlicht. Die Datei oder den Fingerabdruck niemals ersetzen.
5. Der Bootstrap-Commit startet **Publish directly installable JARVIS APK**.
   Dieser Workflow testet, erstellt die signierte APK und prüft deren Zertifikat.
   **Experimentelle Versionen werden nicht automatisch als Release veröffentlicht.**
   Stattdessen steht nach erfolgreicher Prüfung vorübergehend ein Actions-Artefakt
   zur Verfügung. Erst eine freigabefähige Version erhält einen GitHub-Release.
6. Unter https://github.com/sroroxd/TFT-Jarvis-Coach/releases die APK
   direkt herunterladen. Für künftige Versionen zuerst `versionCode` erhöhen
   und `versionName` anpassen; ein Commit der `app/build.gradle` auf `main`
   startet dann die Veröffentlichung automatisch.

## Sicherheits- und Update-Regeln

- `applicationId` bleibt `com.pete.tftjarvis`.
- Der verschlüsselte Signaturschlüssel bleibt **dauerhaft identisch**;
  das Passwort bleibt als GitHub Actions Secret vorhanden.
- Keine privaten Dateien `.p12`, `.jks` oder `.keystore` ins Git einchecken.
- Der Build verweigert Veröffentlichungen ohne passenden Schlüssel,
  ohne überprüfbare APK-Signatur oder mit einer bereits vergebenen Release-Version.
- Die vorherige App-Version erst ersetzen, wenn die neue Installation erfolgreich
  getestet wurde; bei einem einmaligen Debug→Release-Wechsel kann eine
  Deinstallation erforderlich sein.
- Androids `versionCode` muss gegenüber **allen zuvor ausgelieferten APKs**
  derselben App-ID höher sein.

**Hinweis:** Ein grüner CI-Build bestätigt technische Tests und APK-Erstellung,
nicht automatisch die korrekte Erkennung aller TFT-Situationen in einer Partie.

## Richtige Links: GitHub Actions vs. Releases

[Linkregister für Workflow-Läufe, Artefakt-IDs, API-Dokumentation und Release-Downloads](GITHUB-ACTIONS-ARTEFAKTE.md).

Insbesondere [Workflow 41184041027](https://github.com/sroroxd/TFT-Jarvis-Coach/actions/runs/41184041027) ist **fehlgeschlagen** und hat **kein** Download-Artefakt. Sein erfolgreicher Build darf nicht als veröffentlichte APK verlinkt werden. Die zugehörige [GitHub-REST-Dokumentation](https://docs.github.com/en/rest/actions/artifacts?apiVersion=2022-11-28#download-an-artifact) beschreibt lediglich, wie eine tatsächlich vorhandene `artifact_id` als ZIP heruntergeladen wird.
