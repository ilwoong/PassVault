package io.github.ilwoong.passvault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.ilwoong.passvault.security.zeroize
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        EntryEntity::class,
        LoginDetailEntity::class,
        NoteDetailEntity::class,
        CardDetailEntity::class,
        IdentityDetailEntity::class,
        PasswordPolicyEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun dao(): VaultDao

    /** CRY-17: SQLCipher 가 이 배열의 참조를 DB 수명 내내 쓴다. close 직후에 지운다. */
    internal var passphrase: ByteArray? = null
        private set

    override fun close() {
        super.close()
        passphrase?.zeroize()
    }

    companion object {
        const val FILE_NAME = "vault.db"

        /**
         * CRY-05: VK 를 raw key 로 써서 SQLCipher DB 를 연다. [vaultKey] 는 호출자 소유다.
         *
         * `fallbackToDestructiveMigration` 을 쓰지 않는다 (DM-12).
         */
        fun open(context: Context, vaultKey: ByteArray, name: String = FILE_NAME): VaultDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = rawKeyPassphrase(vaultKey)
            return Room.databaseBuilder(context, VaultDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
                .also { it.passphrase = passphrase }
        }
    }
}

/**
 * CRY-05: ASCII `x'<hex>'`. SQLCipher 는 이 형식을 KDF 없이 raw key 로 쓴다.
 * VK 는 이미 32 바이트 무작위 키이므로 SQLCipher 내부 PBKDF2 가 필요 없다. String 을 거치지 않는다.
 */
internal fun rawKeyPassphrase(key: ByteArray): ByteArray {
    val out = ByteArray(3 + key.size * 2)
    out[0] = 'x'.code.toByte()
    out[1] = '\''.code.toByte()
    for (i in key.indices) {
        val b = key[i].toInt() and 0xFF
        out[2 + 2 * i] = HEX[b ushr 4]
        out[3 + 2 * i] = HEX[b and 0x0F]
    }
    out[out.size - 1] = '\''.code.toByte()
    return out
}

private val HEX = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
