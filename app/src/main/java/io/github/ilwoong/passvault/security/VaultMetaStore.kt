package io.github.ilwoong.passvault.security

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** DM-01. 래핑된 키와 공개 파라미터만 담으므로 평문 저장해도 안전하다. */
class VaultMeta(
    val kdfSalt: ByteArray,
    val kdfParams: KdfParams,
    val wrappedVkByMk: ByteArray,
    val wrappedVkByBio: ByteArray?,
    val failedAttempts: Int,
    val lockoutUntilEpochMs: Long,
    val vaultCreatedAtEpochMs: Long,
    val lockoutBootCount: Int = 0,
    val lockoutUntilElapsedMs: Long = 0,
) {
    /** LOCK-05 실패 상태만 바꾼 사본. */
    fun withLockout(failedAttempts: Int, untilEpochMs: Long, bootCount: Int, untilElapsedMs: Long) = VaultMeta(
        kdfSalt, kdfParams, wrappedVkByMk, wrappedVkByBio,
        failedAttempts, untilEpochMs, vaultCreatedAtEpochMs, bootCount, untilElapsedMs,
    )
}

/** DM-01: Corrupt 와 Absent 를 절대 섞지 않는다. 섞으면 온보딩이 기존 금고를 덮어쓴다. */
sealed interface MetaReadResult {
    data object Absent : MetaReadResult
    data object Corrupt : MetaReadResult
    class Present(val meta: VaultMeta) : MetaReadResult
}

/** DM-01: 188 바이트 고정 레이아웃. 쓰기는 임시 파일 → fsync → 원자적 rename 만 허용한다. */
class VaultMetaStore(private val file: File) {

    private val tmp = File(file.parentFile, file.name + ".tmp")

    /** `.tmp` 는 중단된 쓰기의 흔적일 뿐이므로 읽지 않는다. */
    fun read(): MetaReadResult {
        if (!file.exists()) return MetaReadResult.Absent
        return decode(file.readBytes())?.let(MetaReadResult::Present) ?: MetaReadResult.Corrupt
    }

    /** 대상 파일을 쓰기 모드로 직접 열지 않는다. 중간에 죽어도 대상은 구 버전 또는 신 버전이다. */
    fun write(meta: VaultMeta) {
        FileOutputStream(tmp).use { out ->
            out.write(encode(meta))
            out.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    companion object {
        const val SIZE_BYTES = 188
        const val META_VERSION: Byte = 1
        private val MAGIC = "PVMETA".toByteArray(Charsets.US_ASCII)
        private const val WRAPPED_BYTES = AesGcmKeyWrapper.NONCE_BYTES + KEY_BYTES + AesGcmKeyWrapper.TAG_BYTES

        internal fun encode(meta: VaultMeta): ByteArray {
            val buf = ByteBuffer.allocate(SIZE_BYTES)
            buf.put(MAGIC)
            buf.put(META_VERSION)
            buf.put(if (meta.wrappedVkByBio != null) 1 else 0)
            buf.put(meta.kdfSalt)
            buf.putInt(meta.kdfParams.memoryKiB)
            buf.putInt(meta.kdfParams.iterations)
            buf.putInt(meta.kdfParams.parallelism)
            buf.put(meta.wrappedVkByMk)
            buf.put(meta.wrappedVkByBio ?: ByteArray(WRAPPED_BYTES))
            buf.putInt(meta.failedAttempts)
            buf.putLong(meta.lockoutUntilEpochMs)
            buf.putLong(meta.vaultCreatedAtEpochMs)
            buf.putInt(meta.lockoutBootCount)
            buf.putLong(meta.lockoutUntilElapsedMs)
            return buf.array()
        }

        /** 형식이 어긋나면 null (= Corrupt). */
        internal fun decode(bytes: ByteArray): VaultMeta? {
            if (bytes.size != SIZE_BYTES) return null
            val buf = ByteBuffer.wrap(bytes)
            if (!ByteArray(MAGIC.size).also(buf::get).contentEquals(MAGIC)) return null
            if (buf.get() != META_VERSION) return null
            val hasBioWrap = when (buf.get().toInt()) {
                0 -> false
                1 -> true
                else -> return null
            }
            val salt = ByteArray(SALT_BYTES).also(buf::get)
            val params = KdfParams(buf.int, buf.int, buf.int)
            if (!params.isInCalibrationRange()) return null
            val wrappedByMk = ByteArray(WRAPPED_BYTES).also(buf::get)
            val wrappedByBio = ByteArray(WRAPPED_BYTES).also(buf::get)
            return VaultMeta(
                kdfSalt = salt,
                kdfParams = params,
                wrappedVkByMk = wrappedByMk,
                wrappedVkByBio = wrappedByBio.takeIf { hasBioWrap },
                failedAttempts = buf.int,
                lockoutUntilEpochMs = buf.long,
                vaultCreatedAtEpochMs = buf.long,
                lockoutBootCount = buf.int,
                lockoutUntilElapsedMs = buf.long,
            )
        }

        /** v1 파일은 CRY-09 캘리브레이션만 쓰므로 그 범위 밖은 손상이다. */
        private fun KdfParams.isInCalibrationRange() =
            memoryKiB in KdfParams.MIN_MEMORY_KIB..KdfParams.DEFAULT_MEMORY_KIB &&
                iterations in 1..KdfParams.MAX_ITERATIONS &&
                parallelism == KdfParams.PARALLELISM
    }
}
