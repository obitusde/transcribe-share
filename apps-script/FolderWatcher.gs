/**
 * FolderWatcher.gs — Transkription startet von selbst, sobald eine Aufnahme
 * im Arbeitsordner liegt.
 *
 * WARUM
 * -----
 * Der Web-Share-Target der PWA liefert die Datei nicht mehr aus: Chrome baut
 * den POST ohne sie zusammen (nachgemessen - leerer Multipart-Body, sofort,
 * unabhaengig von Groesse und Quell-App, auch mit frisch erzeugtem WebAPK und
 * aktuellem Manifest). Das passiert zwischen WebAPK und Chrome und ist von
 * der PWA aus nicht behebbar.
 *
 * Damit wird der Umweg zum Hauptweg: die Aufnahme geht per Teilen direkt in
 * die Drive-App, und dieser Trigger holt sie dort ab. Ablauf danach:
 *
 *     Recorder -> Teilen -> Drive.  Fertig.
 *
 * Kein Knopf, keine PWA, kein Share-Target - ein Tipp weniger als frueher.
 *
 * EINBAU
 * ------
 * 1. Diese Datei als neue Skriptdatei "FolderWatcher" einfuegen.
 * 2. Oben im Editor installFolderWatcher auswaehlen und einmal ausfuehren.
 *
 * Kein Deployen noetig: Zeit-Trigger laufen gegen den gespeicherten
 * Projektstand, nicht gegen die Bereitstellung der Web-App.
 */

/** Erlaubt sind nur 1, 5, 10, 15 oder 30. */
var WATCH_INTERVAL_MINUTES = 10;

/**
 * Wie lange eine Datei unveraendert sein muss, bevor sie angefasst wird.
 *
 * Schutz gegen halb hochgeladene Dateien: Drive zeigt eine Datei bereits,
 * waehrend der Upload vom Handy noch laeuft. Ohne diese Wartezeit bekaeme
 * AssemblyAI eine abgeschnittene Aufnahme und wuerde ein unvollstaendiges
 * Transkript zurueckliefern - ohne dass irgendwo ein Fehler auftaucht.
 */
var SETTLE_MINUTES = 2;

/** Einmalig von Hand ausfuehren. Legt den Trigger an. */
function installFolderWatcher() {
  removeFolderWatcher();

  ScriptApp.newTrigger('watchWorkFolder')
    .timeBased()
    .everyMinutes(WATCH_INTERVAL_MINUTES)
    .create();

  Logger.log('Ordnerbeobachtung aktiv: alle ' + WATCH_INTERVAL_MINUTES +
    ' Minuten wird "' + CONFIG.FOLDER_WORK_NAME + '" geprueft.');
}

/** Schaltet die Beobachtung wieder ab. */
function removeFolderWatcher() {
  ScriptApp.getProjectTriggers().forEach(function (trigger) {
    if (trigger.getHandlerFunction() === 'watchWorkFolder') {
      ScriptApp.deleteTrigger(trigger);
    }
  });
}

/**
 * Laeuft periodisch. Im Normalfall ein billiger Leerlauf: liegt nichts da,
 * ist nach einem Ordner-Scan Schluss.
 */
function watchWorkFolder() {
  var pending = getPendingAudioFiles();
  if (!pending.length) return;

  // getPendingAudioFiles() sortiert neueste zuerst.
  var ageMs = Date.now() - pending[0].getLastUpdated().getTime();
  if (ageMs < SETTLE_MINUTES * 60 * 1000) {
    Logger.log('watchWorkFolder: neueste Datei noch zu frisch, warte einen Durchlauf.');
    return;
  }

  var results = startAllPendingTranscriptions_();
  if (!results.length) return;

  if (results.some(function (r) { return r.status === 'processing'; })) {
    scheduleAutoCheck();
  }

  Logger.log('watchWorkFolder: ' + JSON.stringify(results));
}
