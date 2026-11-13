# SafeGuard (ZeroBet)

App Android nativa (Kotlin, Jetpack Compose, Room) che aiuta le persone con problemi di gioco
compulsivo bloccando l'accesso a siti di **scommesse/casinò**, **trading/crypto** e
**prestiti/finanziarie**. La configurazione è protetta da un **PIN a 6 cifre** custodito da un
familiare o tutore.

Il blocco avviene interamente sul dispositivo tramite una VPN locale che filtra le sole richieste
DNS: nessun dato di navigazione viene inviato a server di terzi (a parte le normali query DNS verso
`1.1.1.1` / `8.8.8.8` e il download quotidiano delle blocklist pubbliche).

La blacklist si alimenta da sola: liste esterne aggiornate ogni 24 ore, scoperta di nuovi domini
dai log di Certificate Transparency, punteggio euristico sui nomi a dominio e riconoscimento dei
contenuti a schermo.

## Requisiti e build

- JDK 17, Android SDK 34 (minSdk 26 / Android 8.0)
- `local.properties` con `sdk.dir=...` (creato automaticamente da Android Studio)

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

## Struttura del progetto

```
app/src/main/
├── AndroidManifest.xml
├── res/xml/                       device_admin.xml, accessibility_service_config.xml
└── java/com/zerobet/safeguard/
    ├── SafeGuardApp.kt            Application + service locator
    ├── security/PinManager.kt     Hash PIN (SHA-256 + salt) in EncryptedSharedPreferences, lockout
    ├── data/
    │   ├── Entities.kt            BlockedDomain, WhitelistedBank
    │   ├── DomainDao.kt
    │   ├── AppDatabase.kt         Room + pre-popolamento blacklist
    │   ├── DefaultData.kt         Liste predefinite (domini, parole chiave, banche)
    │   └── DomainRepository.kt    Normalizzazione e validazione dei domini
    ├── vpn/
    │   ├── DomainBlockerVpnService.kt   VPN locale DNS-only
    │   ├── DnsPackets.kt                Parsing IPv4/UDP/DNS e risposta sinkhole
    │   ├── DomainFilter.kt              Whitelist → liste → parole chiave → euristica
    │   ├── DomainHeuristics.kt          Punteggio di rischio sul nome + domini "sicuri"
    │   └── FilterStore.kt               Filtro in memoria sincronizzato con Room
    ├── sync/
    │   ├── BlacklistSyncWorker.kt       WorkManager: fonti esterne, ADM, CONSOB, crt.sh
    │   ├── BlocklistParser.kt           Parser hosts/domains/adblock e estrazione da HTML
    │   └── CtLogDiscovery.kt            Certificate Transparency (parser JSON in streaming)
    ├── admin/SafeGuardDeviceAdminReceiver.kt
    ├── guard/
    │   ├── SettingsGuardAccessibilityService.kt   Guardiano Impostazioni + contenuti a schermo
    │   ├── ContentClassifier.kt                   Riconoscimento di scommesse/trading/prestiti nei testi
    │   ├── SearchQueryClassifier.kt               Ricerche a rischio nei motori di ricerca
    │   └── GuardState.kt                          Finestra di grazia dopo il PIN
    ├── security/DeviceStorage.kt  Storage "device protected" per Direct Boot
    ├── security/SecurityLog.kt    Registro Allarmi (scrittura in background, raggruppamento)
    ├── guard/IntegrityChecker.kt  Controllo dei servizi obbligatori
    ├── guard/InAppContentAndAdFilter.kt  Pubblicità e contenuti a rischio nelle app
    ├── guard/BlockedPackages.kt   App dedicate chiuse all'apertura
    ├── guard/AppContextRules.kt   Telegram (chat private / canali) e app della banca autorizzata
    ├── vpn/HashedDomainSet.kt     Liste esterne compatte (hash a 64 bit + ricerca binaria)
    ├── receiver/SimStateReceiver.kt  Rimozione/inserimento SIM
    ├── receiver/BootReceiver.kt   Riavvio al boot (LOCKED_BOOT_COMPLETED)
    └── ui/
        ├── MainActivity.kt        Onboarding → PIN → Dashboard
        ├── OnboardingScreen.kt    Wizard: PIN, banca, permessi
        ├── DashboardScreen.kt     Whitelist/blacklist, pausa, sblocco disinstallazione
        ├── LockScreenActivity.kt  Richiesta PIN sopra le Impostazioni
        └── components/PinPad.kt
```

## Come funziona il filtro

1. La VPN dichiara come DNS un indirizzo fittizio (`10.111.222.2`) e instrada nella TUN solo quello.
2. Per ogni query DNS (UDP/53) viene estratto il dominio richiesto:
   - **Whitelist banche** → inoltrata al resolver pubblico (ha sempre la precedenza);
   - **Blacklist** (dominio o sottodominio) o **parola chiave** contenuta nel nome → risposta
     `0.0.0.0` / `::` (sinkhole);
   - altrimenti → inoltrata.
3. Le banche note sono in blacklist di default: solo quella scelta nel wizard resta raggiungibile.

Le regole del tutore sono in `HashSet`; le liste esterne in un array ordinato di hash a 64 bit cercato
con ricerca binaria: match esatto, poi match sui domini padre (una ricerca per label), senza allocazioni.

## Threat intelligence e auto-alimentazione

| Modulo | Cosa fa |
|---|---|
| **Liste esterne** (`BlacklistSyncWorker`) | Ogni 24 ore, con rete disponibile, scarica in streaming StevenBlack (gambling), Block List Project (gambling), Peter Lowe (pubblicità/tracker), l'elenco ADM dei siti di gioco inibiti e gli oscuramenti CONSOB. Dalla dashboard: "Forza aggiornamento ora". |
| **Certificate Transparency** (`CtLogDiscovery`) | Interroga `crt.sh` per 6 radici lessicali al giorno (a rotazione su ~45) e salva i domini con certificati emessi negli ultimi 45 giorni. |
| **Euristica DNS** (`DomainHeuristics`) | Per i domini non in lista calcola un punteggio da TLD (`.bet`, `.casino`, `.forex`, `.loan`…) e token nel nome (`22bet`, `casino`, `trading`, `prestito`, `bonus`…). Sopra soglia: sinkhole e salvataggio tra gli auto-appresi. |
| **Anti-DoH** | I resolver DoH noti (`dns.google`, `cloudflare-dns.com`, `dns.quad9.net`…) sono in blacklist fissa; il canary di Firefox riceve NXDOMAIN. |
| **Contenuti a schermo** (`ContentClassifier`) | Il servizio di accessibilità legge i testi visibili in browser e app: con una combinazione ad alta confidenza di espressioni (es. "bonus benvenuto" + "gioca ora", "calcola la tua rata" + "TAEG") torna indietro e, se è un sito esterno, ne aggiunge il dominio agli auto-appresi. Vale anche dentro il sito della banca in whitelist (senza però bloccarne il dominio). |
| **Ricerche** (`SearchQueryClassifier`) | Se in un motore di ricerca (Google, Bing, DuckDuckGo, Yahoo, Brave, Ecosia, Startpage, Qwant… e ricerca di YouTube) viene scritta una ricerca su scommesse, trading/investimenti o prestiti, l'app esce dal motore di ricerca e torna alla Home. Qui basta una parola ("trading", "investimenti", "mutuo", un marchio…), ma il confronto è per parola intera: "investigatore", "betulla", "giocatore in prestito", "borsa di studio" non scattano. Le ricerche di aiuto ("come smettere di giocare", "ludopatia numero verde") non sono mai bloccate. |
| **App: ricerche** | Il blocco delle ricerche vale in qualsiasi app (YouTube, Instagram, Facebook, TikTok, Telegram, X, Reddit, Play Store, Google News…): le caselle di ricerca sono riconosciute dall'id della vista o dal suggerimento ("Cerca"). Azione: Indietro e, se dopo 400 ms la ricerca è ancora a schermo, Home. Nel registro: *"Ricerca bloccata su YouTube: trading online"*. |
| **App: pubblicità e contenuti** (`InAppContentAndAdFilter`) | Sui testi visibili, un elemento alla volta. **Pubblicità**: etichetta di annuncio ("Sponsorizzato", "Annuncio", "Ad"…) o invito all'azione ("Installa", "Scopri di più") vicino a un termine a rischio. **Contenuti**: un titolo/post/articolo con un'espressione inequivocabile ("come investire", "trading online", "pronostici serie A", "prestito veloce") o almeno due termini a rischio; "manovra finanziaria", "inflazione ISTAT", "stipendi pubblici" non scattano. Azione a scala: scorrimento in avanti nei feed, poi Indietro, poi Home. |
| **App dedicate** (`BlockedPackages`) | Le app di trading, borsa, crypto, scommesse, poker, casinò e finanziarie vengono chiuse all'apertura con una schermata di avviso: elenco di package noti, euristica sul nome del package e sul nome visibile. Resta libera l'app della banca in whitelist. Nel Play Store la scheda di un'app di questo tipo viene chiusa prima che si possa premere "Installa". |
| **Banca autorizzata: sito + app** | Il catalogo delle banche (`DefaultData.bankPresets`) associa a ogni istituto domini web e package delle app ufficiali. L'app della banca in whitelist resta utilizzabile (saldo, movimenti, bonifici), ma il Guardiano chiude le sezioni il cui titolo in alto è *Richiedi prestito*, *Prestito istantaneo*, *Cessione del quinto*, *Finanziamento*, *Investimenti*, *Trading*, *Compra azioni*, *Crypto*. Le app delle altre banche del catalogo e delle finanziarie (Findomestic, Compass, Agos, Cofidis, IBL…) sono bloccate all'apertura. |
| **Telegram** | Ricerca globale sempre controllata. Canali, gruppi e bot (sottotitolo "iscritti", "membri", "bot", pulsanti "Unisciti", "Silenzia"): filtro standard. Chat private ("online", "ultimo accesso", "sta scrivendo") ed elenco delle chat: le parole discorsive non contano, si blocca solo per link o domini a rischio (filtro DNS) e inviti a bot o canali con nome a rischio (`@CasinoBonusBot`, `t.me/segnalitrading`). |
| **Bypass di sistema** | PIN del tutore anche per: installazione manuale di APK (dialogo del package installer e pagina "Installa app sconosciute"), utenti multipli / ospite / secondo spazio / spazio privato / app clonate, opzioni sviluppatore e debug wireless, opzioni di ripristino (rete, preferenze app, dati di fabbrica). Per le pagine delle Impostazioni conta solo il titolo: la pagina "Sistema", che elenca queste voci, resta libera. |
| **DNS privato** | Il Guardiano chiede il PIN sul dialogo "DNS privato" delle Impostazioni; la dashboard avvisa se è impostato un provider. |

Le voci delle liste esterne già coperte da parole chiave o euristica non vengono salvate (su circa
445.000 righe scaricate ne restano circa 210.000). Nella scheda **Blacklist** della dashboard compaiono
sia le regole dell'app e del tutore sia i domini delle liste esterne (caricati a pagine di 200, con
ricerca per nome e "Consenti" per i falsi positivi). I domini auto-appresi sono visibili nella scheda
**Appresi** della dashboard, dove il tutore può rimuoverli o consentirli in modo permanente.

**Privacy.** I testi letti a schermo sono analizzati in memoria sul dispositivo e scartati subito (tranne la ricerca bloccata, salvata nel Registro Allarmi):
non vengono salvati né trasmessi. L'unico dato registrato è il dominio bloccato con il motivo.
Sono escluse dall'analisi le app di sistema, telefono, SMS, WhatsApp, Telegram, Signal, Gmail e Outlook.

## Avvio al boot e controllo di integrità

- **Direct Boot.** Servizio VPN, Guardiano, `BootReceiver`, `SimStateReceiver` e Device Admin sono
  `directBootAware`. Hash del PIN, stato della protezione, database e copia compatta delle liste stanno
  nello storage *device protected* (`DeviceStorage`), quindi il filtro riparte su
  `LOCKED_BOOT_COMPLETED`, prima che venga inserito il codice di sblocco. I dati delle versioni
  precedenti vengono spostati al primo avvio.
- **Controllo di integrità** (`IntegrityChecker`): allo sblocco dello schermo (`USER_PRESENT`), all'avvio,
  dal Guardiano e ogni 15 minuti. VPN spenta con consenso valido → riavviata subito. VPN senza consenso o
  Guardiano disattivato senza PIN → evento critico, notifica ad alta priorità e schermata di blocco che
  chiede di riattivare il servizio.
- **Risparmio energetico**: wizard e dashboard chiedono l'esenzione dall'ottimizzazione della batteria.

## Registro Allarmi

Scheda della dashboard (quindi dietro PIN) alimentata dalla tabella Room `security_event_log`, con
gravità INFO / WARNING / CRITICAL e filtri *Tutti*, *Manomissione / SIM*, *Ricerche bloccate*, *Domini bloccati*.
Un badge mostra gli eventi critici avvenuti dall'ultimo accesso del tutore. Registra:

- SIM rimossa, inserita o con operatore cambiato (CRITICAL, anche offline e a schermo bloccato);
- tentativi di aprire le Impostazioni protette (VPN, Accessibilità, Device Admin, DNS privato, disinstallazione);
- PIN errati con il numero del tentativo; spegnimenti e riavvii; servizi obbligatori trovati spenti;
- ricerche bloccate (con il testo cercato) e pagine chiuse per i contenuti;
- domini bloccati dal filtro DNS, raggruppati: più richieste allo stesso dominio in un minuto sono una riga con contatore.

Il registro tiene le ultime 5.000 righe; si svuota solo dalla dashboard, dopo una conferma, e la pulizia
lascia a sua volta una riga.

## Consumi

| Misura | Valore |
|---|---|
| Liste esterne in memoria (211.000 domini, array ordinato di hash a 64 bit) | 1,6 MB (circa 22 MB con `HashSet<String>`) |
| Query DNS, media su 10.000 (unit test, JVM desktop) | circa 2 µs |
| Ricerca, media su 1.000 (unit test, JVM desktop) | circa 60 µs |
| Heap Java del processo sull'emulatore, a riposo | circa 12 MB (prima circa 28 MB) |
| CPU a riposo sull'emulatore | 0% |

Il Guardiano segue tutte le app tranne quelle scartate in O(1) (interfaccia di sistema, launcher,
tastiere, telefono, fotocamera, messaggi privati). Cambio di finestra e testo digitato si valutano
subito; scorrimento e aggiornamenti del contenuto dopo 350 ms di quiete. La schermata è letta in un
solo passaggio e, se il suo hash è uguale a quello precedente, non viene analizzata; i testi già
valutati restano in una cache. Misurato sull'emulatore con i tick di CPU del processo: circa 2% di un core durante lo scorrimento continuo
del feed di YouTube (la stessa cifra della versione precedente: è la lettura dell'albero dei nodi tramite il
sistema), 0% a riposo. Il valore "0,0–0,2%" riportato in precedenza era sottostimato dal campionamento di `top`. Il thread della VPN dorme in una lettura bloccante quando non c'è traffico DNS.
`./gradlew testDebugUnitTest` rigenera i numeri in `app/build/reports/safeguard-benchmark.txt`.

## Prove strumentate sull'emulatore (solo build di debug)

La build di debug contiene `DebugTestReceiver` (cartella `app/src/debug`, assente nella build di rilascio),
protetto dal permesso `DUMP`: lo può usare solo la shell adb.

```bash
adb shell am broadcast -a com.zerobet.safeguard.debug.SET_PIN --es pin 123456 -n com.zerobet.safeguard/.debug.DebugTestReceiver
```

```bash
adb shell am broadcast -a com.zerobet.safeguard.debug.SIM --es state ABSENT --ei slot 0 -n com.zerobet.safeguard/.debug.DebugTestReceiver
```

Il primo imposta un PIN (sostituisce quello esistente), il secondo simula la SIM rimossa (`PRESENT` la reinserisce)
passando per la stessa logica del receiver, perché la SIM dell'emulatore non si può togliere. Sull'emulatore di
sviluppo è impostato un PIN di prova, salvato in `emulator-test-pin.txt` (escluso da git).

## Configurazione consigliata sul telefono

- **Impostazioni → Rete → VPN → SafeGuard → "VPN sempre attiva"**: il sistema riavvia da solo il filtro.
- **DNS privato** su *Disattivato* o *Automatico*: con un provider DoT impostato a mano le query non
  passano dalla porta 53 e il filtro viene aggirato.
- Escludere SafeGuard dall'ottimizzazione batteria sui dispositivi con gestione aggressiva (Xiaomi, Huawei…).

## Limiti noti

SafeGuard è un deterrente efficace, non una barriera assoluta. In particolare:

- un browser con **DNS-over-HTTPS** configurato per indirizzo IP (o verso un resolver non in lista), un'altra app VPN
  o un proxy aggirano il filtro DNS;
- `crt.sh` è un servizio pubblico gratuito che risponde spesso con errori 502 o timeout: la scoperta via
  Certificate Transparency va considerata "best effort";
- la lista StevenBlack indicata comprende anche adware/malware generici e, insieme a taboola/outbrain e ai
  comparatori (facile.it, segugio.it), può rompere annunci o funzioni di siti e app legittimi;
- euristica e riconoscimento dei contenuti possono dare **falsi positivi** (es. un articolo di giornale sui prestiti):
  il tutore li corregge dalla scheda "Appresi" con "Consenti";
- l'auto-apprendimento da schermo legge la barra degli indirizzi solo dei browser supportati (Chrome, Firefox,
  Samsung Internet, Brave, Edge, Opera, Vivaldi, DuckDuckGo); negli altri il contenuto viene bloccato ma il dominio non viene appreso;
- il blocco delle ricerche legge la barra degli indirizzi dei browser supportati e le caselle di ricerca delle
  pagine dei motori noti: un motore non in elenco o un browser non supportato non vengono controllati (restano
  attivi il riconoscimento dei contenuti e il filtro DNS);
- dalle pagine CONSOB i domini sono estratti con regex: i link di servizio noti sono esclusi, ma un sito legittimo
  citato in un comunicato potrebbe finire in lista;
- WhatsApp, Signal, SMS, Gmail, Outlook, telefono e contatti non vengono mai letti; Telegram sì (canali e ricerca),
  quindi una chat privata con due termini a rischio nello stesso messaggio può essere chiusa;
- il filtro in-app legge solo i testi: un video il cui titolo non dice nulla, o un annuncio fatto di sola immagine,
  non viene riconosciuto; un'app che non espone i testi all'accessibilità non è controllabile;
- un titolo che usa i termini a rischio in un altro senso può essere chiuso (falso positivo): la scala di azioni
  parte dallo scorrimento proprio per limitarne l'effetto;
- nelle app bancarie si guarda solo il titolo della schermata (i primi tre testi in alto): un'app che mette il
  titolo della sezione altrove non viene riconosciuta; la sezione "Mutuo" resta consultabile;
- su Telegram il tipo di chat è dedotto dai testi dell'intestazione: verificato solo con un'app fittizia che li
  riproduce, non con il client reale;
- le Opzioni sviluppatore sono protette, ma la verifica dal vivo non è stata possibile (sull'emulatore di prova
  sono disattivate); la protezione si basa sul titolo della pagina, come per Utenti e Ripristino, verificati;
- su Android recenti un "arresto forzato" di SafeGuard disattiva il servizio di accessibilità e fa ripartire l'app
  con i broadcast di avvio: viene registrato come manomissione critica ("SafeGuard è stato arrestato
  forzatamente") e il controllo di integrità chiede di riattivare il Guardiano;
- l'elenco delle app dedicate non può essere completo: quelle non in elenco sono riconosciute solo se il nome del
  package o dell'app contiene una parola a rischio;
- nelle pagine dei risultati dei motori di ricerca decide solo la ricerca scritta, non il testo dei risultati;
- Android limita l'apertura di schermate dallo sfondo: se il Guardiano è spento, la schermata di blocco del
  controllo di integrità può non aprirsi da sola e resta la notifica; se un task killer chiude tutto il
  processo, il controllo riparte entro 15 minuti;
- la rimozione della SIM è riconosciuta dagli eventi di sistema: un telefono acceso senza SIM dopo essere
  stato spento viene segnalato solo al primo evento SIM successivo all'avvio;
- un "arresto forzato" dell'app dalle Impostazioni (protette dal Guardiano) disattiva il servizio di accessibilità;
- il blocco agisce sui **siti/domini**; le app già installate che contattano domini non in lista non vengono fermate;
- il Device Admin impedisce la disinstallazione standard, ma non la **modalità provvisoria**, `adb` o il ripristino di fabbrica;
- il Guardiano riconosce le schermate delle Impostazioni dai testi (italiano/inglese) e dai package AOSP/Samsung/Xiaomi:
  su altre personalizzazioni OEM potrebbe servire estendere le liste in `SettingsGuardAccessibilityService`;
- le parole chiave (es. `trading`, `crypto`, `casino`) possono bloccare anche siti legittimi che le contengono:
  si possono rimuovere dalla dashboard;
- le liste esterne occupano circa 20 MB di database e alcune decine di MB di RAM;
- Google Play applica regole restrittive ad `AccessibilityService` e `VpnService`: per la pubblicazione sullo
  store servono le relative dichiarazioni e un'informativa chiara all'utente.

L'app va installata **con il consenso informato** della persona da proteggere e affiancata a un percorso di
supporto (in Italia: Telefono Verde Nazionale per le problematiche legate al Gioco d'Azzardo, 800 558822).
