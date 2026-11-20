package com.batterylab.rf

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The install key of R17: 32 random bytes in the app's private storage, outside every session
 * folder and every backup. A device's key is the first 16 hex digits of HMAC-SHA256 over its kind
 * and address. The key file is never copied into a session or an export. Delete all destroys it,
 * so keys change and pins made before it no longer match anything.
 */
class InstallKey(private val file: File) {
    private val lock = Any()
    private var bytes: ByteArray? = null

    private fun load(): ByteArray = synchronized(lock) {
        bytes?.let { return it }
        val existing = if (file.isFile) try { file.readBytes() } catch (_: Exception) { null } else null
        val b = if (existing != null && existing.size == 32) existing else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { write(it) }
        }
        bytes = b
        b
    }

    private fun write(b: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(b)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** A MAC keyed with the current install key. Each caller gets its own (Mac is not thread-safe). */
    fun mac(): Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(load(), "HmacSHA256")) }

    fun key(kind: String, address: String): String = keyWith(mac(), kind, address)

    /** Deletes the key; the next use creates a new one. */
    fun destroy() = synchronized(lock) {
        bytes?.fill(0)
        bytes = null
        file.delete()
        Unit
    }

    /** True when [text] contains this install's key, for the privacy tests. */
    fun hexForTests(): String = load().joinToString("") { "%02x".format(it) }

    companion object {
        fun keyWith(mac: Mac, kind: String, address: String): String {
            val d = mac.doFinal("$kind|${address.uppercase()}".toByteArray(Charsets.UTF_8))
            return d.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Devices pinned in R31: key and alias only, in files/rf/pins.csv. A pin gives a Bluetooth device
 * an alias for scanning. It never connects to or pairs with anything. The Ring is not a pin: it
 * stays in Battery Lab's existing setting under the reserved alias ring.
 */
class PinStore(private val file: File) {
    private val lock = Any()
    private var cache: LinkedHashMap<String, String>? = null

    companion object {
        const val RING = "ring"
        private val aliasRule = Regex("[a-z0-9_]{1,24}")
        private val keyRule = Regex("[0-9a-f]{16}")
        fun validAlias(alias: String) = aliasRule.matches(alias) && alias != RING
    }

    private fun load(): LinkedHashMap<String, String> = synchronized(lock) {
        cache?.let { return it }
        val m = LinkedHashMap<String, String>()
        if (file.isFile) try {
            val t = Csv.parse(file.readText(Charsets.UTF_8))
            for (r in t.rows) {
                val k = t.cell(r, "key") ?: continue
                val a = t.cell(r, "alias") ?: continue
                if (keyRule.matches(k) && validAlias(a)) m[k] = a
            }
        } catch (_: Exception) { }
        cache = m
        m
    }

    private fun save(m: Map<String, String>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder(Csv.line(listOf("key", "alias")))
        for ((k, a) in m) sb.append(Csv.line(listOf(k, a)))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun all(): Map<String, String> = synchronized(lock) { LinkedHashMap(load()) }

    fun aliasOf(key: String): String? = synchronized(lock) { load()[key] }

    /** Returns false for a bad key or alias, or an alias another device already has. */
    fun pin(key: String, alias: String): Boolean = synchronized(lock) {
        if (!keyRule.matches(key) || !validAlias(alias)) return false
        val m = load()
        if (m.any { (k, a) -> a == alias && k != key }) return false
        m[key] = alias
        save(m)
        true
    }

    fun unpin(key: String) = synchronized(lock) {
        val m = load()
        if (m.remove(key) != null) save(m)
    }

    /** Delete all: every pin goes with the install key. */
    fun clear() = synchronized(lock) {
        cache = LinkedHashMap()
        file.delete()
        Unit
    }
}

/** Device and network names as they may be stored in named mode. */
object Names {
    const val MAX_BYTES = 96
    /** Six hex pairs with or without separators: anything that looks like a hardware address. */
    private val address = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{2}([:\-.]?)[0-9a-f]{2}(\1[0-9a-f]{2}){4}(?![0-9a-f])""")

    /** Control characters removed, address-like text replaced, at most [MAX_BYTES] UTF-8 bytes. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        var s = raw.filter { !it.isISOControl() }.replace(address, "[address]").trim()
        while (s.toByteArray(Charsets.UTF_8).size > MAX_BYTES) s = s.dropLast(1)
        // A high surrogate left alone at the end would not encode.
        if (s.isNotEmpty() && s.last().isHighSurrogate()) s = s.dropLast(1)
        return s.ifEmpty { null }
    }
}

/** One device on the list (R31). [key] is for pinning and is never shown. */
data class DeviceView(
    val number: Int, val kind: String, val key: String, val alias: String?, val name: String?,
    val band: String?, val rows: Long, val bestDbm: Int?, val lastDbm: Int?, val lastNs: Long,
)

/**
 * The devices of one "Everything nearby" session (R17, R26): numbered from 1 in first-seen order,
 * written to emitters.csv by the session. Addresses stay in memory and are discarded at [clear].
 * At most [cap] devices; results from a new device past it are refused and counted as dropped.
 * Every line stays under [LINE_MAX] bytes, so the file never passes [worstCaseBytes], which the
 * session keeps free from the start.
 */
class EmitterTable(installKey: InstallKey, val named: Boolean, val cap: Int = MAX_DEVICES) {
    companion object {
        const val MAX_DEVICES = 4_000
        const val LINE_MAX = 320
        const val BLE = "ble"
        const val WIFI = "wifi"
        val COLUMNS = listOf("emitter", "kind", "key", "alias", "connectable", "vendor_id", "band", "name",
            "first_boot_ns", "first_wall_ms", "last_boot_ns", "last_wall_ms", "rows", "best_dbm", "last_dbm")
        val NAME_COLUMN = COLUMNS.indexOf("name")
        fun band(freqMhz: Int): String? = when (freqMhz) {
            in 2_400..2_500 -> "2.4"
            in 4_900..5_899 -> "5"
            in 5_925..7_125 -> "6"
            else -> null
        }
    }

    private class Device(val number: Int, val kind: String, val key: String, val firstNs: Long, val firstWall: Long) {
        var alias: String? = null
        var connectable: Boolean? = null
        var vendorId: Int? = null
        var band: String? = null
        var name: String? = null
        var lastNs = firstNs
        var lastWall = firstWall
        var rows = 0L
        var best: Int? = null
        var last: Int? = null
    }

    private val lock = Any()
    private var mac: Mac? = installKey.mac()
    private val byAddress = HashMap<String, Device>()
    private val ordered = ArrayList<Device>()

    val worstCaseBytes: Long get() = (Csv.line(COLUMNS).length + cap.toLong() * LINE_MAX)

    /**
     * Records one result and returns its device number, or null when the table is full or cleared.
     * [name] is kept only in named mode, after [Names.clean].
     */
    fun see(kind: String, address: String, rssi: Int?, tBootNs: Long, tWallMs: Long, connectable: Boolean? = null,
            vendorId: Int? = null, band: String? = null, name: String? = null, alias: String? = null): Int? = synchronized(lock) {
        val m = mac ?: return null
        val id = "$kind|${address.uppercase()}"
        val d = byAddress[id] ?: run {
            if (ordered.size >= cap) return null
            Device(ordered.size + 1, kind, InstallKey.keyWith(m, kind, address), tBootNs, tWallMs).also {
                byAddress[id] = it; ordered += it
            }
        }
        d.rows++
        if (tBootNs >= d.lastNs) { d.lastNs = tBootNs; d.lastWall = tWallMs; if (rssi != null) d.last = rssi }
        if (rssi != null && (d.best == null || rssi > d.best!!)) d.best = rssi
        if (alias != null) d.alias = alias
        if (connectable != null) d.connectable = connectable
        if (vendorId != null) d.vendorId = vendorId
        if (band != null) d.band = band
        if (named) Names.clean(name)?.let { d.name = it }
        d.number
    }

    val size: Int get() = synchronized(lock) { ordered.size }

    /** Lock held. One device's line, never over [LINE_MAX] bytes. */
    private fun line(d: Device): String {
        fun cells(name: String) = listOf(d.number.toString(), d.kind, d.key, d.alias ?: "",
            d.connectable?.toString() ?: "", Num.opt(d.vendorId), d.band ?: "", name,
            d.firstNs.toString(), d.firstWall.toString(), d.lastNs.toString(), d.lastWall.toString(),
            d.rows.toString(), Num.opt(d.best), Num.opt(d.last))
        val line = Csv.line(cells(FreeText.encode(d.name ?: "")), setOf(NAME_COLUMN))
        return if (line.toByteArray(Charsets.UTF_8).size > LINE_MAX) Csv.line(cells(""), setOf(NAME_COLUMN)) else line
    }

    fun csv(): String = snapshot().first

    /** The whole file and the number of devices in it. */
    fun snapshot(): Pair<String, Int> = synchronized(lock) {
        val sb = StringBuilder(Csv.line(COLUMNS))
        for (d in ordered) sb.append(line(d))
        sb.toString() to ordered.size
    }

    /**
     * Lines for the devices numbered above [after], to append to emitters.csv before any row that
     * points at them is written, and the highest number included. Each device is appended at most
     * once between full rewrites, so the file stays within [worstCaseBytes].
     */
    fun linesAfter(after: Int): Pair<String, Int> = synchronized(lock) {
        val sb = StringBuilder()
        for (i in after until ordered.size) sb.append(line(ordered[i]))
        sb.toString() to ordered.size
    }

    fun devices(): List<DeviceView> = synchronized(lock) {
        ordered.map { DeviceView(it.number, it.kind, it.key, it.alias, it.name, it.band, it.rows, it.best, it.last, it.lastNs) }
    }

    /** Session end: addresses and the key leave memory. */
    fun clear() = synchronized(lock) {
        byAddress.clear()
        mac = null
    }
}

/** Reads emitters.csv for the device list after a session (R31). */
object EmitterFile {
    /**
     * One row per device number, the last one written. Between full rewrites a session appends
     * new devices, and a session interrupted then can hold a device twice.
     */
    fun latest(t: CsvTable): CsvTable {
        val byNumber = LinkedHashMap<String, List<String>>()
        for (r in t.rows) { val n = t.cell(r, "emitter") ?: continue; byNumber.remove(n); byNumber[n] = r }
        return CsvTable(t.header, byNumber.values.sortedBy { t.cell(it, "emitter")?.toIntOrNull() ?: Int.MAX_VALUE })
    }

    fun read(dir: File): List<DeviceView> {
        val f = File(dir, FileNames.EMITTERS)
        if (!f.isFile) return emptyList()
        val t = try { latest(Csv.parse(f.readText(Charsets.UTF_8))) } catch (_: Exception) { return emptyList() }
        return t.rows.mapNotNull { r ->
            val n = t.cell(r, "emitter")?.toIntOrNull() ?: return@mapNotNull null
            DeviceView(n, t.cell(r, "kind") ?: "", t.cell(r, "key") ?: "",
                t.cell(r, "alias")?.ifEmpty { null }, t.cell(r, "name")?.let { FreeText.decode(it) }?.ifEmpty { null },
                t.cell(r, "band")?.ifEmpty { null }, t.cell(r, "rows")?.toLongOrNull() ?: 0,
                t.cell(r, "best_dbm")?.toIntOrNull(), t.cell(r, "last_dbm")?.toIntOrNull(),
                t.cell(r, "last_boot_ns")?.toLongOrNull() ?: 0)
        }
    }
}

/** Jeff's decision of Nov 19: a new "Everything nearby" session stores names unless switched off. */
object NearbyDefaults {
    const val STORE_NAMES = true
}

/**
 * The alias lookup a Bluetooth scan uses (R31): the Ring by its address, a pinned device by its
 * key. Pins are read once at session start. Each address is keyed once and remembered for the
 * session only, so scan results do not recompute the HMAC.
 */
class PinMatcher(installKey: InstallKey, private val pins: Map<String, String>, private val ring: String?) : (String) -> String? {
    private val mac = if (pins.isEmpty()) null else installKey.mac()
    private val known = HashMap<String, String>()
    private val none = HashSet<String>()

    override fun invoke(address: String): String? {
        if (ring != null && address.equals(ring, ignoreCase = true)) return PinStore.RING
        val m = mac ?: return null
        val a = address.uppercase()
        synchronized(this) {
            known[a]?.let { return it }
            if (a in none) return null
            if (known.size + none.size >= EmitterTable.MAX_DEVICES * 4) { known.clear(); none.clear() }
            val alias = pins[InstallKey.keyWith(m, EmitterTable.BLE, a)]
            if (alias == null) none += a else known[a] = alias
            return alias
        }
    }
}
