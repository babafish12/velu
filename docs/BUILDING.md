# Velu selbst bauen

Alle Befehle werden im Root der öffentlichen Quellverteilung ausgeführt. Sie sind eine Anleitung, kein dokumentiertes Build-Ergebnis. Zurück zur [README](../README.md).

## Voraussetzungen und festgelegte Versionen

| Bestandteil | Konfiguration |
| --- | --- |
| JDK für Gradle/CI | 21 |
| Java-/Kotlin-Bytecodeziel | 17 |
| Gradle Wrapper | 9.6.0, mit `distributionSha256Sum` |
| Android Gradle Plugin | 9.4.1 |
| Kotlin Compose-/Serialization-Plugins | 2.4.21 |
| KSP | 2.3.12 |
| Compile SDK | API 37, Minor 2 (`platforms;android-37.2`) |
| Build Tools im Workflow | `36.0.0` |
| Minimum / Target SDK | 28 / 36 |
| App-ID / Namespace | `de.voci` |

Maßgeblich sind [Root-Builddatei](../build.gradle.kts), [App-Builddatei](../app/build.gradle.kts), [Wrapperkonfiguration](../gradle/wrapper/gradle-wrapper.properties) und [Workflow](../.github/workflows/android.yml). `rootProject.name = "Voci"` und das Paket `de.voci` sind technische Bestandsnamen, nicht ein anderes Produkt. Das Veröffentlichungsziel ist 0.3.0 / `versionCode = 3`; die tatsächlich gebaute Version muss vor dem Release in der App-Builddatei und im APK übereinstimmen.

## SDK und Wrapper

JDK 21 und Android-SDK-Command-line-Tools lokal installieren. Pfade an die eigene Umgebung anpassen, nicht ins Repository übernehmen:

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
java -version
sdkmanager --licenses
sdkmanager 'platform-tools' 'platforms;android-37.2' 'build-tools;36.0.0'
./gradlew --version
```

Alternativ kann Android Studio eine lokale `local.properties` mit `sdk.dir` anlegen. Diese Datei bleibt unversioniert. Den mitgelieferten Wrapper verwenden, keine zufällige systemweite Gradle-Version. Der erste Aufruf lädt Gradle und Maven-Abhängigkeiten; Offline-Builds funktionieren nur mit bereits vollständigem Cache. Falls eine festgelegte SDK-/Plugin-Version nicht auflösbar ist, den Fehler dokumentieren statt stillschweigend andere Versionen als denselben Build auszugeben.

## JVM-Tests, Lint und Entwicklungs-APK

```sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
./gradlew --no-daemon --console=plain --max-workers=2 \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Python 3.11 oder neuer verwenden: Das Manifesttool nutzt `hashlib.file_digest`. Ausgaben:

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- JVM-HTML: `app/build/reports/tests/testDebugUnitTest/index.html`
- JVM-XML: `app/build/test-results/testDebugUnitTest/`
- Lint: `app/build/reports/lint-results-debug.html` und gegebenenfalls weitere Formate

Diese generierten Ausgaben sind keine eingecheckten Quellen. Für Geräteprüfungen zusätzlich `:app:assembleDebugAndroidTest` bauen und die Isolationsanleitung in [TESTING.md](TESTING.md) beachten. Nicht ungeprüft die gesamte historische Instrumentierung als Sammellauf ausführen.

## Signatur und Installation

`assembleDebug` erzeugt eine debuggable Entwicklungs-APK mit dem lokalen Android-Debug-Schlüssel. Die App-Konfiguration enthält keine öffentliche Produktions-Signierkonfiguration. Ein `assembleRelease` ist deshalb nicht automatisch ein verteilbares, kompatibel signiertes Update. Auch eine als GitHub-Release veröffentlichte Debug-APK bleibt eine Debug-APK.

Selbst gebaute APKs und frische CI-Runner besitzen andere Debug-Schlüssel als eine bereits verteilte APK. Android verweigert ein Update über eine anders signierte Installation. Den verteilten Schlüssel nicht kopieren oder veröffentlichen, um diese Sperre zu umgehen. Für lokale Tests einen eigenen Emulator oder ein dediziertes Testgerät verwenden. Deinstallation kann alle lokalen App-Daten löschen.

Die Ausgabe von `apksigner verify --verbose --print-certs` erlaubt die Kontrolle des tatsächlichen APK-Signierers. Sie bestätigt allein keine Kompatibilität mit einer vorhandenen Installation. Öffentlich dürfen Zertifikat-Fingerprints und APK-Hashes dokumentiert werden, aber niemals Keystores, private Schlüssel oder Kennwörter. Den vollständigen Ablauf beschreibt [RELEASING.md](RELEASING.md).

## Keine zusätzlichen App-Zugangsdaten zum Bauen

Kompilieren und synthetische Tests benötigen keine persönliche ChatGPT-Anmeldung. Beispiele und gespeicherte Lektionen sind ohne Login nutzbar. Reale Generierung benötigt eine gesonderte unterstützte Anmeldung in der App; ein erfolgreicher Build beweist diese nicht. Keine Tokens in Gradle-Dateien, Testfixtures, Screenshots oder öffentliche Protokolle schreiben.

Der [Workflow](../.github/workflows/android.yml) enthält keine Release-Schlüssel, veröffentlicht kein Update und führt keine Android-Geräte-, Liveauth- oder Hardwareprüfung aus.
