package io.github.ilwoong.passvault.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// SEC-12, CRY-16: 비밀 바이트·문자는 사용 후 즉시 0으로 채운다.

fun ByteArray.zeroize() = fill(0)

fun CharArray.zeroize() = fill('\u0000')

/** position·limit 과 무관하게 전체 용량을 덮어쓴다. direct 버퍼(네이티브 메모리)에도 쓴다. */
fun ByteBuffer.zeroize() {
    clear()
    put(ByteArray(capacity()))
    clear()
}

/** 블록이 정상 종료하든 예외를 던지든 [this] 를 지운다. */
inline fun <R> ByteArray.useThenZeroize(block: (ByteArray) -> R): R =
    try {
        block(this)
    } finally {
        zeroize()
    }

/** 블록이 정상 종료하든 예외를 던지든 [this] 를 지운다. */
inline fun <R> CharArray.useThenZeroize(block: (CharArray) -> R): R =
    try {
        block(this)
    } finally {
        zeroize()
    }

/**
 * [String] 을 거치지 않고 UTF-8 로 인코딩한다 (CRY-16). 반환값은 호출자가 지운다.
 *
 * `CharsetEncoder.encode(CharBuffer)` 는 출력이 넘치면 버퍼를 재할당하면서 이전 버퍼를
 * 지우지 않고 버린다. 최대 크기로 한 번만 할당해 재할당을 막는다.
 */
fun CharArray.toUtf8Bytes(): ByteArray {
    val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val work = ByteArray((size * encoder.maxBytesPerChar()).toInt())
    try {
        val out = ByteBuffer.wrap(work)
        encoder.encode(CharBuffer.wrap(this), out, true).let { if (it.isError) it.throwException() }
        encoder.flush(out).let { if (it.isError) it.throwException() }
        return work.copyOf(out.position())
    } finally {
        work.zeroize()
    }
}
