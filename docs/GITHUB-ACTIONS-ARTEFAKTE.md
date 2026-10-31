# GitHub Actions, APK-Artefakte und Releases – Linkregister

**Repository:** [sroroxd/TFT-Jarvis-Coach](https://github.com/sroroxd/TFT-Jarvis-Coach)

Diese Links gehören zu **verschiedenen GitHub-Datenbeständen** und sind nicht austauschbar:

| Bereich / Datenbestand | Korrekte Adresse | Zweck |
|---|---|---|
| Actions-Ausführungen (Workflow Runs) | [Lauf 41184041027](https://github.com/sroroxd/TFT-Jarvis-Coach/actions/runs/41184041027) | Status, Logs, Job-Schritte, Commit |
| Actions-REST-API – genau dieser Lauf | [GET /actions/runs/41184041027](https://api.github.com/repos/sroroxd/TFT-Jarvis-Coach/actions/runs/41184041027) | Metadaten maschinenlesbar |
| Actions-Artefakte **dieses** Laufs | [GET /actions/runs/41184041027/artifacts](https://api.github.com/repos/sroroxd/TFT-Jarvis-Coach/actions/runs/41184041027/artifacts) | ZIP-Artefakte, falls erstellt |
| Actions-Artefakte des Repositories | [GET /actions/artifacts](https://api.github.com/repos/sroroxd/TFT-Jarvis-Coach/actions/artifacts) | Artefaktliste mit tatsächlicher `id` und Verfallsdatum |
| API-Dokumentation | [GitHub REST: Download an artifact](https://docs.github.com/en/rest/actions/artifacts?apiVersion=2022-11-28#download-an-artifact) | Nur Anleitung, **kein APK-Download** |
| Veröffentlichung freigegebener APKs | [GitHub Releases](https://github.com/sroroxd/TFT-Jarvis-Coach/releases) | Release-Dateien direkt als `.apk` herunterladen |
| Release-Workflow im Quellcode | [release-090.yml](../.github/workflows/release-090.yml) | Builds, Zertifikatsprüfung, Release-Regeln |

## Bekannter Vorfall: Workflow 41184041027

- GitHub-Run: [41184041027](https://github.com/sroroxd/TFT-Jarvis-Coach/actions/runs/41184041027)
- Commit: `f18a5fe2193e5f3d853b065dd7780f9b3dea9478` (Branch `main`)
- Status: **abgeschlossen / fehlgeschlagen**; Android-Build erfolgreich, Veröffentlichung fehlgeschlagen.
- Diagnose im Job `release`: `Release version or monotonically increasing versionCode invalid.`
- Im Commit stand `versionName '0.9.20-qwen-chatml-fix-experimental'` und `versionCode 50`. Der damalige Versionsprüfer erlaubte nach der Versionsnummer nur Buchstaben, Ziffern oder Punkte, **keine weiteren Bindestriche**.
- **Artefakte dieses Laufs: 0.** Somit existiert weder ein Artefakt-ID-Download noch ein neuer GitHub Release aus diesem Lauf. Der Fehler lässt sich nicht durch eine andere URL beheben.

## Korrektur im Entwicklungszweig

In `.github/workflows/release-090.yml` wird die Versionsvalidierung für zusätzliche Bindestriche korrigiert. Ein experimenteller / Preview-/Test-Build wird nach Zertifikatsprüfung **nur als kurzlebiges Actions-Artefakt** aufbewahrt und **nicht** als regulärer Release veröffentlicht. Eine Release-APK wird erst bei einem zulässigen freigabefähigen Versionsnamen veröffentlicht.

Dies betrifft künftige Builds **nach Übernahme des Fixes**. Ein historischer fehlgeschlagener Workflow wird dadurch nicht rückwirkend erfolgreich. Der Pull Request für diese Korrektur bleibt bis zur CI-Prüfung separat.

## Richtige Download-Adresse aus einer echten Artefakt-ID

Die dokumentierte API benutzt nicht die Workflow-Run-ID, sondern die **Artefakt-ID**:

```http
GET /repos/sroroxd/TFT-Jarvis-Coach/actions/artifacts/{artifact_id}/zip
Accept: application/vnd.github+json
Authorization: Bearer <GITHUB_TOKEN>
X-GitHub-Api-Version: 2022-11-28
```

- Die tatsächliche `artifact_id` **immer zuerst** aus `GET /actions/runs/{run_id}/artifacts` lesen; nicht raten, nicht die Run-ID einsetzen.
- GitHub antwortet mit einem **temporären Redirect** auf ein ZIP-Archiv. Dieser Redirect ist kein dauerhafter öffentlicher APK-Link und kann Authentifizierung sowie Berechtigungen voraussetzen.
- Erst nach ZIP-Download, Extraktion und APK-Integritätsprüfung existiert eine eigenständige APK. Ein bloßer CI-Erfolg ersetzt keine Tests auf dem Galaxy S24 Ultra.
- GitHub-**Releases** sind eine getrennte Sammlung. Die dauerhafte Browseradresse für eine bereits veröffentlichte APK ist `/releases/download/<tag>/<dateiname>.apk` und muss zu einem **tatsächlich existierenden Release-Asset** gehören.
- Experimentelle signierte Builds niemals ungeprüft als Produktions-Updates installieren; gleiche App-ID und identische Signatur können bestehende Daten und App-Versionen beeinflussen.

**Wichtig:** Dieses Linkregister verwaltet Verweise auf GitHub-Ressourcen, legt aber keine GitHub-Artefakte in einer zusätzlichen Datenbank an. Die Artefakte selbst werden ausschließlich durch erfolgreiche GitHub-Actions-Uploads erstellt.
