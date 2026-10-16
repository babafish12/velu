# Grafiken, Animation und Drittanbietertexte

Dieses Verzeichnis beschreibt die öffentliche Herkunftsabgrenzung; es ist keine Zusicherung exklusiver Bild- oder Markenrechte. Eigene Quellen stehen unter [MIT](../LICENSE). Fremde Bibliotheken, Schriften und Ressourcen behalten ihre eigenen Bedingungen.

## Projektseitige Grafiken

| Ressourcen im Quellbaum | Herkunft und Verwendung |
| --- | --- |
| [velu_brand.png](../app/src/main/res/drawable-nodpi/velu_brand.png), [velu_launcher.png](../app/src/main/res/drawable-nodpi/velu_launcher.png) | KI-unterstützt erzeugtes Velu-Fennek-Motiv mit projektseitiger Raster-/Layoutableitung. Aktives Header- und Launcherbranding. Keine Zusicherung exklusiver Rechte. |
| [spreli_brand.png](../app/src/main/res/drawable-nodpi/spreli_brand.png), [spreli_launcher.png](../app/src/main/res/drawable-nodpi/spreli_launcher.png) | Älteres projektseitig KI-generiertes Motiv mit dokumentierter Rasterableitung. Weiterhin gebündelte Bestandsressourcen, nicht das aktuelle Header-/Launcherbranding. Keine Aussage, dass sie entfernt wurden. |
| [ic_uncertain_star_filled.xml](../app/src/main/res/drawable/ic_uncertain_star_filled.xml), [ic_uncertain_star_outline.xml](../app/src/main/res/drawable/ic_uncertain_star_outline.xml) | Projektseitig angelegte Vektorressourcen für Unsicherheitsmarkierungen; keine mit den Bibliothekssternen gleichzusetzenden Fremdressourcen. Ein eigenständiger deterministischer Generator ist dafür nicht dokumentiert. |

Die spätere Herkunftszuordnung der Projektsterne und Spreli-Grafiken präzisiert den älteren pauschalen Vorbehalt im unveränderten Lizenzpaket. Sie ändert dessen Texte nicht und behauptet keine umfassende Rechteprüfung. Header und Launcher referenzieren Velu; vorhandene ältere Dateien verschwinden dadurch nicht automatisch aus einer APK.

## Selbst erzeugte Completion-Animation

[complete.json](../app/src/main/assets/complete.json) wurde für 0.3.0 vollständig durch eine neue projektseitige geometrische Animation ersetzt. Der offene [Python-Generator](../tools/generate_completion.py) wurde mit Codex-Unterstützung eigens dafür verfasst, ohne externe Animationsvorlage, Bilder oder Schriften. Generator und erzeugtes Asset werden als eigene Projektbeiträge unter [MIT](../LICENSE) angeboten. Die frühere Datei ist nicht Teil dieser öffentlichen Quellverteilung.

Rezept: 80 × 80 Pixel, 60 Frames/s, 36 Frames (600 ms); ein blauer Ring wächst von 70 % auf 100 %, anschließend erscheint ein weißer Haken. Die historische interne Kompositionskennung `VOCI completion` bleibt aus Kompatibilitätsgründen erhalten; sie ist nicht das sichtbare App-Branding.

- SHA-256 des erzeugten Assets: `c3bfd93db6d5faba275c8775f794f199e34e85977642708fd59086ff489b7b7e`
- Reproduzierbarkeit ohne Schreiben: `python3 tools/generate_completion.py --check`
- Explizite Neuerzeugung: `python3 tools/generate_completion.py --write`
- Elf Python-Kontrollen prüfen deterministische Bytes, Grenzen, lokale Shapes, parsergerechte Schlüsselreihenfolge und schreibfreie Fehlerbehandlung. Android-Rendering wird zusätzlich durch `R7CompletionAssetTest` geprüft; tatsächliche Laufergebnisse stehen in [TESTING.md](TESTING.md).

Der ältere pauschale Herkunftsvorbehalt im unveränderten Drittanbieterpaket bezieht sich auf den Bestand vor diesem Ersatz. Die Bibliothekslizenz von Lottie bleibt von der Herkunft dieses Animationsinhalts getrennt.

## Schrift, Symbole und Bibliotheksressourcen

- **Manrope:** SIL Open Font License 1.1; Original-Copyright und vollständiger Text im Lizenzpaket.
- **Material Symbols Outlined:** `graphic_eq`, `category`, `library_books`, `tune`, `play_arrow`, `pause`; Apache 2.0. Die übernommenen SVG-Pfade wurden in Android-Vektoren umgewandelt, mit Y-Verschiebung um 960. Den Änderungshinweis erhalten.
- **AndroidX-/Media3-/AppCompat-Ressourcen:** Bibliotheksressourcen, darunter weitere Sternsymbole, sind getrennt von den beiden projektseitigen Unsicherheitssternen zu behandeln. Zugehörige Bedingungen stehen im Drittanbieterpaket.
- **Lottie:** Bibliothekslizenz und Herkunft einer geladenen Animationsdatei sind unterschiedliche Fragen.

## Ein verbindlicher Ort für die vollständigen Texte

[THIRD_PARTY_NOTICES.md](../app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md), [index.json](../app/src/main/assets/licenses/index.json) und [texts/](../app/src/main/assets/licenses/texts/) bleiben gemeinsam unter `app/src/main/assets/licenses/`. Die App kann die mitgelieferten Texte offline anzeigen. Bei Quell- und APK-Verteilung müssen sie tatsächlich enthalten und lesbar sein.

Die Hinweise verlinken `texts/` relativ zu ihrem eigenen Verzeichnis. Deshalb keine isolierte Rootkopie anlegen und keine Originaltexte kürzen, normalisieren oder durch bloße Lizenznamen ersetzen. Die Projektlizenz ist keine pauschale Neulizenzierung dieses Pakets. Bereits vorhandene Wrapper-/Quellheader ebenfalls erhalten.

## Öffentliche Vorschauen

Die eingebundenen [Player-](media/velu-player.gif) und [Bibliotheks-GIFs](media/velu-library.gif) stammen aus echten Bildschirmaufnahmen der finalen, APK-gebunden geprüften App. Sie zeigen ausschließlich mitgelieferte Beispiele, keine persönlichen Konten oder Lektionen. Die Bilder wurden nicht durch Mockups ersetzt. Kodierung: von 720 × 1600 auf 360 × 800 Pixel, 12 Frames/s und GIF-Palette, ohne künstliche Verlangsamung. Androids Aufnahme mit variabler Bildrate enthält bei unverändertem Bildschirm keine zusätzlichen Schlussframes; angegeben ist die tatsächliche Mediendauer, nicht das längere Aufnahmelimit.

- `velu-player.gif`: 42 Frames, 17 unterschiedliche dekodierte Bilder, 3,50 Sekunden; SHA-256 `97149f9de5e8dfa64290999ee50173c075b01e65565d88fc23220de879934396`.
- `velu-library.gif`: 17 Frames, 13 unterschiedliche dekodierte Bilder, 1,41 Sekunden; SHA-256 `a0da51e2dd8537e9ee8c702aa12ac11f96c3c39d82f114b0849a389aa1867573`.

Die Aufnahmen entstanden vor der Erstveröffentlichung; der damalige Updatehinweis bleibt sichtbar. GIFs enthalten keinen Ton und belegen weder Sprachqualität noch Livegenerierung, Installation, vollständige TalkBack-Bedienbarkeit oder die Erreichbarkeit aller unterhalb des sichtbaren Bereichs liegenden Controls.
