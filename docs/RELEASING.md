# Velu veröffentlichen

Diese Checkliste beschreibt den Veröffentlichungsablauf für **0.3.0**, Android-`versionCode` **3**, Tag **`v0.3.0`**. Die APK-gebundenen Build-, Geräte-, UI- und Same-Version-Installer-Ergebnisse stehen in [TESTING.md](TESTING.md) und [verification.json](verification.json). Öffentliche Assets müssen zusätzlich nach jedem Upload zurückgelesen und heruntergeladen werden; lokale Tests allein beweisen keine Veröffentlichung. Siehe auch [BUILDING.md](BUILDING.md).

## 1. Öffentlichen Quellstand abschließen

- In der [App-Builddatei](../app/build.gradle.kts) `versionName = "0.3.0"` und `versionCode = 3` setzen; App-ID `de.voci` beibehalten. Versionswerte niemals nur in der Dokumentation erhöhen.
- Die eigene neue Completion-Animation mit dokumentierter Herkunft übernehmen; [ASSETS.md](ASSETS.md) um den tatsächlichen Erzeugungsweg und SHA-256 ergänzen. Keine ungeklärte Altanimation als neu erzeugt ausgeben.
- Echte Vorschauen als `docs/media/velu-player.gif` und `docs/media/velu-library.gif` hinzufügen; keine Konten, persönlichen Lektionen oder Geheimnisse aufnehmen. Danach den Vorschautext in der README aktualisieren.
- Eigene Quellen unter [MIT](../LICENSE) veröffentlichen. Das unveränderte [Lizenzpaket](../app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md) samt `index.json` und allen `texts/` übernehmen. Keine alleinstehende Kopie der Hinweise ins Root: Ihre relativen Links würden ins Leere zeigen.
- Nur den freigegebenen öffentlichen Quellstand exportieren, nicht die alte private Git-Historie, Reviewerlogs, Geräte-App-Daten, Zugangsdaten, Schlüssel, lokale Konfigurationen oder interne Berichte. Eine `.gitignore` entfernt keine bereits getrackten Daten und bereinigt keine Historie.
- Relative Links im zusammengesetzten Quell-/Dokumentationsbaum prüfen. Dokumentierte Sollwerte sind keine Bestätigung der exportierten APK.

## 2. Artefakt und Signatur festlegen

Der bisherige Verteilungsweg nutzt eine **Debug-/Entwicklungssignatur**. Nicht als produktionsgehärtete Release-Signierung darstellen. `assembleDebug` bleibt debuggable; der vorhandene CI-Workflow erzeugt lediglich ein Entwicklungsartefakt mit einem eigenen Runner-Schlüssel.

Für ein Update muss der tatsächliche Signierer zur vorherigen Installation passen. Ein neuer privater Schlüssel oder ein neuer CI-Debug-Schlüssel erfüllt diese Voraussetzung nicht. Ein Wechsel auf eine Produktionssignatur braucht eine gesonderte Migrationsentscheidung; in dieser Veröffentlichung wird keine funktionierende Schlüsselrotation zugesichert.

Private Signierschlüssel, Keystores und Passwörter weder in Quellen noch in Releaseassets hochladen. Den verwendeten Schlüssel außerhalb des Repositories geschützt halten. Den öffentlichen Zertifikat-Fingerprint und die genaue Buildvariante hingegen in den Releasenotizen dokumentieren.

Das fertige, endgültig signierte APK unter einem einfachen Dateinamen bereitstellen, beispielsweise `velu-0.3.0.apk`. Das Umbenennen ändert weder Buildvariante noch Signatur. Danach nicht erneut signieren oder bearbeiten, ohne die Metadaten neu zu erzeugen.

## 3. Manifest aus dem tatsächlichen APK erzeugen

Das [Manifesttool](../tools/create_update_manifest.py) benötigt Python 3.11+ sowie `aapt2` und `apksigner` aus dem SDK. Beispiel mit einem bewusst generischen lokalen Ausgabeordner:

```sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  artifacts/velu-0.3.0.apk
python3 tools/create_update_manifest.py artifacts/velu-0.3.0.apk \
  --aapt2 "$ANDROID_HOME/build-tools/36.0.0/aapt2" \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner" \
  --output artifacts/velu-update.json
```

Der Ordner und die APK müssen vorher existieren; das Tool überschreibt kein vorhandenes Manifest. Es prüft die APK-Signatur, liest Paketkennung und Version mit `aapt2`, berechnet SHA-256 und Bytezahl und erkennt Änderungen während der Verarbeitung. Es akzeptiert keine Symlink-APK. Es **vergleicht nicht** mit einem früheren Release-Zertifikat und prüft weder eine Installation noch den finalen GitHub-Release. Diese Schritte bleiben separat nötig.

Das Manifest enthält exakt:

| Feld | Bedeutung / Releaseziel |
| --- | --- |
| `schemaVersion` | `1` |
| `applicationId` | `de.voci` |
| `versionCode` | aus APK; für dieses Release `3` |
| `versionName` | aus APK; für dieses Release `0.3.0` |
| `apkName` | tatsächlicher APK-Assetname |
| `sha256` | tatsächlicher kleingeschriebener SHA-256 der finalen APK |
| `sizeBytes` | tatsächliche Dateilänge |
| `minSdk` | aus APK; aktuell `28` |

Keine erfundenen Beispielhashes oder handgeschriebenen Dateigrößen einsetzen. Ein Hash ist keine Signatur.

## 4. Vertrag des eingebauten Updaters

Die [ReleasePolicy](../app/src/main/java/de/voci/update/ReleasePolicy.kt) und [URL-Prüfung](../app/src/main/java/de/voci/update/UpdateUrls.kt) sind maßgeblich:

- Repository: `babafish12/velu`; der Updater liest dessen neuestes Stable-Release. Der Tag muss `v<versionName>` im akzeptierten Format `vMAJOR.MINOR.PATCH` sein.
- `draft` und `prerelease` müssen beide `false` sein. Entwürfe und Vorabversionen sind keine angebotenen Stable-Updates.
- Asset `velu-update.json` und die darin benannte APK müssen **im selben Release** liegen. APK-Assetnamen einfach halten, ohne `..`, Leerzeichen oder URL-Kodierung; höchstens 96 Zeichen.
- Asset-URLs müssen zum konfigurierten Repository, Release-Tag und exakten Assetnamen passen. Die APK nicht durch einen extern gehosteten Download ersetzen.
- Metadaten höchstens 16 KiB, Releaseantwort höchstens 256 KiB, APK höchstens 128 MiB; alle Assets der Releaseantwort müssen die Policy erfüllen.
- `versionCode` muss höher als die installierte Version sein; `minSdk` zum Gerät passen. Gleiche oder niedrigere Versionen sind kein Updateangebot.
- Der Downloader prüft Länge/Hash; die Android-Archivprüfung prüft außerdem APK-Metadaten und Signierer gegen die installierte App. Der Android-Installationsdialog braucht weiterhin Zustimmung.

## 5. Veröffentlichung verifizieren

1. Den finalen Quellstand, die finalen Testergebnisse und die genaue Debug-/Signaturgrenze in den Releasenotizen nennen. Keine Liveauth-, Hardware-, CI- oder Installationsfreigabe aus anderen Prüfungen ableiten.
2. APK und erzeugtes `velu-update.json` gemeinsam hochladen; Tag und Stable-Einstellungen explizit setzen. Die Existenz dieses Dokuments ist kein Uploadnachweis.
3. Den öffentlichen Release nach Veröffentlichung erneut lesen. Tag, beide Flags, Assetnamen, Bytelängen und Downloadzuordnung kontrollieren.
4. Die veröffentlichten Dateien erneut herunterladen und gegen lokale finale Hashes, Manifestfelder und APK-Zertifikat prüfen. Damit erst den tatsächlich verteilten Stand bestätigen.
5. Auf einem separaten Testgerät/Emulator das Update über die vorherige kompatibel signierte Version prüfen: Bibliothek/Einstellungen erhalten, Zustimmung nötig, Installation abgeschlossen, neue Version gestartet. Bis dahin ausdrücklich „Installations-/Updateprüfung ausstehend“ schreiben.
6. Release- und Testdokumentation mit den tatsächlich erreichten Ergebnissen aktualisieren, einschließlich Fehlschlägen und nicht ausgeführten Prüfungen.

Keine nachträgliche APK-Ersetzung unter unverändertem Manifest. Bei einer anderen APK müssen Bindung, Tests und Release-Metadaten erneut geprüft werden. Die CI ist kein Releasepublisher und besitzt keinen für offizielle Updates vorgesehenen privaten Schlüssel.
