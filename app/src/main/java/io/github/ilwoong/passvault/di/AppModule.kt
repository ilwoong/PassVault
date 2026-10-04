package io.github.ilwoong.passvault.di

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.ilwoong.passvault.data.db.VaultDatabaseHolder
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.security.AesGcmKeyWrapper
import io.github.ilwoong.passvault.security.Argon2KeyDeriver
import io.github.ilwoong.passvault.security.BiometricKeyStore
import io.github.ilwoong.passvault.security.Clocks
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.VaultKeyManager
import io.github.ilwoong.passvault.security.VaultMetaStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun clocks(@ApplicationContext context: Context): Clocks = AndroidClocks(context)

    /** noBackupFilesDir: 어떤 백업 경로에도 실리지 않는 디렉터리 (SEC-03 이중 방어). */
    @Provides
    @Singleton
    fun vaultMetaStore(@ApplicationContext context: Context) =
        VaultMetaStore(File(context.noBackupFilesDir, "vault_meta"))

    @Provides
    @Singleton
    fun vaultKeyManager(store: VaultMetaStore, clocks: Clocks) =
        VaultKeyManager(store, Argon2KeyDeriver(), AesGcmKeyWrapper(), clocks)

    @Provides
    @Singleton
    fun biometricKeyStore(@ApplicationContext context: Context) = BiometricKeyStore(context)

    @Provides
    @Singleton
    fun vaultDatabaseHolder(@ApplicationContext context: Context) = VaultDatabaseHolder(context)

    /** 해제 동안에만 주입할 수 있다. 금고 분기의 ViewModel 만 쓴다 (UX-00). */
    @Provides
    fun entryRepository(holder: VaultDatabaseHolder) = EntryRepository(holder.requireDatabase().dao())

    @Provides
    @Singleton
    fun sessionManager(keyManager: VaultKeyManager, holder: VaultDatabaseHolder) =
        SessionManager(keyManager, holder)
}

private class AndroidClocks(private val context: Context) : Clocks {
    override fun wallMs() = System.currentTimeMillis()
    override fun elapsedMs() = SystemClock.elapsedRealtime()
    override fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
}
