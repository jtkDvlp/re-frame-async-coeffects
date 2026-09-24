@../claude-guidelines/CLAUDE.md

# re-frame-async-coeffects

Die projektübergreifenden Richtlinien oben gelten (Checkout von
`jtkDvlp/claude-guidelines` daneben). Hier steht nur, was diese Bibliothek
zusätzlich braucht, und wo sie bewusst abweicht.

## Was die Bibliothek tut

Eine re-frame-Erweiterung, die asynchrone Eingangsdaten (HTTP, IPC,
Browser-APIs) als **Coeffect** eines Events behandelt — statt als Kette aus
Effect, Erfolgs-Event und weiterem Event.

| Funktion | Zweck |
|---|---|
| `reg-acofx` | Registriert einen acofx-Handler unter einer Id (Registrar-Art `:acofx`). Er bekommt die Coeffects und die Args und liefert einen Kanal. |
| `reg-acofx-by-fx` | Macht einen bestehenden Effect (etwa `:http-xhrio`) zum acofx, indem er dessen Erfolgs- und Fehler-Event-Schlüssel belegt. |
| `inject-acofx`, `inject-acofxs` | Interceptoren, die ein oder mehrere acofxs nebenläufig ausführen und die Ergebnisse in die Coeffects des Events legen. |
| `set-global-on-failure-event` | Event für Fehler, für die sonst niemand ein Event nennt. |
| `…async-coeffects.tasks/inject-acofx(s)` | Dieselbe Injektion, hält dabei einen re-frame-tasks-Task am Laufen, solange die acofxs laufen. |

**So läuft eine Injektion.** Ein Lauf des Events, der auf eine Injektion
ohne Ergebnisse trifft, startet deren acofxs und bricht vor dem Handler ab.
Sind sie fertig, werden die Ergebnisse in einem Atom geparkt und das Event
mit einer `::dispatch-id` in der Meta erneut ausgelöst. Spätere Läufe
finden sie dort; entfernt werden sie erst unmittelbar vor dem Event-Handler
— so überstehen sie weitere Abbrüche, und ein Handler, der wirft, lässt
nichts zurück. Welche Teile davon anderer Code (re-frame-tasks) liest,
sagen die Docstrings und `WATCHOUT`s im Interceptor-Abschnitt.

## Aufbau

| Pfad | Inhalt |
|---|---|
| `src/jtk_dvlp/re_frame/async_coeffects.cljs` | Die Bibliothek. |
| `src/jtk_dvlp/re_frame/async_coeffects/tasks.cljs` | Anbindung an re-frame-tasks. Eigener Namespace, damit nur braucht, wer ihn einbindet. |
| `test/jtk_dvlp/re_frame/` | Tests und der Test-Runner für node. |
| `dev/jtk_dvlp/your_project.cljs` | Demo-App, von der README als Beispiel verlinkt. |
| `dev/user.clj` | REPL-Einstieg: `fig-init` startet figwheel-main, `cljs-repl` hängt sich an. |
| `dev.cljs.edn` | figwheel-main-Build `dev`, Port 9801, Ausgabe nach `target/`. |

**Abweichung: Die Namespace-Wurzel ist `jtk-dvlp.…`, nicht
`jtkdvlp.<artifact>`.** Sie ist älter als die Richtlinie und öffentliche
API; eine Umbenennung bricht jeden Nutzer. Nur zusammen mit einer
Major-Version und nur nach ausdrücklicher Entscheidung — nicht als
Nebenprodukt anderer Arbeit.

## Abhängigkeiten

Alle Laufzeit-Abhängigkeiten (`re-frame`, `reagent`, `core.async`,
`jtk-dvlp/core.async-helpers`) stehen bewusst im Profil `:provided`: Die
Anwendung wählt die Versionen, die Bibliothek bringt keine eigenen mit.
Neue Laufzeit-Abhängigkeiten gehören ebenfalls dorthin.

**WATCHOUT:** Alles, was aus einem Kanal liest, läuft über `jtk-dvlp.async`
(`go`, `<!`, `reduce`, …). `cljs.core.async` nur für Operationen, die
nichts lesen: `put!`, `close!`, `merge`, `timeout`. Ein einfaches
`core.async/<!` nimmt einen Fehler stumm als Wert entgegen, und die
`on-failure`-Behandlung bekommt ihn nie zu sehen (siehe die Richtlinie zu
core.async-helpers).

**WATCHOUT: re-frame-tasks 3 ist noch nicht veröffentlicht.** Die Anbindung
ist gegen dessen Zweig `refactor` auf Commit `096bbd8` geschrieben. Bis
zum Release holen die Tests die Quellen aus einem Checkout unter
`target/deps/re-frame-tasks/` (die CI checkt ihn aus, `project.clj` legt
ihn auf den `:test`-Quellpfad), und ein Release dieser Bibliothek muss
darauf warten. Danach wird der Checkout durch die Abhängigkeit ersetzt,
unter `:provided`.

## Zweige und Versionen

| Name | Bedeutung |
|---|---|
| `master` | Default-Branch, veröffentlichte 2.x-API (`v2.0.0`). |
| `v3.0.0` | Umbau von Interna und API auf 3.0.0. PRs für 3.0 gehen hierhin. |
| Tags `v1.0.0`, `v1.0.1`, `v2.0.0` | Releases. Tags tragen ein `v`. |

## Release

| Frage | Antwort für dieses Projekt |
|---|---|
| Hauptzweig | `master` |
| Wer trägt die Version? | `project.clj`, die `defproject`-Zeile, markiert mit `x-release-please-version`. Dort steht das letzte Release; die nächste Version setzt release-please. |
| Paket-Repository | Clojars, `net.clojars.jtkdvlp/re-frame-async-coeffects` |
| Secrets | `CLOJARS_USERNAME` und `CLOJARS_PASSWORD` — ein Deploy-Token, eingeschränkt auf dieses eine Artefakt |
| Veröffentlichungsbefehl | `lein deploy clojars` (Ziel steht als `:deploy-repositories` in `project.clj`) |
| Was die CI prüft | `.github/workflows/test.yml` — derselbe Workflow läuft vor der Veröffentlichung noch einmal gegen den Tag |

**Tags tragen ein `v`.** Die bisherigen Releases heißen `v1.0.0` bis
`v2.0.0`, deshalb `include-v-in-tag: true` und
`include-v-in-release-name: true`; ohne sie fände release-please die
Historie nicht wieder und finge bei `1.0.0` an.
`include-component-in-tag: false` hält den Paketnamen aus dem Tag.

**3.0.0 ergibt sich aus `!` und den `BREAKING CHANGE:`-Footern** der
Commits, die die 2.x-API brechen. Diese Footer sind das Changelog der
Brüche; der README-Abschnitt *Migrating from 2.x* ist die Langfassung.

**`.release-please-manifest.json` gehört der Maschine.** Nicht von Hand
editieren.

## Tests

Laufen unter node, wie in `.github/workflows/test.yml`. Lokal müssen zuerst
die re-frame-tasks-Quellen da sein:

```
git clone https://github.com/jtkDvlp/re-frame-tasks target/deps/re-frame-tasks
git -C target/deps/re-frame-tasks checkout 096bbd8
lein with-profile +test,-dev run -m cljs.main --target node \
  --output-dir target/test --output-to target/test/main.js \
  --compile-opts '{:main jtk-dvlp.re-frame.test-runner}' \
  --compile jtk-dvlp.re-frame.test-runner
node target/test/main.js
```

**WATCHOUT: `cljs.main` cacht kompilierte Abhängigkeiten global**
(AOT-Cache unter `~/.cljs`), samt dem Ausgabepfad des Builds, der ihn
gefüllt hat. Nach einem Build in ein anderes Ausgabeverzeichnis kann ein
node-Lauf an einer fehlenden `react.inc.js` unter dem alten Pfad scheitern.
`--compile-opts '{:aot-cache false}'` oder `~/.cljs` löschen hilft.
