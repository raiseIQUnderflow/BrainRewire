package com.example.brainrewire.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import com.example.brainrewire.MainActivity
import com.example.brainrewire.R
import com.example.brainrewire.data.BlocklistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optimized VPN Service for DNS-based content filtering.
 * Uses caching and HashSet lookups for fast performance.
 */
class BrainRewireVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var vpnThread: Thread? = null
    private lateinit var blocklistManager: BlocklistManager

    // Use HashSet for O(1) lookups instead of iterating
    @Volatile private var blockedDomainsSet: HashSet<String> = HashSet()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // DNS response cache - avoids repeated upstream queries
    private val dnsCache = LruCache<String, CachedResponse>(500)

    private data class CachedResponse(
        val response: ByteArray,
        val timestamp: Long,
        val blocked: Boolean
    )

    companion object {
        private const val TAG = "BrainRewireVPN"
        const val NOTIFICATION_CHANNEL_ID = "brainrewire_vpn"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.example.brainrewire.VPN_START"
        const val ACTION_STOP = "com.example.brainrewire.VPN_STOP"

        private const val VPN_ADDRESS = "10.215.173.1"
        private const val VPN_DNS = "10.215.173.2"

        // Single fast DNS server (Cloudflare is typically fastest)
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val DNS_TIMEOUT = 2000 // 2 seconds
        private const val CACHE_TTL = 300_000L // 5 minutes

        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        blocklistManager = BlocklistManager(applicationContext)
        loadBlockedDomains()
        createNotificationChannel()
    }

    private fun loadBlockedDomains() {
        serviceScope.launch {
            blocklistManager.blockedDomains.collect { domains ->
                // Convert to HashSet for O(1) lookups
                blockedDomainsSet = HashSet(domains)
                Log.d(TAG, "Loaded ${domains.size} blocked domains")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning.get()) return

        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            blocklistManager.refreshIfNeeded()
        }

        try {
            val builder = Builder()
                .setSession("BrainRewire")
                .addAddress(VPN_ADDRESS, 24)
                // Only intercept DNS traffic to common DNS servers
                .addRoute(VPN_DNS, 32)
                .addRoute("1.1.1.1", 32)
                .addRoute("1.0.0.1", 32)
                .addRoute("8.8.8.8", 32)
                .addRoute("8.8.4.4", 32)
                .addDnsServer(VPN_DNS)
                .setMtu(1500)
                .setBlocking(true)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                isRunning.set(true)
                isServiceRunning = true
                Log.i(TAG, "VPN started")

                vpnThread = Thread(DnsProxy(), "DNS-Proxy")
                vpnThread?.start()

                runBlocking { blocklistManager.setVpnEnabled(true) }
            } else {
                stopVpn()
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN start error: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning.set(false)
        isServiceRunning = false
        vpnThread?.interrupt()
        vpnThread = null

        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        dnsCache.evictAll()

        runBlocking { blocklistManager.setVpnEnabled(false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private inner class DnsProxy : Runnable {
        override fun run() {
            val fd = vpnInterface ?: return
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val buffer = ByteArray(4096)

            while (isRunning.get() && !Thread.interrupted()) {
                try {
                    val len = input.read(buffer)
                    if (len > 0) {
                        handlePacket(buffer, len, output)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Read error: ${e.message}")
                }
            }
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 28) return

        // Quick checks
        val version = (buffer[0].toInt() and 0xF0) shr 4
        if (version != 4) return

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val ipHeaderLen = (buffer[0].toInt() and 0x0F) * 4
        val dstPort = ((buffer[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                      (buffer[ipHeaderLen + 3].toInt() and 0xFF)

        if (dstPort != 53) return // DNS only

        val srcIp = ByteArray(4)
        System.arraycopy(buffer, 12, srcIp, 0, 4)

        val srcPort = ((buffer[ipHeaderLen].toInt() and 0xFF) shl 8) or
                      (buffer[ipHeaderLen + 1].toInt() and 0xFF)

        val dnsStart = ipHeaderLen + 8
        val dnsLen = length - dnsStart
        if (dnsLen < 12) return

        val dnsQuery = ByteArray(dnsLen)
        System.arraycopy(buffer, dnsStart, dnsQuery, 0, dnsLen)

        val domain = parseDomain(dnsQuery) ?: return

        // Check cache first
        val cached = dnsCache.get(domain)
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL) {
            sendResponse(cached.response, srcIp, srcPort, output)
            return
        }

        // Check if blocked (O(1) lookup)
        if (shouldBlock(domain)) {
            Log.d(TAG, "Blocked: $domain")
            val response = buildBlockedResponse(dnsQuery)
            dnsCache.put(domain, CachedResponse(response, System.currentTimeMillis(), true))
            sendResponse(response, srcIp, srcPort, output)
        } else {
            // Forward to upstream DNS
            forwardQuery(domain, dnsQuery, srcIp, srcPort, output)
        }
    }

    private fun parseDomain(dns: ByteArray): String? {
        if (dns.size < 12) return null
        try {
            val sb = StringBuilder()
            var pos = 12
            while (pos < dns.size) {
                val len = dns[pos].toInt() and 0xFF
                if (len == 0) break
                if (sb.isNotEmpty()) sb.append('.')
                pos++
                repeat(len) {
                    if (pos < dns.size) sb.append(dns[pos++].toInt().toChar())
                }
            }
            return if (sb.isNotEmpty()) sb.toString().lowercase() else null
        } catch (_: Exception) {
            return null
        }
    }

    private fun shouldBlock(domain: String): Boolean {
        val d = domain.lowercase()

        // O(1) exact match
        if (blockedDomainsSet.contains(d)) return true

        // Check parent domains (limited iterations)
        var current = d
        repeat(5) { // Max 5 levels deep
            val dot = current.indexOf('.')
            if (dot < 0) return@repeat
            current = current.substring(dot + 1)
            if (blockedDomainsSet.contains(current)) return true
        }

        // Keyword check (only if strict mode likely enabled)
        return blocklistManager.containsAdultKeyword(d)
    }

    private fun forwardQuery(
        domain: String,
        query: ByteArray,
        clientIp: ByteArray,
        clientPort: Int,
        output: FileOutputStream
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = DNS_TIMEOUT

                val server = InetAddress.getByName(UPSTREAM_DNS)
                socket.send(DatagramPacket(query, query.size, server, 53))

                val respBuf = ByteArray(512)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPkt)
                socket.close()

                val response = respBuf.copyOf(respPkt.length)
                dnsCache.put(domain, CachedResponse(response, System.currentTimeMillis(), false))
                sendResponse(response, clientIp, clientPort, output)
            } catch (e: Exception) {
                Log.w(TAG, "DNS forward failed: ${e.message}")
            }
        }
    }

    private fun sendResponse(dnsResponse: ByteArray, clientIp: ByteArray, clientPort: Int, output: FileOutputStream) {
        try {
            val packet = buildUdpPacket(
                byteArrayOf(10, 215.toByte(), 173.toByte(), 2),
                clientIp, 53, clientPort, dnsResponse
            )
            synchronized(output) {
                output.write(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
        }
    }

    private fun buildBlockedResponse(query: ByteArray): ByteArray {
        val resp = ByteArray(query.size + 16)
        resp[0] = query[0]; resp[1] = query[1] // Transaction ID
        resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte() // Flags
        resp[4] = 0; resp[5] = 1 // Questions
        resp[6] = 0; resp[7] = 1 // Answers
        resp[8] = 0; resp[9] = 0; resp[10] = 0; resp[11] = 0

        var pos = 12
        var qPos = 12
        while (qPos < query.size && query[qPos].toInt() != 0) {
            val len = query[qPos].toInt() and 0xFF
            resp[pos++] = query[qPos++]
            repeat(len) { if (qPos < query.size) resp[pos++] = query[qPos++] }
        }
        resp[pos++] = 0; qPos++

        if (qPos + 4 <= query.size) {
            repeat(4) { resp[pos++] = query[qPos++] }
        } else {
            resp[pos++] = 0; resp[pos++] = 1; resp[pos++] = 0; resp[pos++] = 1
        }

        // Answer
        resp[pos++] = 0xC0.toByte(); resp[pos++] = 0x0C
        resp[pos++] = 0; resp[pos++] = 1 // A
        resp[pos++] = 0; resp[pos++] = 1 // IN
        resp[pos++] = 0; resp[pos++] = 0; resp[pos++] = 0; resp[pos++] = 60 // TTL
        resp[pos++] = 0; resp[pos++] = 4 // Length
        resp[pos++] = 0; resp[pos++] = 0; resp[pos++] = 0; resp[pos++] = 0 // 0.0.0.0

        return resp.copyOf(pos)
    }

    private fun buildUdpPacket(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)

        pkt[0] = 0x45.toByte()
        pkt[2] = (totalLen shr 8).toByte(); pkt[3] = totalLen.toByte()
        pkt[6] = 0x40.toByte()
        pkt[8] = 64; pkt[9] = 17
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)

        // IP checksum
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val cksum = sum.inv() and 0xFFFF
        pkt[10] = (cksum shr 8).toByte(); pkt[11] = cksum.toByte()

        // UDP header
        pkt[20] = (srcPort shr 8).toByte(); pkt[21] = srcPort.toByte()
        pkt[22] = (dstPort shr 8).toByte(); pkt[23] = dstPort.toByte()
        pkt[24] = (udpLen shr 8).toByte(); pkt[25] = udpLen.toByte()

        System.arraycopy(payload, 0, pkt, 28, payload.size)
        return pkt
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Content Filter", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BrainRewire content filter"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BrainRewireVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BrainRewire Active")
            .setContentText("Content filter enabled")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }
}

