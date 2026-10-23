package com.localshare.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

data class Nearby(val name: String, val url: String)

/** Finds phones that are sharing on this Wi-Fi (they advertise _localshare._tcp) so nothing has to be typed. */
class Discovery(ctx: Context, private val onFound: (Nearby) -> Unit, private val onLost: (String) -> Unit) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        lock = wifi.createMulticastLock("localshare-tv").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { enqueue(s) }
            override fun onServiceLost(s: NsdServiceInfo) { onLost(s.serviceName) }
        }
        listener = l
        try { nsd.discoverServices("_localshare._tcp.", NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { listener = null }
    }

    @Synchronized
    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (e: Exception) { } }
        listener = null
        lock?.takeIf { it.isHeld }?.release()
        lock = null
        queue.clear()
        resolving = false
    }

    // Older Android versions can only resolve one service at a time.
    @Synchronized
    private fun enqueue(s: NsdServiceInfo) {
        queue.addLast(s)
        next()
    }

    @Synchronized
    private fun next() {
        if (resolving) return
        val s = queue.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) { done() }
            override fun onServiceResolved(i: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = i.host?.hostAddress
                if (host != null && !host.contains(':')) onFound(Nearby(i.serviceName, "http://$host:${i.port}"))
                done()
            }
        })
    }

    @Synchronized
    private fun done() { resolving = false; next() }
}
