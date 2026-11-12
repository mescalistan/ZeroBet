# SafeGuard (ZeroBet)

App Android nativa (Kotlin, Jetpack Compose, Room) che aiuta le persone con problemi di gioco
compulsivo bloccando l'accesso a siti di **scommesse/casinò**, **trading/crypto** e
**prestiti/finanziarie**. La configurazione è protetta da un **PIN a 6 cifre** custodito da un
familiare o tutore.

Il blocco avviene interamente sul dispositivo tramite una VPN locale che filtra le sole richieste
DNS: nessun dato di navigazione viene inviato a server di terzi (a parte le normali query DNS verso
`1.1.1.1` / `8.8.8.8`).

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
    │   └── DomainFilter.kt              Whitelist → blacklist → parole chiave
    ├── admin/SafeGuardDeviceAdminReceiver.kt
    ├── guard/
    │   ├── SettingsGuardAccessibilityService.kt   Guardiano Impostazioni
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

## Configurazione consigliata sul telefono

- **Impostazioni → Rete → VPN → SafeGuard → "VPN sempre attiva"**: il sistema riavvia da solo il filtro.
- **DNS privato** su *Disattivato* o *Automatico*: con un provider DoT impostato a mano le query non
  passano dalla porta 53 e il filtro viene aggirato.
- Escludere SafeGuard dall'ottimizzazione batteria sui dispositivi con gestione aggressiva (Xiaomi, Huawei…).

## Limiti noti

SafeGuard è un deterrente efficace, non una barriera assoluta. In particolare:

- un browser con **DNS-over-HTTPS** configurato manualmente, un'altra app VPN o un proxy aggirano il filtro DNS;
- il blocco agisce sui **siti/domini**; le app già installate che contattano domini non in lista non vengono fermate;
- il Device Admin impedisce la disinstallazione standard, ma non la **modalità provvisoria**, `adb` o il ripristino di fabbrica;
- il Guardiano riconosce le schermate delle Impostazioni dai testi (italiano/inglese) e dai package AOSP/Samsung/Xiaomi:
  su altre personalizzazioni OEM potrebbe servire estendere le liste in `SettingsGuardAccessibilityService`;
- le parole chiave (es. `trading`, `crypto`, `casino`) possono bloccare anche siti legittimi che le contengono:
  si possono rimuovere dalla dashboard;
- Google Play applica regole restrittive ad `AccessibilityService` e `VpnService`: per la pubblicazione sullo
  store servono le relative dichiarazioni e un'informativa chiara all'utente.

L'app va installata **con il consenso informato** della persona da proteggere e affiancata a un percorso di
supporto (in Italia: Telefono Verde Nazionale per le problematiche legate al Gioco d'Azzardo, 800 558822).
