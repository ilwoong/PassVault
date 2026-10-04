package io.github.ilwoong.passvault.di

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.ilwoong.passvault.backup.BackupCodec
import io.github.ilwoong.passvault.data.db.VaultDatabaseHolder
import io.github.ilwoong.passvault.data.settings.AppSettings
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.security.AesGcmKeyWrapper
import io.github.ilwoong.passvault.security.AutoLock
import io.github.ilwoong.passvault.security.Argon2KeyDeriver
import io.github.ilwoong.passvault.security.BiometricKeyStore
import io.github.ilwoong.passvault.security.Clocks
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.VaultKeyManager
import io.github.ilwoong.passvault.security.VaultMetaStore
import io.github.ilwoong.passvault.ui.common.AndroidClipboardAccess
import io.github.ilwoong.passvault.ui.common.SecureClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    fun argon2KeyDeriver() = Argon2KeyDeriver()

    @Provides
    @Singleton
    fun vaultKeyManager(store: VaultMetaStore, deriver: Argon2KeyDeriver, clocks: Clocks) =
        VaultKeyManager(store, deriver, AesGcmKeyWrapper(), clocks)

    /** BK-01 */
    @Provides
    @Singleton
    fun backupCodec(deriver: Argon2KeyDeriver) = BackupCodec(deriver::derive)

    @Provides
    @Singleton
    fun biometricKeyStore(@ApplicationContext context: Context) = BiometricKeyStore(context)

    @Provides
    @Singleton
    fun vaultDatabaseHolder(@ApplicationContext context: Context) = VaultDatabaseHolder(context)

    @Provides
    @Singleton
    fun appSettings(@ApplicationContext context: Context) = AppSettings(context)

    /** 타이머는 프로세스 수명 동안 돈다 (LOCK-07). */
    @Provides
    @Singleton
    fun secureClipboard(@ApplicationContext context: Context, settings: AppSettings) = SecureClipboard(
        AndroidClipboardAccess(context),
        clearAfterSeconds = { settings.state.value.clipboardClearSeconds },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )

    /** LOCK-03 */
    @Provides
    @Singleton
    fun autoLock(clocks: Clocks, settings: AppSettings, session: SessionManager, clipboard: SecureClipboard) = AutoLock(
        clocks = clocks,
        timeoutMs = { settings.state.value.autoLockSeconds * 1_000L },
        lockOnBackground = { settings.state.value.lockOnBackground },
        lock = session::lock,
        clearClipboard = clipboard::clearIfOurs,
    )

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
