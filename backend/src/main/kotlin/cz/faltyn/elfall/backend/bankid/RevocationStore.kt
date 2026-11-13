package cz.faltyn.elfall.backend.bankid

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Druhy záznamů v úložišti revokací / spotřebovaných DPoP jti. */
enum class RevocationKind { CREDENTIAL, DEVICE, JTI }

/**
 * Perzistentní úložiště revokací Zero Trust. Odvolaný passkey credential, klíč zařízení (jkt)
 * a spotřebované DPoP `jti` musí přežít restart (jinak by restart umožnil replay nebo
 * znovuoživení odvolaného klíče). Schéma pro SQL backend: `db/migration/V1__zt_revocations.sql`.
 */
interface RevocationStore {
    fun add(kind: RevocationKind, id: String, ts: Long)
    fun contains(kind: RevocationKind, id: String): Boolean
    /** Atomicky zaznamená jti; false = už bylo použito (replay). */
    fun addJtiIfAbsent(jti: String, ts: Long): Boolean
    /** Odstraní jti starší než [olderThan] (revokace credential/device se nemažou nikdy). */
    fun purgeJti(olderThan: Long)
}

/** Jen pro testy / vývoj – nepřežije restart. */
class InMemoryRevocationStore : RevocationStore {
    private val sets = RevocationKind.entries.associateWith { ConcurrentHashMap<String, Long>() }
    override fun add(kind: RevocationKind, id: String, ts: Long) { sets.getValue(kind)[id] = ts }
    override fun contains(kind: RevocationKind, id: String) = sets.getValue(kind).containsKey(id)
    override fun addJtiIfAbsent(jti: String, ts: Long) = sets.getValue(RevocationKind.JTI).putIfAbsent(jti, ts) == null
    override fun purgeJti(olderThan: Long) { sets.getValue(RevocationKind.JTI).entries.removeIf { it.value < olderThan } }
}

/**
 * Souborové úložiště: append-only log (jeden záznam na řádek `v1\tKIND\tts\tid`), každý zápis fsync.
 * Při startu se log přehraje; poškozený/neznámý řádek => výjimka (fail-closed, server nenastartuje).
 * Kompaktace přepíše soubor atomicky (tmp + rename) bez prošlých jti.
 */
class FileRevocationStore(private val file: File) : RevocationStore {
    private val mem = InMemoryRevocationStore()
    private val idRe = Regex("^[A-Za-z0-9._~+/=-]{1,512}$")

    init {
        file.parentFile?.mkdirs()
        if (file.exists()) file.readLines().forEachIndexed { n, l ->
            if (l.isBlank()) return@forEachIndexed
            val p = l.split('\t')
            require(p.size == 4 && p[0] == "v1") { "poškozený revokační log, řádek ${n + 1}" }
            val kind = RevocationKind.valueOf(p[1]); val ts = p[2].toLong()
            require(idRe.matches(p[3])) { "neplatné id, řádek ${n + 1}" }
            if (kind == RevocationKind.JTI) mem.addJtiIfAbsent(p[3], ts) else mem.add(kind, p[3], ts)
        }
    }

    @Synchronized private fun append(kind: RevocationKind, id: String, ts: Long) {
        require(idRe.matches(id)) { "neplatné id" }
        FileOutputStream(file, true).use { o -> o.write("v1\t${kind.name}\t$ts\t$id\n".toByteArray()); o.fd.sync() }
    }

    @Synchronized override fun add(kind: RevocationKind, id: String, ts: Long) {
        if (!mem.contains(kind, id)) { append(kind, id, ts); mem.add(kind, id, ts) }
    }
    override fun contains(kind: RevocationKind, id: String) = mem.contains(kind, id)
    @Synchronized override fun addJtiIfAbsent(jti: String, ts: Long): Boolean {
        if (mem.contains(RevocationKind.JTI, jti)) return false
        append(RevocationKind.JTI, jti, ts); return mem.addJtiIfAbsent(jti, ts)
    }
    @Synchronized override fun purgeJti(olderThan: Long) { mem.purgeJti(olderThan) }

    /** Atomicky přepíše log jen s živými záznamy. */
    @Synchronized fun compact(records: () -> Sequence<Triple<RevocationKind, String, Long>> = { emptySequence() }) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { o ->
            file.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.forEach { l ->
                val p = l.split('\t')
                val k = RevocationKind.valueOf(p[1])
                if (k != RevocationKind.JTI || mem.contains(k, p[3])) o.write("$l\n".toByteArray())
            }
            records().forEach { (k, id, ts) -> o.write("v1\t${k.name}\t$ts\t$id\n".toByteArray()) }
            o.fd.sync()
        }
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
