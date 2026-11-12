package com.zerobet.safeguard.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zerobet.safeguard.R
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * VPN locale "DNS-only".
 *
 * Come funziona:
 *  - L'interfaccia TUN dichiara come server DNS un indirizzo fittizio ([FAKE_DNS]) e instrada
 *    nella VPN SOLO quell'indirizzo (route /32). Tutto il resto del traffico del telefono
 *    continua a uscire normalmente dalla rete reale: nessun dato passa da server esterni.
 *  - Android invia quindi ogni query DNS a [FAKE_DNS]: i pacchetti arrivano a questo servizio,
 *    che li legge dalla TUN, estrae il dominio richiesto e decide:
 *      * dominio bloccato  -> risponde subito con 0.0.0.0 (sinkhole)
 *      * dominio consentito -> inoltra la query a un resolver pubblico e riscrive la risposta sulla TUN
 *
 * Limite noto: se sul telefono è attivo "DNS privato" (DNS-over-TLS) verso un provider specifico,
 * le query non passano dalla porta 53 e il filtro viene aggirato. Va impostato su "Disattivato"
 * o "Automatico" (vedi README).
 */
class DomainBlockerVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var filter: DomainFilter = DomainFilter.EMPTY
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var tunOutput: FileOutputStream? = null
    private val writeLock = Any()
    private var readerThread: Thread? = null
    private var forwardPool: ExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        // Mantiene in memoria uno snapshot aggiornato di whitelist e blacklist: ogni modifica
        // fatta dalla dashboard è attiva immediatamente, senza riavviare la VPN.
        val repo = (application as SafeGuardApp).repository
        scope.launch {
            combine(repo.whitelist, repo.blocked) { wl, bl -> DomainFilter(wl, bl) }
                .collect { filter = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        // Qualsiasi altro intent (ACTION_START, riavvio START_STICKY con intent null,
        // oppure "android.net.VpnService" inviato dal sistema per la VPN sempre attiva) avvia il filtro.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        )
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (tun != null) return
        // Se il consenso VPN è stato revocato, prepare() restituisce un Intent: non possiamo
        // mostrare dialoghi da un servizio, sarà la UI a richiederlo di nuovo.
        if (prepare(this) != null) {
            Log.w(TAG, "Consenso VPN mancante: impossibile avviare")
            stopSelf()
            return
        }
        val pfd = try {
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(FAKE_DNS)
                .addRoute(FAKE_DNS, 32)      // SOLO il DNS fittizio passa dalla VPN
                .setBlocking(true)
                .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(false) }
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Creazione interfaccia TUN fallita", e)
            null
        }
        if (pfd == null) {
            stopSelf()
            return
        }
        tun = pfd
        tunOutput = FileOutputStream(pfd.fileDescriptor)
        forwardPool = Executors.newFixedThreadPool(FORWARD_THREADS)
        readerThread = thread(name = "safeguard-dns-reader") { readLoop(pfd) }
        _running.value = true
        Log.i(TAG, "Filtro DNS avviato")
    }

    /** Loop principale: legge un pacchetto IP alla volta dalla TUN. */
    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val packet = ByteArray(MTU)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val length = input.read(packet)   // bloccante; lancia IOException alla chiusura della TUN
                if (length <= 0) continue
                try {
                    handlePacket(packet, length)
                } catch (e: Exception) {
                    Log.w(TAG, "Pacchetto scartato", e)
                }
            }
        } catch (e: Exception) {
            // TUN chiusa da stopVpn()/onRevoke(): uscita normale.
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int) {
        // 1) IPv4 + UDP verso la porta 53, tutto il resto viene ignorato.
        val datagram = DnsPackets.parseIpv4Udp(packet, length) ?: return
        if (datagram.dstPort != DNS_PORT) return

        // 2) Estrae il dominio dalla sezione "question" della query DNS.
        val question = DnsPackets.parseQuestion(datagram.payload)
        if (question == null) {
            forward(datagram)                     // non interpretabile: decide il resolver upstream
            return
        }

        // 3) Decisione: whitelist -> blacklist -> keyword (vedi DomainFilter).
        when {
            question.name == DOH_CANARY -> {
                // Firefox interroga questo dominio: NXDOMAIN gli dice di NON attivare il
                // DNS-over-HTTPS automatico, che scavalcherebbe il filtro.
                reply(datagram, DnsPackets.buildBlockedResponse(datagram.payload, question, nxDomain = true))
            }
            filter.shouldBlock(question.name) -> {
                _blockedCount.value += 1
                reply(datagram, DnsPackets.buildBlockedResponse(datagram.payload, question))
            }
            else -> forward(datagram)
        }
    }

    /**
     * Inoltra la query originale (byte per byte) a un resolver pubblico e riporta la risposta
     * sulla TUN. Eseguito su un thread pool perché l'attesa della risposta è bloccante.
     */
    private fun forward(datagram: UdpDatagram) {
        val pool = forwardPool ?: return
        pool.execute {
            val buffer = ByteArray(MTU - 28)
            for (server in UPSTREAM_DNS) {
                try {
                    DatagramSocket().use { socket ->
                        // protect() fa uscire il socket dalla rete reale e non dalla VPN.
                        protect(socket)
                        socket.soTimeout = UPSTREAM_TIMEOUT_MS
                        socket.send(
                            DatagramPacket(datagram.payload, datagram.payload.size, InetAddress.getByName(server), DNS_PORT)
                        )
                        val response = DatagramPacket(buffer, buffer.size)
                        socket.receive(response)
                        reply(datagram, buffer.copyOf(response.length))
                    }
                    return@execute
                } catch (e: Exception) {
                    // timeout o rete assente: prova il resolver successivo
                }
            }
        }
    }

    /** Scrive sulla TUN una risposta DNS, scambiando indirizzi e porte rispetto alla query. */
    private fun reply(query: UdpDatagram, dnsResponse: ByteArray) {
        val packet = DnsPackets.buildIpv4Udp(
            srcAddr = query.dstAddr, dstAddr = query.srcAddr,
            srcPort = query.dstPort, dstPort = query.srcPort,
            payload = dnsResponse
        )
        synchronized(writeLock) {
            try {
                tunOutput?.write(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Scrittura su TUN fallita", e)
            }
        }
    }

    private fun stopVpn() {
        _running.value = false
        readerThread?.interrupt()
        readerThread = null
        forwardPool?.shutdownNow()
        forwardPool = null
        synchronized(writeLock) { tunOutput = null }
        try {
            tun?.close()                          // sblocca la read() del readLoop
        } catch (_: Exception) {
        }
        tun = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** Chiamato dal sistema se la VPN viene scollegata dalle Impostazioni o da un'altra app VPN. */
    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SafeGuardVpn"
        const val ACTION_START = "com.zerobet.safeguard.vpn.START"
        const val ACTION_STOP = "com.zerobet.safeguard.vpn.STOP"

        private const val CHANNEL_ID = "safeguard_vpn"
        private const val NOTIFICATION_ID = 1001

        /** Indirizzi privati usati solo all'interno della TUN (blocco 10.111.222.0/24). */
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val FAKE_DNS = "10.111.222.2"

        /** Resolver pubblici reali, provati in ordine. */
        private val UPSTREAM_DNS = listOf("1.1.1.1", "8.8.8.8")
        private const val UPSTREAM_TIMEOUT_MS = 3000
        private const val DNS_PORT = 53
        private const val FORWARD_THREADS = 8

        /** MTU ampia: le risposte DNS con EDNS possono superare i classici 1500 byte. */
        private const val MTU = 8192

        private const val DOH_CANARY = "use-application-dns.net"

        private val _running = MutableStateFlow(false)
        /** Stato osservabile dalla dashboard. */
        val running: StateFlow<Boolean> = _running

        private val _blockedCount = MutableStateFlow(0L)
        /** Numero di richieste bloccate dall'avvio del processo. */
        val blockedCount: StateFlow<Long> = _blockedCount

        /** Avvia il filtro. Richiede che il consenso VPN sia già stato concesso. */
        fun start(context: Context) {
            val intent = Intent(context, DomainBlockerVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // startService (non stopService) così il servizio chiude la TUN in modo ordinato.
            try {
                context.startService(
                    Intent(context, DomainBlockerVpnService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        }
    }
}
