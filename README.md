<!-- Version: 2026-11-22 -->
# Screenshot-Marker

Eine kleine Android-App, um auf Screenshots zu malen: markieren, anstreichen, Pfeile, Rahmen und Kreise in mehreren Farben, dazu ein Radierer. Gedacht für schnelle Hinweise auf einem Bild, zum Beispiel vier Wortgruppen in einem Rätsel in vier Farben markieren.

Die Seite für Besucher steht auf [kuehnert.online/screenshot-marker](https://kuehnert.online/screenshot-marker/). Die APK kommt aus dem gemeinsamen App-Store `store.kuehnert.online/screenshot-marker` (Repo `kuehnert/kuehnert-appstore`); die Seite liest Version, Größe und Prüfsumme von dort.

## Was sie kann

| | |
|---|---|
| Öffnen | Über „Teilen“ aus jeder App, über „Bearbeiten mit“ in der Galerie oder über die Bildauswahl in der App |
| Werkzeuge | Marker (halbtransparent, multipliziert mit dem Bild, Text bleibt lesbar), Stift, Pfeil, Rahmen, Kreis |
| Radierer | **Objekte**: ganze Striche und Formen löschen. **Radieren**: Markierungen pixelweise wegwischen, das Original kommt wieder zum Vorschein. **Übermalen**: im Bild selbst abdecken, mit der Farbe unter dem Finger oder einer festen Farbe |
| Farben | Sechs Plätze, die ersten vier nummeriert. Lange drücken öffnet eine Farbauswahl |
| Strichstärken | Drei Stufen, für Formen, Marker und Radierer je eigen abgestimmt |
| Zuschneiden | Rahmen an Ecken und Kanten ziehen oder verschieben. **Einrasten**: Kanten springen an waagerechte und senkrechte Linien im Bild (Kartenränder, Trennlinien, Kacheln), gemessen nur im Bereich des Rahmens; eine eingerastete Kante leuchtet blau. **Auto** schneidet einfarbige Ränder ab. Rückgängig machbar, Gezeichnetes wandert mit |
| Zoomen | Mit zwei Fingern vergrößern und verschieben, mit einem Finger zeichnen |
| Zurück | Macht Striche, Radieren, „Alles löschen“ und Zuschneiden rückgängig |
| Speichern, Teilen | PNG in voller Auflösung unter *Bilder/Markiert*; Teilen speichert und öffnet das Teilen-Menü |

Die App merkt sich Werkzeug, Farbe, eigene Farben, Strichstärke und Radierer-Einstellungen. Sie braucht keine Berechtigungen und kein Internet. Android 10 oder neuer.

## Aufbau

| Pfad | Was |
|---|---|
| `app/src/app/screenmarker/MainActivity.java` | Leisten, Einstellungen, Laden, Speichern, Teilen, Farbdialog |
| `app/src/app/screenmarker/DrawView.java` | Bild, Zeichnen, Radieren, Zuschneiden mit Linienerkennung, Zoomen, Rückgängig. Alle Formen liegen in Bildpixeln und werden beim Speichern in voller Auflösung gezeichnet |
| `app/src/app/screenmarker/ColorPickerView.java` | Farbfläche und Farbtonleiste |
| `app/src/app/screenmarker/IconButton.java`, `ColorDot.java` | Knöpfe mit selbst gezeichneten Symbolen, Farb- und Stärkepunkte |
| `app/res/icon.png` | Das App-Symbol |
| `tools/build.py` | Der Build, siehe unten. Hier stehen auch Versionsnummer und Manifest |
| `tools/Sign.java`, `tools/Verify.java` | Signieren und Prüfen mit apksig |
| `.github/workflows/apk.yml` | Baut bei einem Tag `vX.Y.Z` die signierte APK und veröffentlicht sie im App-Store |

Kein Android Studio, kein Gradle, keine Bibliotheken: nur das Android-Framework. Die Oberfläche ist im Code gebaut, nicht in Layout-Dateien.

## Bauen

Nötig sind ein JDK (17 oder neuer, getestet mit 21) und Python 3. Ein Android-SDK braucht es nicht.

```bash
python tools/build.py
```

schreibt `build/ScreenshotMarker-<version>.apk`. Beim ersten Mal lädt das Skript drei Werkzeuge nach `.tools/` und prüft ihre SHA-256-Summen:

- `android.jar` (API 34) aus [Sable/android-platforms](https://github.com/Sable/android-platforms)
- `dx` von Maven Central (`com.jakewharton.android.repackaged:dalvik-dx`)
- `apksig` von Maven Central (`com.android.tools.build:apksig`)

Daraus: `javac` gegen `android.jar`, `dx` macht `classes.dex`, das Skript schreibt das binäre `AndroidManifest.xml` und eine `resources.arsc` mit dem Symbol selbst (ohne aapt2), packt alles mit 4-Byte-Ausrichtung und signiert mit Signaturschema v2.

`python tools/build.py --unsigned` baut nur `build/unsigned.apk`, ohne Schlüssel.

### Signaturschlüssel

Signiert wird mit `keystore/screenshot-marker.p12` (Alias `marker`), das Passwort steht in `keystore/password.txt`. Alternativ `SCREENSHOT_MARKER_KEYSTORE` und `SCREENSHOT_MARKER_KEYSTORE_PASSWORD` setzen. Beides ist in `.gitignore` und gehört nicht ins Repo.

Ein Update installiert sich nur über eine vorhandene App, wenn es mit **demselben Schlüssel** signiert ist. Geht der Schlüssel verloren, muss die App vor dem nächsten Update deinstalliert werden. Also gut aufheben, zum Beispiel im Passwortmanager.

### Neue Version

1. In `tools/build.py` `VERSION_CODE` um eins erhöhen und `VERSION_NAME` setzen, immer dreistellig (`1.2.0`): der App-Store kennt nur solche Versionen.
2. Committen, pushen und das Tag setzen: `git tag v1.2.0 && git push origin v1.2.0`.
3. `.github/workflows/apk.yml` baut die signierte APK und veröffentlicht sie im App-Store. Die Seite auf kuehnert.online zeigt sie von selbst an, dort ist nichts zu tun.

Einmalig auf GitHub als Secrets hinterlegen:

```bash
base64 -w0 keystore/screenshot-marker.p12 | gh secret set SCREENSHOT_MARKER_KEYSTORE_BASE64 -R kuehnert/ScreenshotMarker
gh secret set SCREENSHOT_MARKER_KEYSTORE_PASSWORD -R kuehnert/ScreenshotMarker < keystore/password.txt
gh secret set APPSTORE_TOKEN -R kuehnert/ScreenshotMarker
```

`APPSTORE_TOKEN` ist der Upload-Schlüssel für das Programm `screenshot-marker` von der Admin-Seite des App-Stores.

Von Hand geht es auch: `python tools/build.py`, dann mit `APPSTORE_TOKEN` in der Umgebung

```bash
curl -fsSL https://store.kuehnert.online/publish.mjs -o publish.mjs
node publish.mjs publish --app screenshot-marker --version 1.2.0 --file apk:android:build/ScreenshotMarker-1.2.0.apk
```

## Versionen

| Version | |
|---|---|
| 1.2.0 | Zuschneiden, mit Einrasten an Linien im Bild und automatischem Abschneiden einfarbiger Ränder |
| 1.1 | Radierer mit drei Modi, Farben per langem Druck änderbar, deutlich dickere Strichstärken, Rückgängig auch für Radieren und Löschen |
| 1.0 | Marker, Stift, Pfeil, Rahmen, Kreis in sechs Farben, Zoomen, Speichern und Teilen |
