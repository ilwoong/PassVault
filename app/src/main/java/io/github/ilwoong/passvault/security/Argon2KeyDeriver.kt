package io.github.ilwoong.passvault.security

import com.lambdapioneer.argon2kt.Argon2Exception
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode

/** MK, VK 길이. */
const val KEY_BYTES = 32

/** Argon2id salt 길이 (CRY-02). */
const val SALT_BYTES = 16

data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        const val DEFAULT_MEMORY_KIB = 64 * 1024
        const val MIN_MEMORY_KIB = 32 * 1024
        const val DEFAULT_ITERATIONS = 3
        const val MAX_ITERATIONS = 8
        const val PARALLELISM = 2
    }
}

/**
 * CRY-02: Argon2id 로 마스터 비밀번호에서 MK 를 유도한다.
 *
 * 한 번 호출에 수백 ms ~ 1.5초가 걸린다. 메인 스레드에서 부르지 않는다 (ARC-05).
 */
class Argon2KeyDeriver(private val argon2: Argon2Kt = Argon2Kt()) {

    /** 반환값(MK)은 호출자가 지운다. */
    fun derive(password: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password,
            salt = salt,
            tCostInIterations = params.iterations,
            mCostInKibibyte = params.memoryKiB,
            parallelism = params.parallelism,
            hashLengthInBytes = KEY_BYTES,
        )
        try {
            return result.rawHashAsByteArray()
        } finally {
            // CRY-16: 결과 객체가 MK 를 rawHash 와 encodedOutput(PHC 문자열) 두 곳에 들고 있다.
            // 라이브러리의 wipeDirectBuffer 는 Kotlin internal 이라 직접 지운다.
            result.rawHash.zeroize()
            result.encodedOutput.zeroize()
        }
    }

    /** CRY-09: 금고 생성 시 1회. 이후 해제는 저장된 값만 쓴다. */
    fun calibrate(): KdfParams {
        val password = ByteArray(SALT_BYTES)
        val salt = ByteArray(SALT_BYTES)
        return calibrateKdf { params ->
            val start = System.nanoTime()
            derive(password, salt, params).zeroize()
            (System.nanoTime() - start) / 1_000_000
        }
    }
}

/**
 * CRY-09 결정 순서. 측정 함수를 받아 기기와 무관하게 단위 테스트할 수 있게 한다.
 *
 * [measureMs] 는 주어진 파라미터로 Argon2id 를 1회 실행하고 걸린 ms 를 돌려준다.
 * 메모리 할당 실패는 예외로 알린다.
 */
internal fun calibrateKdf(measureMs: (KdfParams) -> Long): KdfParams {
    var memoryKiB = KdfParams.DEFAULT_MEMORY_KIB
    var elapsed: Long

    // ①② m 을 먼저 맞춘다. 메모리-하드성이 Argon2id 의 핵심이므로 m 을 우선 보존한다.
    while (true) {
        elapsed = try {
            measureMs(KdfParams(memoryKiB, KdfParams.DEFAULT_ITERATIONS, KdfParams.PARALLELISM))
        } catch (e: Throwable) {
            if (!e.isAllocationFailure() || memoryKiB <= KdfParams.MIN_MEMORY_KIB) throw e
            memoryKiB /= 2
            continue
        }
        if (elapsed > SLOW_MS && memoryKiB > KdfParams.MIN_MEMORY_KIB) {
            memoryKiB /= 2
            continue
        }
        break
    }

    // ③ t 를 올린다. 한 단계는 최대 4/3 배라 500ms 미만에서 출발하면 1200ms 를 넘지 않는다.
    var iterations = KdfParams.DEFAULT_ITERATIONS
    while (elapsed < FAST_MS && iterations < KdfParams.MAX_ITERATIONS) {
        iterations++
        elapsed = measureMs(KdfParams(memoryKiB, iterations, KdfParams.PARALLELISM))
    }
    return KdfParams(memoryKiB, iterations, KdfParams.PARALLELISM)
}

private const val FAST_MS = 500L
private const val SLOW_MS = 1200L

/** 네이티브 malloc 실패는 Argon2Exception, 출력 버퍼 allocateDirect 실패는 OutOfMemoryError 로 온다. */
private fun Throwable.isAllocationFailure() = this is Argon2Exception || this is OutOfMemoryError
