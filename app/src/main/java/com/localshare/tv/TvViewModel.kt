package com.localshare.tv

import android.app.Application
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localshare.tv.pair.RelayServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class Media(val items: List<Entry>, val index: Int)

/** Files a phone is sharing from its browser page. Nothing is stored on the TV; they stream on demand. */
data class SharedFile(val id: Int, val name: String, val size: Long, val mime: String)
data class SharedBatch(val name: String, val files: List<SharedFile>)
data class SharedDevice(val sid: String, val name: String, val batches: List<SharedBatch>)

data class UiState(
    val phones: List<Phone> = emptyList(),
    val nearby: List<Nearby> = emptyList(),
    val pairUrl: String = "",
    val asking: Nearby? = null,
    val connecting: Boolean = false,
    val message: String? = null,
    val phone: Phone? = null,
    val relay: Boolean = false,
    val devices: List<SharedDevice> = emptyList(),
    val path: String = "",
    val entries: List<Entry> = emptyList(),
    val loading: Boolean = false,
    val video: Media? = null,
    val photos: Media? = null,
)

@Stable
interface TvActions {
    fun connect(phone: Phone)
    fun connectNearby(n: Nearby)
    fun connectAddress(address: String, password: String)
    fun submitPassword(password: String)
    fun cancelPassword()
    fun forget(phone: Phone)
    fun open(e: Entry)
    fun openShared()
    fun dismissMessage()
}

class TvViewModel(app: Application) : AndroidViewModel(app), TvActions {
    val store = Store(app)
    var api: Api? = null
        private set
    private val _state = MutableStateFlow(UiState(phones = store.phones()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var server: RelayServer? = null
    private var relayPort = 0
    private val discovery = Discovery(app, { n -> _state.update { s -> s.copy(nearby = s.nearby.filter { it.name != n.name && it.url != n.url } + n) } },
        { name -> _state.update { s -> s.copy(nearby = s.nearby.filter { it.name != name }) } })

    init {
        startRelay()
        discovery.start()
    }

    private fun localIp(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }

    /** One server for the whole time the app is open: it serves the phone page and relays the files. */
    private fun startRelay() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = localIp()
            if (ip == null) {
                _state.update { it.copy(message = "Connect this TV to Wi-Fi or Ethernet first") }
                return@launch
            }
            val listener = object : RelayServer.Listener {
                override fun onFiles(sid: String, device: String, batch: String, files: List<RelayServer.FileInfo>) = addFiles(sid, device, batch, files)
                override fun onGone(sid: String) = removeDevice(sid)
                override fun onPaired(name: String, url: String, password: String) { viewModelScope.launch { connect(Phone(name, url, password)) } }
            }
            val s = RelayServer(listener) { try { getApplication<Application>().assets.open("send.html").use { it.readBytes() } } catch (e: java.io.IOException) { ByteArray(0) } }
            try {
                relayPort = s.start()
                server = s
                _state.update { it.copy(pairUrl = "http://$ip:$relayPort/pair?t=${s.token()}") }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Could not start: ${e.message}") }
            }
        }
    }

    // ---- files shared from a phone's browser ----

    private fun addFiles(sid: String, device: String, batch: String, files: List<RelayServer.FileInfo>) {
        val add = files.map { SharedFile(it.id, it.name, it.size, it.mime) }
        _state.update { s ->
            val old = s.devices.firstOrNull { it.sid == sid }
            val batches = (old?.batches.orEmpty().filter { it.name != batch }) +
                SharedBatch(batch, old?.batches.orEmpty().firstOrNull { it.name == batch }?.files.orEmpty() + add)
            val devices = s.devices.filter { it.sid != sid } + SharedDevice(sid, device, batches)
            val go = s.phone == null && !s.relay
            val next = s.copy(devices = devices, relay = s.relay || go, path = if (go) "" else s.path)
            if (next.relay) next.copy(entries = relayEntries(devices, next.path)) else next
        }
    }

    private fun removeDevice(sid: String) {
        _state.update { s ->
            val devices = s.devices.filter { it.sid != sid }
            val path = if (s.path.startsWith("$sid|")) "" else s.path
            if (s.relay) s.copy(devices = devices, path = path, entries = relayEntries(devices, path)) else s.copy(devices = devices)
        }
    }

    private fun kindOf(f: SharedFile): String {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        return when {
            f.mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ts", "3gp") -> "video"
            f.mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> "image"
            f.mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus") -> "audio"
            else -> "file"
        }
    }

    private fun relayEntries(devices: List<SharedDevice>, path: String): List<Entry> {
        if (path.isEmpty()) {
            val many = devices.size > 1
            return devices.flatMap { d -> d.batches.map { b -> Entry(if (many) "${d.name} · ${b.name}" else b.name, "${d.sid}|${b.name}", true, 0, "dir") } }
        }
        val sid = path.substringBefore('|')
        val batch = path.substringAfter('|')
        val files = devices.firstOrNull { it.sid == sid }?.batches?.firstOrNull { it.name == batch }?.files.orEmpty()
        return files.map { Entry(it.name, "$sid/${it.id}", false, it.size, kindOf(it), "http://127.0.0.1:$relayPort/media/$sid/${it.id}") }
            .sortedBy { it.name.lowercase() }
    }

    override fun openShared() {
        _state.update { it.copy(relay = true, path = "", entries = relayEntries(it.devices, "")) }
    }

    // ---- LocalShare phone app (connect by address / nearby) ----

    override fun connect(phone: Phone) {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true, message = null, asking = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val a = Api(phone.url, phone.password, store.deviceId)
            try {
                a.login()
                val entries = sorted(a.list(""))
                api = a
                store.savePhone(phone)
                _state.update { it.copy(connecting = false, phone = phone, relay = false, path = "", entries = entries, phones = store.phones()) }
            } catch (e: ApiException) {
                _state.update { it.copy(connecting = false, message = e.message) }
            }
        }
    }

    override fun connectNearby(n: Nearby) {
        // Try without a password first; only ask when the phone says it needs one.
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Api(n.url, "", store.deviceId).login()
                _state.update { it.copy(connecting = false) }
                connect(Phone(n.name, n.url, ""))
            } catch (e: ApiException) {
                _state.update { if (e.code == 401) it.copy(connecting = false, asking = n) else it.copy(connecting = false, message = e.message) }
            }
        }
    }

    /** For the emulator or any network where automatic discovery cannot work: type the address shown on the phone. */
    override fun connectAddress(address: String, password: String) {
        val a = address.trim().removePrefix("http://").trimEnd('/')
        if (!a.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}(:[0-9]{1,5})?"))) {
            _state.update { it.copy(message = "Type the address like 192.168.1.5:8080") }
            return
        }
        connect(Phone("Phone $a", "http://" + if (a.contains(':')) a else "$a:8080", password))
    }

    override fun submitPassword(password: String) {
        val n = _state.value.asking ?: return
        connect(Phone(n.name, n.url, password))
    }

    override fun cancelPassword() = _state.update { it.copy(asking = null) }

    override fun forget(phone: Phone) {
        store.forget(phone.url)
        _state.update { it.copy(phones = store.phones()) }
    }

    override fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun sorted(l: List<Entry>) = l.sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() }))

    private fun load(path: String) {
        val a = api ?: return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = sorted(a.list(path))
                _state.update { it.copy(loading = false, path = path, entries = list) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, message = e.message) }
            }
        }
    }

    override fun open(e: Entry) {
        val s = _state.value
        when {
            e.dir && s.relay -> _state.update { it.copy(path = e.path, entries = relayEntries(it.devices, e.path)) }
            e.dir -> load(e.path)
            e.kind == "video" || e.kind == "audio" -> {
                val same = s.entries.filter { !it.dir && (it.kind == "video" || it.kind == "audio") }
                _state.update { it.copy(video = Media(same, same.indexOf(e).coerceAtLeast(0))) }
            }
            e.kind == "image" -> {
                val same = s.entries.filter { !it.dir && it.kind == "image" }
                _state.update { it.copy(photos = Media(same, same.indexOf(e).coerceAtLeast(0))) }
            }
            else -> _state.update { it.copy(message = "The TV plays videos, music and photos. “${e.name}” can't be shown here.") }
        }
    }

    /** @return true when the Back press was used up inside the app. */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.video != null || s.photos != null -> { _state.update { it.copy(video = null, photos = null) }; true }
            s.asking != null -> { cancelPassword(); true }
            s.relay && s.path.isNotEmpty() -> { _state.update { it.copy(path = "", entries = relayEntries(it.devices, "")) }; true }
            s.relay -> { _state.update { it.copy(relay = false, entries = emptyList(), message = null) }; true }
            s.phone != null && s.path.isNotEmpty() -> { load(s.path.substringBeforeLast('/', "")); true }
            s.phone != null -> {
                api = null
                _state.update { it.copy(phone = null, entries = emptyList(), path = "", message = null) }
                true
            }
            else -> false
        }
    }

    override fun onCleared() {
        server?.stop()
        discovery.stop()
    }
}
