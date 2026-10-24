# CloudStream Sync: Anleitung

Diese Version ist ein Fork von CloudStream (Stand 22. Oktober 2026) mit einer zusätzlichen Funktion: Wiedergabeposition, «Weiterschauen»-Liste und Gesehen-Markierungen werden über deinen eigenen Server zwischen Handy und Android-TV abgeglichen. Alles andere, inklusive Erweiterungen und Repo-JSON, funktioniert wie im Original.

Die App heisst auf dem Gerät «CloudStream Sync» und installiert sich neben einem bereits vorhandenen CloudStream, ohne es zu ersetzen.

## Wie der Sync funktioniert

Während du schaust, lädt die App die Position etwa alle 8 Sekunden hoch, spätestens aber beim Schliessen oder Verlassen der App. Öffnest du die App auf dem anderen Gerät, holt sie den neuesten Stand; in «Weiterschauen» steht dann der Titel mit der richtigen Position. Gibt es auf beiden Geräten Änderungen am selben Eintrag, gewinnt die jüngere.

Voraussetzung: Auf beiden Geräten müssen dieselben Erweiterungen installiert sein, weil Cloudstream die Einträge über IDs zuordnet, die aus der Erweiterung stammen. Synchronisiert wird jeweils das aktive Profil.

## Teil 1: Server einrichten

Benötigt: ein VPS mit Docker (z.B. Hetzner oder Infomaniak, kleinste Grösse reicht) und eine Domain oder Subdomain, deren A-Eintrag auf die IP des Servers zeigt, z.B. `sync.deinedomain.ch`.

1. Den Ordner `sync-server` auf den Server kopieren, z.B. nach `/opt/cloudstream-sync`.
2. Im Ordner einen Token erzeugen:
   ```
   docker run --rm -v "$PWD":/app -w /app node:22-alpine node server.js --gen-token
   ```
   Das Ergebnis ist eine Zeichenfolge aus Kleinbuchstaben und Ziffern. Sie ist bewusst so gewählt, dass sie sich auf der TV-Fernbedienung gut eintippen lässt.
3. `.env.example` nach `.env` kopieren und ausfüllen:
   ```
   DOMAIN=sync.deinedomain.ch
   SYNC_TOKENS=hysen:DEIN-TOKEN
   ```
4. Datenordner vorbereiten und starten:
   ```
   mkdir -p data && sudo chown 1000:1000 data
   docker compose up -d
   ```
5. Prüfen: `https://sync.deinedomain.ch/health` im Browser muss `{"ok":true}` zeigen. Das HTTPS-Zertifikat holt Caddy automatisch; dafür müssen die Ports 80 und 443 offen sein.

Die Daten liegen in `data/db.json`. Für eine Sicherung genügt es, diese Datei zu kopieren.

Mehrere Personen mit getrennten Daten: `SYNC_TOKENS=hysen:token1,familie:token2` und danach `docker compose up -d` erneut ausführen.

## Teil 2: APK bauen (lokal)

### Mit Android Studio (einfachster Weg)

1. Android Studio installieren (aktuelle Version; bringt Java und Android-SDK mit).
2. Den Projektordner öffnen (der Ordner, in dem diese Anleitung liegt) und warten, bis «Gradle Sync» fertig ist. Beim ersten Mal dauert das wegen der Downloads einige Minuten.
3. Links unten «Build Variants» öffnen und bei `app` die Variante **syncRelease** wählen.
4. Menü «Build», «Build App Bundle(s) / APK(s)», «Build APK(s)».
5. Die fertige APK liegt unter `app/build/outputs/apk/sync/release/`.

### Über die Kommandozeile

Mit installiertem Android-SDK und Java 17 im Projektordner:

```
gradlew.bat :app:assembleSyncRelease      (Windows)
./gradlew :app:assembleSyncRelease        (macOS / Linux)
```

### Wichtig zur Signatur

Die APK wird mit dem Debug-Schlüssel deines PCs signiert. Spätere Updates musst du deshalb auf demselben PC bauen, sonst lässt Android das Update nicht über die alte Version installieren (dann zuerst deinstallieren, wobei der lokale Verlauf verloren geht; mit Sync holt er ihn danach aber wieder vom Server).

## Teil 3: Installieren

Es ist dieselbe APK für Handy und TV.

**Handy:** APK aufs Handy kopieren und öffnen. Beim ersten Mal die Installation aus unbekannten Quellen für den Dateimanager erlauben.

**Android-TV:** Eine der folgenden Varianten:

1. App «Downloader» (von AFTVnews) aus dem Play Store installieren, die APK irgendwo per Link bereitstellen (z.B. Google Drive Direktlink) und dort herunterladen.
2. App «Send Files to TV» auf Handy und TV installieren und die APK so übertragen.
3. Per ADB vom PC: Auf dem TV Entwickleroptionen und «ADB-Debugging» einschalten, dann `adb connect <IP-des-TV>` und `adb install CloudStream-Sync.apk`.

## Teil 4: Sync einschalten (auf jedem Gerät)

1. Einstellungen, «Allgemein», ganz oben die Gruppe «Cloud-Sync».
2. «Server-Adresse» eintragen, z.B. `https://sync.deinedomain.ch`.
3. «Zugangs-Token» eintragen.
4. «Verbindung testen» antippen; es muss «Verbindung OK» erscheinen.
5. Schalter «Fortschritt synchronisieren» einschalten. Dabei wird der bisherige Verlauf des Geräts einmal hochgeladen.

Zuerst auf dem Handy einrichten, danach auf dem TV; der TV übernimmt dann direkt den Stand vom Handy.

## Repo-JSON und Erweiterungen

Unverändert wie im Original: Einstellungen, «Erweiterungen», «Repository hinzufügen» und die URL der Repo-JSON eingeben. Die Repositories selbst werden nicht synchronisiert; richte sie auf beiden Geräten gleich ein.

## Was geändert wurde (für spätere Updates des Forks)

| Datei | Änderung |
|---|---|
| `app/.../utils/CloudSync.kt` | Neu: Upload, Download, Zusammenführen |
| `app/.../utils/DataStoreHelper.kt` | Meldet Änderungen an Position, Weiterschauen und Gesehen-Status an CloudSync |
| `app/.../MainActivity.kt` | Holen beim Öffnen und jede Minute im Vordergrund, Hochladen beim Verlassen |
| `app/.../ui/home/HomeViewModel.kt` | Holt den neuesten Stand vor dem Anzeigen von «Weiterschauen» |
| `app/.../ui/settings/SettingsGeneralScreen.kt` | Einstellungsgruppe «Cloud-Sync» |
| `shared/.../DevicePreferenceStore.kt` | Einstellungen für Server, Token, Ein/Aus |
| `app/.../utils/InAppUpdater.kt` | Keine Update-Hinweise auf das offizielle CloudStream |
| `app/build.gradle.kts`, `app/src/sync/` | Build-Variante `sync` mit eigenem Paketnamen und App-Namen |
| `sync-server/` | Server (Node.js ohne Abhängigkeiten, Docker, Caddy für HTTPS) |

Um später auf eine neue CloudStream-Version zu wechseln, genügt es, diese Änderungen auf den neuen Stand zu übertragen; die Datei `cloudstream-sync.patch` enthält sie gesammelt (`git apply cloudstream-sync.patch`).

## Bekannte Grenzen

1. Die App holt den Stand beim Öffnen und danach jede Minute, solange sie sichtbar ist. Ein Wechsel ist also spätestens nach etwa einer Minute auf dem anderen Gerät sichtbar; wer direkt weiterschauen will, öffnet die App dort einfach neu.
2. Lesezeichen, Bibliothek und Einstellungen werden nicht synchronisiert, nur Fortschritt, Weiterschauen und Gesehen-Status.
3. CloudStream steht unter der GPL-3.0. Für den privaten Gebrauch spielt das keine Rolle; gibst du die App weiter, musst du den Quellcode mitliefern.
