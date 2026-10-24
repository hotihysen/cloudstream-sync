# Flimmer: Anleitung

Fork von CloudStream mit einer zusätzlichen Funktion: Wiedergabeposition, «Weiterschauen»-Liste und Gesehen-Markierungen werden zwischen Handy und Android-TV abgeglichen. Erweiterungen und Repo-JSON funktionieren wie im Original. Die App heisst «Flimmer» und ist eigenständig; sie installiert sich neben einem vorhandenen CloudStream und holt Updates aus diesem Repository (Einstellungen, Updates, «Nach Updates suchen»).

## APK herunterladen

Fester Link auf die jeweils neueste Version:

```
https://github.com/hotihysen/cloudstream-sync/releases/latest/download/Flimmer.apk
```

Damit der Link ohne GitHub-Anmeldung funktioniert (z.B. in der TV-App «Downloader»), muss das Repository öffentlich sein: Repository, «Settings», ganz unten «Danger Zone», «Change visibility», «Public». Im Repository liegen keine Passwörter oder Schlüssel; der Signierschlüssel steckt geschützt in den Repository-Secrets und bleibt auch bei einem öffentlichen Repository geheim.

**Auf Google TV / Android-TV:**
1. Im Play Store die App «Downloader» (von AFTVnews) installieren.
2. In den TV-Einstellungen unter Apps, Sicherheit bzw. «Unbekannte Apps installieren» dem Downloader die Installation erlauben.
3. Im Downloader den Link oben eingeben, herunterladen, installieren.

Für spätere Updates denselben Link nochmals öffnen; die neue Version installiert sich über die alte, alle Daten bleiben erhalten.

Jede Änderung im Repository erzeugt automatisch eine neue Version (Reiter «Actions», danach «Releases»).

## Sync mit GitHub Gist einrichten (kein Server nötig)

### Einmalig: Token erstellen
1. GitHub, Profilbild, «Settings», «Developer settings», «Personal access tokens», «Tokens (classic)», «Generate new token (classic)».
2. Note: `CloudStream Sync`, Expiration: «No expiration» (oder ein Jahr).
3. Nur das Häkchen bei **gist** setzen, sonst nichts.
4. «Generate token» und den Token (beginnt mit `ghp_`) kopieren und gut aufbewahren.

Der Token darf nur Gists lesen und schreiben, sonst nichts in deinem Konto.

### Auf jedem Gerät
1. Einstellungen, «Allgemein», Gruppe «Cloud-Sync».
2. «Sync über»: **GitHub Gist**.
3. «GitHub-Token»: den Token eintragen.
4. «Verbindung testen»: es muss «Verbindung OK» erscheinen.
5. Schalter «Fortschritt synchronisieren» einschalten.

Zuerst auf dem Handy einrichten, danach auf dem TV. Beim ersten Mal legt die App in deinem Konto einen privaten Gist «CloudStream Sync (nicht löschen)» an; die anderen Geräte finden ihn automatisch.

Tipp für den TV: Den Token am Handy in eine Notiz kopieren und auf dem TV mit der Spracheingabe oder der Google-TV-App (Fernbedienung mit Handy-Tastatur) eingeben.

## Wie der Sync funktioniert

Jedes Gerät schreibt nur seine eigene Datei im Gist und liest die Dateien der anderen; so kann nichts gegenseitig überschrieben werden. Hochgeladen wird etwa alle 30 Sekunden während der Wiedergabe und beim Verlassen der App. Geholt wird beim Öffnen der App und danach jede Minute, solange sie offen ist. Pro Eintrag gewinnt der jüngste Stand.

Voraussetzungen: Auf beiden Geräten dieselben Erweiterungen installieren (Cloudstream ordnet die Einträge über IDs aus der Erweiterung zu) und dasselbe Profil verwenden (z.B. jeweils das erste).

Synchronisiert werden Position, «Weiterschauen» und Gesehen-Status; Lesezeichen, Bibliothek, Repositories und Einstellungen nicht.

## Alternative: eigener Server

Unter «Sync über» kann statt Gist «Eigener Server» gewählt werden. Der Server liegt im Ordner `sync-server` (Node.js ohne Abhängigkeiten, Docker, Caddy für HTTPS); die Einrichtung steht in den Kommentaren von `sync-server/docker-compose.yml` und `sync-server/server.js`.

## Lizenz

CloudStream steht unter der GPL-3.0. Ein öffentliches Repository erfüllt die Pflicht, den Quellcode zugänglich zu machen.
