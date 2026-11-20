package com.batterylab.rf

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.PriorityQueue
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A scheduler whose clock moves only when the test calls [advance]. Synthetic times only. */
class FakeScheduler(var now: Long = 5_000_000_000_000L, var wall: Long = 1_700_000_000_000L) : Scheduler {
    private class Task(val due: Long, val order: Long, val run: () -> Unit) { var cancelled = false }
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.due }, { it.order }))
    private var order = 0L
    /** Each session shuts its scheduler down at stop; the fake is reused, so it only counts them. */
    var shutdowns = 0
        private set

    override fun bootNs() = now
    override fun wallMs() = wall

    override fun schedule(delayNs: Long, task: () -> Unit): Cancellable {
        val t = Task(now + delayNs.coerceAtLeast(0), order++, task)
        queue.add(t)
        return Cancellable { t.cancelled = true }
    }

    override fun shutdown() { shutdowns++; queue.clear() }

    fun pending() = queue.count { !it.cancelled }

    fun advance(ns: Long) {
        val target = now + ns
        while (true) {
            val t = queue.peek() ?: break
            if (t.due > target) break
            queue.poll()
            if (t.cancelled) continue
            moveTo(t.due)
            t.run()
        }
        moveTo(target)
    }

    fun advanceSeconds(s: Double) = advance((s * 1e9).toLong())

    private fun moveTo(t: Long) {
        wall += (t - now) / 1_000_000
        now = t
    }
}

const val SEC = 1_000_000_000L

fun newRoot(): File = kotlin.io.path.createTempDirectory("rftest").toFile().also { it.deleteOnExit() }

/** A controller over a fresh folder whose sessions all use [sch]. */
fun controller(root: File = newRoot(), sch: FakeScheduler = FakeScheduler(), sizeLimit: Long = SessionController.SIZE_LIMIT_BYTES,
               hooks: SessionHooks? = null): Pair<SessionController, FakeScheduler> =
    SessionController(SessionStore(root), { sch }, { mapOf(Keys.APP_VERSION to "test") }, hooks, sizeLimit) to sch

/** Records a simulated session of [seconds] and returns its folder. */
fun recordSimulated(c: SessionController, sch: FakeScheduler, seconds: Int = 20, seed: Long = 7,
                    markers: List<Pair<Double, String>> = emptyList()): File {
    val r = c.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, seconds, mapOf("seed" to seed.toString()),
        { SimulatedStream.pair(seed, it) })) as StartResult.Started
    var t = 0.0
    for ((at, label) in markers.sortedBy { it.first }) { sch.advanceSeconds(at - t); t = at; c.marker(label) }
    sch.advanceSeconds(seconds - t + 0.5)
    return c.store.dir(r.id)
}

/** A binary kind this version does not know: chunks of bytes, including line feeds and zeros. */
class MockBinaryStream(private val sch: Scheduler, private val chunks: Int = 5) : Stream {
    override val id = "radar_points"
    override val kind = "mock_radar"
    override val unit = "bytes"
    override val nominalRateHz = 1.0
    override val extraColumns = listOf(Columns.BIN_OFFSET, Columns.BIN_LENGTH)
    private var task: Cancellable? = null
    private var n = 0
    val sent = ByteArrayOutputStream()

    fun chunk(i: Int) = ByteArray(10 + i * 7) { j -> ((i * 37 + j * 11) % 256).toByte() }.also { it[0] = 0x0A; it[1] = 0 }

    override fun start(sink: StreamSink) {
        fun next() {
            task = sch.schedule(SEC) {
                val c = chunk(n++)
                sent.write(c)
                sink.binary(sch.bootNs(), sch.wallMs(), c)
                if (n < chunks) next()
            }
        }
        next()
    }

    override fun stop() { task?.cancel() }
}

/** Writes zip local headers by hand, so tests can build archives a normal writer refuses to. */
class RawZip {
    private val out = ByteArrayOutputStream()

    private fun u16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
    private fun u32(v: Long) { for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xff).toInt()) }

    fun entry(name: String, data: ByteArray, deflate: Boolean = false, declaredSize: Long? = null): RawZip {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val body = if (deflate) {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            d.setInput(data); d.finish()
            val buf = ByteArray(data.size + 1024); val bo = ByteArrayOutputStream()
            while (!d.finished()) { val n = d.deflate(buf); bo.write(buf, 0, n) }
            d.end(); bo.toByteArray()
        } else data
        u32(0x04034b50); u16(20); u16(0); u16(if (deflate) 8 else 0); u16(0); u16(0x21)
        u32(crc); u32(body.size.toLong()); u32(declaredSize ?: data.size.toLong())
        u16(nameBytes.size); u16(0)
        out.write(nameBytes); out.write(body)
        return this
    }

    fun bytes(): ByteArray = out.toByteArray()
}

fun File.filesByName(): Map<String, ByteArray> =
    listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }

fun allFiles(root: File): Set<String> = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSet()
