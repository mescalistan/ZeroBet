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
    │   └── GuardState.kt                          Finestra di grazia dopo il PIN
    ├── receiver/BootReceiver.kt   Riavvio al boot
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

Ogni lookup è su `HashSet` in memoria: match esatto O(1), poi match sui domini padre (una ricerca
per label), anche con centinaia di migliaia di domini in lista.

## Threat intelligence e auto-alimentazione

| Modulo | Cosa fa |
|---|---|
| **Liste esterne** (`BlacklistSyncWorker`) | Ogni 24 ore, con rete disponibile, scarica in streaming StevenBlack (gambling), Block List Project (gambling), Peter Lowe (pubblicità/tracker), l'elenco ADM dei siti di gioco inibiti e gli oscuramenti CONSOB. Dalla dashboard: "Forza aggiornamento ora". |
| **Certificate Transparency** (`CtLogDiscovery`) | Interroga `crt.sh` per 6 radici lessicali al giorno (a rotazione su ~45) e salva i domini con certificati emessi negli ultimi 45 giorni. |
| **Euristica DNS** (`DomainHeuristics`) | Per i domini non in lista calcola un punteggio da TLD (`.bet`, `.casino`, `.forex`, `.loan`…) e token nel nome (`22bet`, `casino`, `trading`, `prestito`, `bonus`…). Sopra soglia: sinkhole e salvataggio tra gli auto-appresi. |
| **Anti-DoH** | I resolver DoH noti (`dns.google`, `cloudflare-dns.com`, `dns.quad9.net`…) sono in blacklist fissa; il canary di Firefox riceve NXDOMAIN. |
| **Contenuti a schermo** (`ContentClassifier`) | Il servizio di accessibilità legge i testi visibili in browser e app: con una combinazione ad alta confidenza di espressioni (es. "bonus benvenuto" + "gioca ora", "calcola la tua rata" + "TAEG") torna indietro e, se è un sito esterno, ne aggiunge il dominio agli auto-appresi. Vale anche dentro il sito della banca in whitelist (senza però bloccarne il dominio). |
| **DNS privato** | Il Guardiano chiede il PIN sul dialogo "DNS privato" delle Impostazioni; la dashboard avvisa se è impostato un provider. |

Le voci delle liste esterne già coperte da parole chiave o euristica non vengono salvate (su circa
445.000 righe scaricate ne restano circa 210.000). I domini auto-appresi sono visibili nella scheda
**Appresi** della dashboard, dove il tutore può rimuoverli o consentirli in modo permanente.

**Privacy.** I testi letti a schermo sono analizzati in memoria sul dispositivo e scartati subito:
non vengono salvati né trasmessi. L'unico dato registrato è il dominio bloccato con il motivo.
Sono escluse dall'analisi le app di sistema, telefono, SMS, WhatsApp, Telegram, Signal, Gmail e Outlook.

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
- dalle pagine CONSOB i domini sono estratti con regex: i link di servizio noti sono esclusi, ma un sito legittimo
  citato in un comunicato potrebbe finire in lista;
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
