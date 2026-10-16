# Testen und Prüfgrenzen

## Status für 0.3.0 / Code 3

**Geprüfter Entwicklungsbuild: 0.3.0 / Code 3, `debug`, Paket `de.voci`.** Die Veröffentlichung enthält die neue eigene Completion-Animation. **143 eindeutige JVM-Tests und 110 eindeutige Android-Testfälle bestanden**, jeweils ohne Fehler oder übersprungene Tests. Android: **86 Instrumentierungsaufrufe**, Android 16 / API 36 Emulator. Lint: **0 Fehler / 16 Warnungen**. Die exakten Methoden, Fixture-Modi und APK-Bindungen stehen in [verification.json](verification.json).

- Quellrevision: der zu diesem Release gehörende Git-Tag **`v0.3.0`**. Der öffentliche App-Code erzeugte dieselbe APK wie der geprüfte Kandidat; die ausdrücklich dokumentierten Exportanpassungen betreffen nur Tests.
- APK-SHA-256: `ffdba3599bb1491f51e186c6d810de5b4936b70c573373fd90a40f170a51d679`.
- Signierer-SHA-256: `d428da3d1cd9b59a7a29f76c5308760b94ccfb144c0babfcb4596bb399c7ad4f` – bisherige Entwicklungs-/Debug-Signatur, keine produktionsgehärtete Release-Buildvariante.
- Reale UI-Navigation, Sprachwechsel, Wiedergabeablauf und stumme Bildschirmaufnahmen bestanden. Die für die Vorschau gesicherten Sitzungsdateien und Einstellungen wurden bytegenau bzw. auf ihren vorherigen Abwesenheitszustand zurückgesetzt.
- Native Installation: explizite Android-Freigabe und Updatebestätigung, tatsächlicher Paketwechsel, exakter APK-Readback und sichtbarer App-Neustart bestanden. **106 dauerhafte Nicht-Datenbank-Dateien** unverändert; SQLite-Schema, alle Zeilenwerte und implizite Row-IDs erhalten. Die vorherige Installationsberechtigung und Sitzungsdateien wurden wiederhergestellt. Der beobachtete Abschluss war der Launcher nach verifiziertem Paketwechsel, kein behaupteter Erfolgsdialog.

**Transparent zur Laufaufteilung:** Nach 107 erfolgreichen Fällen verweigerte der Hosttreiber das letzte Archivpaket wegen einer veralteten Freigabeliste, noch vor dessen Instrumentierung. Der fehlgeschlagene ursprüngliche Bericht bleibt erhalten. Nach der eng begrenzten Skriptkorrektur liefen die übrigen drei Fälle und die UI mit identischen App-/Test-APKs in einer zweiten kontrollierten Phase erfolgreich. Sämtliche 110 Rohresultate wurden unabhängig erneut geparst; keine fehlgeschlagene Produktassertion wurde ausgelassen oder umetikettiert.

### Nachträgliche CI-/Fixture-Korrekturen auf `main`

Der Release-Tag `v0.3.0`, sein Quellenarchiv und sämtliche Releaseassets bleiben unverändert. Auf `main` wird die SDK-Setup-Action auf `platform-tools` begrenzt; die explizite Installation der festgelegten Compile-Plattform bleibt bestehen. Im ersten danach ausführbaren Remote-JVM-Lauf bestanden 142 von 143 Tests. Der verbleibende Socket-Test traf eine Fixture-Race: Nach `flush()` kann der Client bereits die echten Header empfangen, bevor der Serverthread sein anschließendes `headersSent.countDown()` ausführt. Der Test wartet deshalb jetzt begrenzt auf diese tatsächliche Publikation, statt den Latch-Zähler ohne Synchronisation abzufragen. Echter Socket-/Body-Read, Abbruch, Dateibereinigung und sämtliche übrigen Assertions bleiben erhalten; kein Produktcode und keine Release-APK ändern sich dadurch. Die jeweiligen aktuellen Remote-Ergebnisse stehen unter [GitHub Actions](https://github.com/babafish12/velu/actions/workflows/android.yml); die ursprüngliche APK-Abnahme oben ist davon getrennt.

### Explizite testseitige Exportanpassung

`AuthRecoveryRotationRegression.delayedRecoveryMustNotRollBackSuccessfullyRotatedCredentials` behält seine Identität und sämtliche ursprünglichen Assertions. Die Fixture kontrolliert jetzt zusätzlich die beiden Recovery-IO-Schritte, statt deren Reihenfolge vom realen Threadpool abhängig zu machen. Anschließende Tokenrotation verwendet weiterhin den realen IO-Dispatcher und den echten `AuthManager` mit synthetischem Vault und vollständig abgefangenem HTTP. Die zusätzliche Methode `inlineRecoveryIoDoesNotRequireAQueuedOwnerContinuation` prüft die ebenfalls zulässige synchrone IO-Ausführung ohne zusätzliche Owner-Fortsetzung. Die historische Originalfixture bleibt im privaten Prüfarchiv unverändert; keine Klasse, Assertion oder Fehlerauswertung wird still entfernt. Produktcode, Authentifizierungsregeln und Produktionsdispatching sind durch diese Exportanpassung unverändert.

Nicht nachgewiesen sind persönliche Live-ChatGPT-Anmeldung/-Generierung, physisches Hören, Bluetooth, vollständiges TalkBack-Verhalten und eine höher-versionierte Live-GitHub-Download-/Installationskette. Die native Installerprüfung verwendete dieselbe vollständige Version. Die historische Auswahl unten erklärt Scope und Voraussetzungen; die tatsächlich ausgeführten aktuellen Methoden stehen getrennt in [verification.json](verification.json). Frühere erfolgreiche Läufe gelten nicht automatisch für diese Veröffentlichung. Die [CI-Konfiguration](../.github/workflows/android.yml) ist eine Ablaufdefinition, kein behaupteter erfolgreicher Remote-CI-Lauf.

Veröffentlicht werden ausschließlich bereinigte Ergebniszusammenfassungen. Private Rohlogs, App-Daten, Zugangsdaten und Signierschlüssel gehören nicht in das Repository oder die Releaseassets.

## Lokale JVM-/Werkzeugprüfung

Voraussetzungen: [BUILDING.md](BUILDING.md). Im Repositoryroot:

```sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
./gradlew --no-daemon --console=plain --max-workers=2 \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

JVM-Ergebnisse aus `app/build/test-results/testDebugUnitTest/` und `app/build/reports/tests/testDebugUnitTest/` lesen; bei der Zählung Fehler, Skips und Tests getrennt auswerten. Python-Werkzeugtests nicht in die JVM-Zahl einrechnen. APK-Bau, JVM-Erfolg und Lint sind voneinander getrennte Ergebnisse.

Synthetische Anbieter-/HTTP-Antworten testen Produktionsparser, Fehlerbehandlung und Zustandsübergänge, nicht Anmeldung oder Verfügbarkeit eines realen Kontos. UI-Composition-Tests mit künstlichem Display-State beweisen keine echte TTS-/Media3-Wiedergabe.

## Android: Isolation I gilt vor und nach jeder einzelnen Methode

Die historischen Tests verändern lokale Preferences, Datenbanken, Audio-/Plan-Dateien und teilweise Account-Testfixtures. Deshalb **nur einen wegwerfbaren Emulator oder ein dediziertes Testgerät ohne persönliche Daten und ohne echtes angemeldetes Konto verwenden**. Nicht auf einer produktiv verwendeten Installation laufen lassen.

1. Ein isoliertes Testprofil mit passendem App-/Test-APK vorbereiten. `ANDROID_SERIAL` bewusst auf genau dieses Testziel setzen. App und Testpaket müssen zusammenpassen; anders signierte Installationen nicht gewaltsam ersetzen. Keine Schlüssel aus einer veröffentlichten Installation übernehmen.
2. Benötigte Offline-System-TTS-Stimmen vorab installieren: Deutsch plus die von der jeweiligen Fixture verwendeten Lernsprachen (Englisch, Italienisch, Spanisch, Französisch). Offline-Bereitschaft tatsächlich prüfen. Keine fehlende Stimme durch synthetische Audiodauern kaschieren.
3. Einen sauberen Ausgangssnapshot ohne unfertige Wiedergabesession, ohne persönliche Lektionen und ohne Livekonto verwenden. Vor **jeder Methode** diesen Ausgangszustand wiederherstellen; ein Prozess-Neustart allein löscht keine persistenten Testreste. Niemals eine ganze historische Klasse als Ersatz für die einzeln klassifizierten Methoden starten.
4. Vor dem Test darf kein anderer Test, Controller oder Player dieselbe App steuern. Queue und Vorbereitung müssen ruhen. Besonders `last-session.json`, `.bak`/`.new`, `plans/`, Audiodateien, DB und Preferences dürfen nicht von einem vorherigen Lauf stammen. Vorhandene fremde Dateien nicht als Fixture-Müll löschen: bei unerwarteten Daten abbrechen und zum isolierten Snapshot zurückkehren.
5. Genau eine unten genannte `Klasse#Methode` mit Modus **P** oder **V** starten. Historische Methoden und Assertions nicht umschreiben. Keine `visible=true`-Zuweisung, keine Auth-Freigaben und keine Abschaltung von Produktionsschranken.
6. Den vollständigen Methodenausgang lesen: Start, eindeutiges Testende, Assertions, Fixture-Cleanup und Instrumentierungsabschluss. Ein Shell-Exitcode oder `INSTRUMENTATION_CODE` allein ist kein JUnit-Erfolg. Leere Auswahl, Crash, Timeout, übersprungene Tests, Mechanism-Fehler oder fehlende Endmarker sind kein PASS.
7. Nach der Methode offene Jobs, Audio und eigene Activity-Fixtures beenden lassen. Bereinigte Ergebnisse sichern und dann den sauberen Ausgangszustand für die nächste Methode wiederherstellen. Nicht durch pauschales App-Datenlöschen auf einem Nutzergerät „isolieren“.

Beispiel zur einmaligen Installation auf dem ausdrücklich gewählten Testziel (löscht keine vorhandenen App-Daten; Signaturkonflikte separat klären):

```sh
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

### P: vorhandene Fixture, ohne zusätzlichen Activitylistener

Nach Isolation I eine einzelne Identität aus der P-Tabelle verwenden:

```sh
CASE='de.voci.R4RequestedFeaturesTest#signedOutNoticeAppearsBeforeGenerationAndAccountActionWorks'
adb -s "$ANDROID_SERIAL" shell am force-stop de.voci
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class "$CASE" de.voci.test/androidx.test.runner.AndroidJUnitRunner
```

Die Methode hat bereits die erforderliche Activity-/Compose-Fixture oder führt keine gegatete Aktion aus. Das ist keine Garantie ihres Bestehens. Die bestehende Fixture entscheidet über ihre eigenen Voraussetzungen; keine zusätzliche Activity daneben offen halten, wenn die Methode echte Stop-/Recreation-Grenzen prüft.

### V: echte Activity nur für die 27 ausdrücklich zugelassenen Methoden

Der vorhandene [R7VisibleRegressionListener](../app/src/androidTest/java/de/voci/R7VisibleRegressionTest.kt) startet pro zugelassener Methode einen echten `ComponentActivity`-Host über `ActivityScenario` und prüft `RESUMED` sowie die durch reale Lifecycle-Callbacks gesetzte App-Sichtbarkeit. Nach der Methode schließt er genau seinen Host bis `DESTROYED`. Er gibt keine Produktflags frei und räumt keine App-Daten auf: Isolation I bleibt erforderlich.

```sh
CASE='de.voci.R4PlaybackEdgeTest#pendingUncachedSelectionSurvivesOldItemsNaturalEnd'
adb -s "$ANDROID_SERIAL" shell am force-stop de.voci
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class "$CASE" \
  -e listener de.voci.R7VisibleRegressionListener \
  de.voci.test/androidx.test.runner.AndroidJUnitRunner
```

**Runner 1.7.0 mit Standard-Listenerreihenfolge:** `newRunListenerMode` weglassen oder ausdrücklich `false` setzen. Nicht `true` verwenden: Sonst kann der ActivityFinisher den gerade gestarteten Host vor dem Test zerstören. Nicht parallel laufen lassen. Der Host ist keine sichtbare MainActivity-UI und der Listener ersetzt weder einen UI-Test noch eine Liveanmeldung.

Für **jede** ausgewählte V-Methode müssen zusätzlich zum echten JUnit-Ausgang genau diese beiden Diagnosen mit ihrer exakten Identität erscheinen:

```text
R7_VISIBLE_FIXTURE_READY Klasse#Methode RESUMED visible=true
R7_VISIBLE_FIXTURE_CLOSED Klasse#Methode DESTROYED
```

Die Diagnosen werden mit `r7_visible_fixture=READY` bzw. `CLOSED` als eigene Statuspakete gesendet und sind **keine weiteren erfolgreichen Tests**. Kein pauschales Zählen aller Statuscodes `0`. Fehlende, doppelte, fremde oder ineinander verschachtelte Marker, `R7_VISIBLE_FIXTURE_FAILURE`, Fehlerstatus, `TEST_MECHANISM` und negative Testergebnisse verwerfen den Lauf. Abhängig von der Druckerreihenfolge ist `READY → START → CLOSED → END` oder `START → READY → END → CLOSED` möglich; andere bzw. unvollständige Folgen nicht als Erfolg werten. Standard-JUnit-Testpakete dürfen bei der Auswertung nicht entfernt werden.

Der Listener hat eine exakte Allowlist: die folgenden 27 V-Identitäten, keine Klassenpräfixe und keine teilweise supersedierten Fälle. `createComposeRule()` stellt bereits einen echten Host bereit; ein lokales `mutableStateOf(visible)` dagegen ist keine Application-Lifecycle-Fixture.

## Historische Methoden: vollständige Auswahl, keine Resultatliste

71 eindeutige Methoden: **37 × P**, **27 × V**, **7 × S (teilweise supersediert)**. P und V sind zusammen 64 beibehaltene Methoden ohne supersedierte Assertions. S bezeichnet nur die unten konkret genannten alten Assertions, nicht eine ganze Fachfunktion. Für jede Zeile Isolation I separat anwenden. Methodennamen mit „Minute“, „Budget“, „Repeat“ oder „Language“ sind kein Grund, die ganze Klasse auszusortieren.

### P — Isolation I + vorhandene Fixture (37 Methoden)

| Exakte Methodenidentität | Vorgehen pro Methode |
| --- | --- |
| `de.voci.R4RequestedFeaturesTest#signedOutNoticeAppearsBeforeGenerationAndAccountActionWorks` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R4RequestedFeaturesTest#otherAuthDisplayStatesDoNotClaimSignedOut_positiveControl` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R4RequestedFeaturesTest#librarySingleEntryPlaysOnlyItsFourCuesWithoutOverwritingCourse` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R4RequestedFeaturesTest#libraryFullCourseStillPlaysWithoutAuthentication_positiveControl` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.AccountRecoveryTest#corruptCiphertextIsPreservedAndAccountScreenCanRetry` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.AudioDeviceTest#storagePressureRejectsBeforeWriting` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.AudioDeviceTest#missingOfflineVoiceCreatesNoFileOrMetadata` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.CorrectionPlaybackTest#repairedPinnedAssetSurvivesCacheClear` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.DeviceAcceptanceTest#nonLatinDraftSurvivesRecreation` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.DeviceAcceptanceTest#distinctRoutesAndBackstack` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.DeviceAcceptanceTest#noMandatoryLoginForExamples` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.DeviceAcceptanceTest#categoryRenamePreservesLesson` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.DeviceAcceptanceTest#keystoreCiphertextAndStableHost` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.DeviceAcceptanceTest#reducedMotionAndRecallAreOptIn` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R2MigrationDeviceTest#explicitConfirmationPreservesLegacyFilesPreferencesAndReopenedLibrary` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R2ScopeUiTest#settingsHaveNoGenerationOrModelManagementEntry` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R2ScopeUiTest#accountRequiresExplicitMigrationAndRetainsLibrary` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R2ScopeUiTest#legacyDestinationExplainsRemovalWithoutActions` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.ReplayRouteTest#requestedRouteNeverFinishesForOlderEndedSession` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.ReplayRouteTest#oldResultRouteCannotDisplayNewSessionStatistics` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.VaultFailureTest#atomicWriteFailureIsTruthfulAfterFreshManagerAndPreservesOtherAccount` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.VoiceReadinessTest#transientEmptyInventoryWaitsForRealOfflineVoiceAndSynthesizes` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.VoiceReadinessTest#coldInstalledItalianVoiceIsReadyBeforeFirstRender` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5UncertainStarsTest#libraryPlaybackActionsAndContentRemain_positiveControl` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5UncertainStarsTest#libraryStarsToggleIndependentlyWithoutPlaybackOrNavigation` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5UncertainStarsTest#libraryMarksSurviveReopenAndNewViewModelAndStayLessonScoped` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5UncertainStarsTest#libraryStarsHaveSeparateTrailing48DpTargetsAt200PercentFont` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5PlayerWheelTest#originalSingleKeepsSourceIdentityAndRealAudio_positiveControl` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5PlayerWheelTest#pausedSingleTouchSwipeCommitsBWithSameSessionAndFourCues` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5PlayerWheelTest#playingWheelChoiceSurvivesSlowSynthesisAtOldNaturalBoundary` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5MotionFeedbackTest#pendingStatusHasIndependentProgressAndActualCancel` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5MotionFeedbackTest#providerErrorClearsFeedbackAndRetryStartsFreshRequest` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5MotionFeedbackTest#syntheticSuccessClearsFeedbackAndRequestsPlayer` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R5MotionFeedbackTest#originalRequestOwnership_positiveControl` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R6WheelPersistenceTest#translatedWheelSurvivesNewViewModelAndLongTextAt200Percent` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R6WheelPersistenceTest#playingLanguageSwapKeepsQueueAndRapidTogglesAreAtomic` | I → P; vorhandene Fixture und Cleanup unverändert. |
| `de.voci.R6BrandMotionTest#veluReducedHeaderIsImmediatelyReadableAndNonblocking` | I → P; alle Originalassertions erhalten, Testuhr-Fenster explizit angepasst; siehe [Testuhr und Scrollen](#testuhr-und-scrollen). |

### V — Isolation I + sichtbarer Activitylistener (27 Methoden)

| Exakte Methodenidentität | Vorgehen pro Methode |
| --- | --- |
| `de.voci.R4PlaybackEdgeTest#pendingUncachedSelectionSurvivesOldItemsNaturalEnd` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R4PlaybackEdgeTest#cachedFullCoursePlaysWithEmptyVoiceInventory_positiveControl` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R4PlaybackEdgeTest#cachedSingleStartNeedsNoVoiceInventory` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R4PlaybackEdgeTest#cachedSingleSelectionNeedsNoVoiceInventory` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R4PlaybackEdgeTest#repeatUncertainAfterSingleSwitchReplaysMarkedAInsteadOfB` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.CorrectionPlaybackTest#savedThreeMinuteReplayDoesNotRewriteOriginalAtOneMinute` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.CorrectionPlaybackTest#deletingPendingPreparationCannotPublishSessionOrPlan` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.CorrectionPlaybackTest#deleteDuringRenderedFileCannotLeaveNewMetadataOrFiles` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.CorrectionPlaybackTest#deleteAfterAudioRenameCannotLeaveOrphanFile` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.CorrectionPlaybackTest#deleteDuringPlanPersistenceCannotLeaveSessionOrPlan` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.CorrectionPlaybackTest#staleRestoreCannotReplaceExplicitStart` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.CorrectionPlaybackTest#cancellationAfterDatabaseSaveBeforePublicationRollsBackNewLesson` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.CorrectionPlaybackTest#cancelledGenerationCannotSaveNavigateOrClearSuccessorBusy` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.PlaybackBoundaryTest#earlierRepositoryReadCannotStartDeletedLesson` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.PlaybackBoundaryTest#saveActionCannotPinSuccessorSession` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.PlaybackBoundaryTest#liveBoundaryDuringReplanCannotRestartCurrentSpokenFile` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.PlaybackBoundaryTest#staleReplanCannotOverwriteNewSessionFile` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.PlaybackBoundaryTest#staleReplanCannotResurrectDeletedSessionFile` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R2MigrationDeviceTest#legacyColdStartCannotInvokeProviderOrModifyLibrary` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R2MigrationDeviceTest#savedPlaybackReadBypassesGenerationGate` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.RemainingBudgetTest#speedDuringPrebufferCannotAppendStaleControllerPlaylist` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.SavedRecallTest#recallOptInAndOutDoNotRewriteSavedAudioPlan` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R5WheelEdgeTest#recoverableSelectionErrorAllowsUnchangedQueueNaturalCompletion` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R5WheelEdgeTest#committedSelectionSurvivesNoOpDragBeforePersistence` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R5WheelEdgeTest#committedSelectionSurvivesCancelledDragBeforePersistence` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R5WheelEdgeTest#ordinaryCommittedSelectionPersists_positiveControl` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |
| `de.voci.R5RejectedSelectionReplayTest#repeatAfterRejectedSelectionAllowsNaturalCompletion` | I → V; eigene READY-/CLOSED-Paarung und JUnit-Ende prüfen. |

### S — gezielt supersedierte Assertions, kein R7-PASS

Die folgenden sieben historischen Methoden byteidentisch erhalten, aber nicht unverändert als aktuelle Releaseakzeptanz mitzählen. Es sind **neun konkrete Assertion-Stellen** supersediert. Weiterhin relevante Teilprüfungen brauchen separat zugeordnete aktuelle Tests; ein früherer Erfolg oder eine erwartete Kollision ersetzt diese nicht. Die vier headless Methoden in dieser Gruppe sind nicht für den V-Listener freigegeben: Bei Übernahme ihrer erhaltenen Prüfungen einen eigenen echten Activity-Host im gesonderten aktuellen Test verwenden, keine Allowlist-Erweiterung als bloßen Workaround.

#### `de.voci.AudioDeviceTest#actualSystemTtsMeasuredAssetsAndBudget`

- **Superseded:** Die 1/3/5-Minuten-`Planner.fit`-Dauer muss im alten Band `minutes*54000..minutes*60000` liegen. Dieses Zielband ist durch vollständigen endlichen Inhalt ersetzt; der alte Utility kann trotzdem weiterhin grün sein. Beleg: [Zeile 52](../app/src/androidTest/java/de/voci/AudioDeviceTest.kt#L52).
- **Weiterhin erforderlich:** Reale System-TTS, positive gemessene Dauern und valide Audiodateien in allen Beispielsprachen behalten. Ein einzelner englisch/deutscher Inhaltslauf deckt nicht die ganze Sprachmatrix ab.
- **Isolation:** I; keine zusätzliche Activity für die direkte TTS-Teilprüfung, sofern kein neuer gegateter Playback-Aufruf hinzugefügt wird.

#### `de.voci.PlaybackBoundaryTest#clearCachePreservesFullActiveSourcePoolForReplan`

- **Superseded:** Die Fixture verlangt eine Quell-Audiodatei außerhalb des zeitlich eingepassten Plans (`sourceCues.any` ohne entsprechenden Cue). Ein vollständiger endlicher Erstplan muss gerade keinen solchen Ausschluss besitzen. Beleg: [Zeile 48](../app/src/androidTest/java/de/voci/PlaybackBoundaryTest.kt#L48).
- **Weiterhin erforderlich:** Cache-Clear muss alle aktiven Quellassets und Metadaten erhalten; anschließende Geschwindigkeitsanpassung/gezieltes Backend-Repeat bleiben offline fehlerfrei. Ein Legacy-Plan-Replay ohne aktives Cache-Clear ist kein vollständiger Ersatz.
- **Isolation:** I; erhaltener Playback-Teil braucht einen eigenen realen STARTED/RESUMED-Host; nicht in der V-Allowlist.

#### `de.voci.RemainingBudgetTest#skipTakesEffectOnActualPlayerBeforeReplanNearPauseBoundary`

- **Superseded:** Nur die Gesamtkappe `remainingMs + playedMs <= 60500` ist ersetzt. Beleg: [Zeile 40](../app/src/androidTest/java/de/voci/RemainingBudgetTest.kt#L40).
- **Weiterhin erforderlich:** Seek vor Replan auf dem tatsächlichen Player (Ziel/Position 0), `skip_seek`-Grenze, PlayedClock-Zuwachs unter 250 ms und Queue-/Cue-Konsistenz behalten. „Skip ohne Refill“ allein deckt diese Race nicht ab.
- **Isolation:** I; eigener sichtbarer Activity-Host für den aktuellen Ersatz, nicht V.

#### `de.voci.RemainingBudgetTest#latestSpeedSurvivesImmediateRepeat`

- **Superseded:** Nur `playedMs + remainingMs <= 60500` ist ersetzt, nicht die Backend-`repeat()`-Race. Beleg: [Zeile 81](../app/src/androidTest/java/de/voci/RemainingBudgetTest.kt#L81).
- **Weiterhin erforderlich:** Die Folge `speed(1.15)`, `repeat`, `speed(.85)`, `repeat` muss zuletzt .85 übernehmen. Der entfernte UI-Wiederholen-Button macht diesen Backend-Vertrag nicht ungültig.
- **Isolation:** I; eigener sichtbarer Activity-Host für den aktuellen Ersatz, nicht V.

#### `de.voci.RemainingBudgetTest#measuredWholeSegmentsAdaptToRateAndRepeatWithinBudget`

- **Superseded:** Beide alten Grenzen sind ersetzt: `remainingMs + playedMs <= minutes*60000L+500` und `>= minutes*54000L`. Sie verlangen Minutenkappe und Mindestfüllung statt fester Inhaltsmenge. Beleg: [Zeile 96](../app/src/androidTest/java/de/voci/RemainingBudgetTest.kt#L96), [Zeile 97](../app/src/androidTest/java/de/voci/RemainingBudgetTest.kt#L97).
- **Weiterhin erforderlich:** Pausierte PlayedClock bleibt unverändert, angeforderte Rate wird übernommen, alle gesprochenen Segmente haben reale positive Dauer. Die aktuelle Matrix nach 5/10/20 Einträgen organisieren; keine alte Zeitfüllung wieder einführen.
- **Isolation:** I; eigener sichtbarer Activity-Host für den aktuellen Ersatz, nicht V.

#### `de.voci.R5PlayerWheelTest#reducedMotionAt200PercentFontStillSelectsAndExposesReadableContentAndActions`

- **Superseded:** Nur der Eintrag „Wiederholen“ in der Schleife über `Play`, `Weiter`, `Wiederholen`, `Audio` ist ersetzt. Beleg: [Zeile 165–167](../app/src/androidTest/java/de/voci/R5PlayerWheelTest.kt#L165-L167).
- **Weiterhin erforderlich:** B-Auswahl, vier korrekte Cues, lesbare Ziel-/Beispiel-/Übersetzungstexte bei 200 % und die erhaltenen Play/Weiter/Audio-Aktionen bleiben nötig. Synthetischer 1x-Display-State ist kein Ersatz für diesen realen Audio-/200%-Test.
- **Isolation:** I; Compose-Host bereits vorhanden. Historische Methode nicht umschreiben; aktuelle Teilprüfungen separat abbilden.

#### `de.voci.R6WheelLanguageTest#translationInWheelDoesNotReplanPausedAudio`

- **Superseded:** Beide Existenz-/Klick-Assertions für „Übersetzung ausblenden“ sind ersetzt: vor und nach dem Sprachwechsel. Nur die erste Kollision auszublenden reicht nicht. Beleg: [Zeile 81](../app/src/androidTest/java/de/voci/R6WheelLanguageTest.kt#L81), [Zeile 165](../app/src/androidTest/java/de/voci/R6WheelLanguageTest.kt#L165).
- **Weiterhin erforderlich:** „Radsprache wechseln“, Stable-ID-Auswahl trotz gleicher Labels, pausierte Queue/URIs/Position, WAV-Hashes und Audio-Event-/Checkpoint-Zähler unverändert prüfen. R6WheelPersistence bleibt im P-Scope; seine Teilabdeckung ist keine automatische vollständige Ersetzung dieser Methode.
- **Isolation:** I; Compose-Host bereits vorhanden. Aktuellen Ersatz ohne die beiden entfernten Controls separat prüfen.

## Testuhr und Scrollen

Der Großschrift-Test wird im öffentlichen Export ausdrücklich angepasst; sein historisches Original wird nicht stillschweigend als bytegleich ausgegeben. In der verwendeten Compose/Foundation-Version führt die `ScrollBy`-Semantik eine Animation aus. Bei 200 % Schrift benötigen die ausgeschriebenen Inhaltsmengen mehr Platz. Die bisher durchgehend eingefrorene Testuhr verhindert damit den notwendigen Scrollfortschritt. Ein Kontrolllauf ohne sichtbare Bildschirmtastatur hing ebenfalls; eine reine Tastaturursache ist nicht belegt.

Die angepasste Methode behält Identität, sämtliche ursprünglichen Assertions, tatsächliche Texteingabe, unveränderte Schriftgröße, Feedback-/Abbruch-/Late-Result-Prüfungen und Cleanup. Sie prüft zunächst die erste Header-Komposition bis zur sofortigen Lesbarkeit/Bedienbarkeit bei unveränderter Testuhr. Danach darf die Uhr für Eingabe und erforderliches Scrollen laufen. Beim bereits vorbereiteten Start ist automatischer Uhrfortschritt wieder ausgeschaltet; die Messung erlaubt exakt die vom Framework selbst gemeldete Eingabe-Ereignisperiode des echten `performClick`, aber keinerlei zusätzliche Zeit bis zu Provideraufruf und Busy-Zustand. Der Compose-Eingabedispatcher bewegt die Testuhr für die Down-/Move-/Up-Zeitstempel auch bei `autoAdvance=false`. Das ist keine frei gewählte Toleranz und kein nullzeitiger Touch. Die Vorbereitungsframes kommen der gesamten Komposition zugute; das ist **kein** durchgängig eingefrorener Erststart und darf nicht so beschrieben werden.

Zwei zusätzliche Android-Identitäten sichern diese Abgrenzung ab:

- `R6BrandMotionTest#r7FrameworkScrollRequiresFramesButVisibleTargetDoesNot`: isoliertes Framework-/Viewport-Experiment ohne App-Header, Eingabefeld, Provider oder Navigation. Dieselbe Scrollbewegung muss mit freigegebenen Frames fortschreiten; ein bereits vollständig sichtbares Ziel benötigt anschließend keine weiteren Frames. Ein zusätzlicher echter Klick auf dieses Ziel prüft exakt die Framework-Ereignisperiode bis zum Callback und bis zur Rückkehr; damit wird die Eingabezeit unabhängig vom Produkt gemessen.
- `R6BrandMotionTest#r7FreshStartCallbackDispatchesWithoutPreparatoryFrames`: separate frische Fixture ohne vorbereitende Frames. Geprüft wird der echte Start-Callback über Semantik und der kontrollierte Provider-/Busy-Pfad. Das ist ausdrücklich **kein physischer Touch auf einen offscreen liegenden Button**. Die tatsächliche Eingabe-/Scrollstrecke bleibt in der ursprünglichen, angepassten Methode.

Im R7-Ablauf nach natürlichem Wiedergabeende bleibt die Navigation unverändert: Der Bibliotheks-Tab restauriert absichtlich die letzte Detailansicht. Der Test muss diese erste Detailansicht positiv erkennen und über ihr echtes „Zurück“ zur Liste wechseln, bevor er die zweite Lektion öffnet. Queue-, Request-/Sessionidentitäts-, Cue-, Audiohash- und Hintergrundstop-Assertions bleiben erhalten.

Die zusätzlichen Methoden und diese Beschreibung sind für sich noch kein bestandenes Geräteergebnis. Fehlgeschlagene historische Läufe werden dadurch nicht nachträglich grün.

## Aktuelle R7-Prüfbereiche und verbleibende Grenzen

Die R7-Testquellen enthalten getrennte Prüfungen für Inhaltsmenge/Parser/Preferences, Foreground-Playback und Konfigurationswechsel, Exit-Races, Controls/Textgeometrie, Update-Metadaten/Streams/Archivprüfung/Plattformübergabe sowie Lizenzanzeige/-navigation. Diese Bereiche nach ihrer tatsächlichen Ausführung einzeln berichten; ihre Existenz belegt keinen PASS.

- Den durchgängigen Pfad „5/10/20 in der UI wählen → persistierte neue ViewModel-Instanz → Request/Parser → gespeicherte Lektion → vollständige reale TTS-/Media3-Queue bis zum Ende“ nicht aus getrennten Teiltests zu einem End-to-End-Erfolg zusammensetzen.
- Die neuen Exit-Races ersetzen keine alten Delete-/Save-/Restore-/Prebuffer-/Rejection-/Recall-Races. Dafür sind die beibehaltenen Methoden weiter einzeln erforderlich.
- Legacy-`minutes`/`budgetMs` als erhaltene Serialisierungsmetadaten sind nicht dasselbe wie eine neue Zeitobergrenze. Gesprochene Repeat-Cues, `repeatUncertain` und Backend-`repeat()` sind nicht der entfernte UI-Button.
- Ein früherer Scope-Abgleich verzeichnete beim Controls-Test eine Text-Overflow-Kollision und beim alten Wheel-Language-Test die obsolete Button-Assertion. Das sind historische Hinweise, **keine aktuellen Ergebnisse** der finalen Quellen. Controls/Textgeometrie nach dem finalen Stand prüfen und die supersedierten Assertions getrennt ausweisen.
- Reale Android-TTS-/Media3-Tests nicht als physisches Hören, Bluetooth-/Headsetprüfung, OS-Benachrichtigungs-Tap, vollständige TalkBack-Prüfung oder Livekonto-Generierung ausgeben. Emulator und physische Hardware getrennt benennen.
- Update-Policy-, Archiv- und Intent-Tests sind keine erfolgreich abgeschlossene Android-Installation. Den veröffentlichten Download und ein reales signaturkompatibles Update separat nach [RELEASING.md](RELEASING.md) prüfen.

Die bestandenen Ergebnisse gelten ausschließlich für die oben gebundene APK und die ausdrücklich aufgeführten Prüfbereiche. Nicht ausgeführte Livekonto-, Hardware- und höher-versionierte Online-Updateprüfungen bleiben offen; sie werden nicht aus dem Emulatorergebnis abgeleitet.
