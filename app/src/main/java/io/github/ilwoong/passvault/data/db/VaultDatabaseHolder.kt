package io.github.ilwoong.passvault.data.db

import android.content.Context
import io.github.ilwoong.passvault.security.SessionResource

/**
 * 해제 동안만 DB 를 열어 둔다 (ARC-03). SessionManager 가 LOCK-04 순서대로 호출한다.
 */
class VaultDatabaseHolder(
    private val context: Context,
    private val name: String = VaultDatabase.FILE_NAME,
) : SessionResource {

    @Volatile
    private var db: VaultDatabase? = null

    /** 잠겨 있으면 던진다. 잠기면 이 화면들은 이미 컴포지션에서 빠져 있다 (UX-00). */
    fun requireDatabase(): VaultDatabase = checkNotNull(db) { "금고가 잠겨 있다" }

    /**
     * Room 은 지연 오픈하므로 여기서 강제로 연다. 키가 맞지 않거나 DB 가 손상됐으면
     * 해제 시점에 드러나게 하기 위해서다. 실패해도 아무것도 지우지 않는다 (ARC-06).
     */
    override fun onUnlocked(vaultKey: ByteArray) {
        check(db == null) { "이미 열려 있다" }
        val opened = VaultDatabase.open(context, vaultKey, name)
        try {
            opened.openHelper.writableDatabase
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
        db = opened
    }

    override fun onLocked() {
        db?.close()
        db = null
    }

    /** BK-05: 다시 만들기 직전에 기존 DB 파일(WAL 포함)을 지운다. */
    override fun discard() {
        check(db == null) { "열린 DB 는 지우지 않는다" }
        context.deleteDatabase(name)
    }
}
