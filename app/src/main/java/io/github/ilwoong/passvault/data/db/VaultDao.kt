package io.github.ilwoong.passvault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.ilwoong.passvault.data.model.EntrySummary
import io.github.ilwoong.passvault.data.model.EntryType
import kotlinx.coroutines.flow.Flow

/** DM-03 변경 주기 재계산 입력. 비밀번호 컬럼을 담지 않는다. */
data class RotationRow(
    val entryId: String,
    val passwordUpdatedAtEpochMs: Long?,
    val rotationDays: Int?,
    val hasRotationDue: Boolean,
)

/** 한 항목의 모든 행. 타입에 맞는 상세 하나만 null 이 아니다. */
class LoadedEntry(
    val entry: EntryEntity,
    val login: LoginDetailEntity?,
    val note: NoteDetailEntity?,
    val card: CardDetailEntity?,
    val identity: IdentityDetailEntity?,
    val policy: PasswordPolicyEntity?,
)

/**
 * 여러 테이블에 걸친 쓰기는 이 DAO 의 `@Transaction` 메서드로만 한다 (DM-11).
 *
 * 부모 entry 행에 `@Insert(onConflict = REPLACE)` 를 쓰지 않는다. SQLite 의 REPLACE 는
 * DELETE 후 INSERT 라서 ON DELETE CASCADE 가 발동해 상세·정책 행이 지워진다. `@Upsert` 는
 * INSERT 가 충돌하면 UPDATE 하므로 지우지 않는다.
 */
@Dao
abstract class VaultDao {

    /**
     * 목록 (UX-04). entry 테이블만 읽는다 (NFR-02).
     * [pattern] 은 이스케이프된 LIKE 패턴이고 null 이면 검색하지 않는다.
     */
    @Query(
        """
        SELECT id, type, title, subtitle, isFavorite, hasPolicyViolation, hasRotationDue FROM entry
        WHERE (:type IS NULL OR type = :type)
          AND (:pattern IS NULL OR title LIKE :pattern ESCAPE '\' OR subtitle LIKE :pattern ESCAPE '\')
        ORDER BY isFavorite DESC, title COLLATE NOCASE ASC
        """,
    )
    abstract fun observeSummaries(type: EntryType?, pattern: String?): Flow<List<EntrySummary>>

    /** 상세 화면이 저장 후 다시 읽을 신호. 저장은 항상 entry 행을 갱신한다. */
    @Query("SELECT * FROM entry WHERE id = :id")
    abstract fun observeEntryRow(id: String): Flow<EntryEntity?>

    /** DM-03: 비밀번호를 읽지 않고 변경 시각과 주기만 읽는다. */
    @Query(
        """
        SELECT e.id AS entryId, l.passwordUpdatedAtEpochMs, p.rotationDays, e.hasRotationDue
        FROM entry e
        JOIN login_detail l ON l.entryId = e.id
        LEFT JOIN password_policy p ON p.entryId = e.id
        """,
    )
    abstract suspend fun rotationRows(): List<RotationRow>

    @Query("UPDATE entry SET hasRotationDue = :due WHERE id = :id")
    abstract suspend fun setRotationDue(id: String, due: Boolean)

    /** 한 행만 UPDATE 한다. REPLACE 는 CASCADE 로 상세를 지운다. */
    @Query("UPDATE entry SET isFavorite = :favorite WHERE id = :id")
    abstract suspend fun setFavorite(id: String, favorite: Boolean): Int

    @Transaction
    open suspend fun load(id: String): LoadedEntry? {
        val e = entry(id) ?: return null
        return LoadedEntry(e, login(id), note(id), card(id), identity(id), policy(id))
    }

    @Transaction
    open suspend fun saveLogin(entry: EntryEntity, detail: LoginDetailEntity, policy: PasswordPolicyEntity?) {
        upsertEntry(entry)
        upsertLogin(detail)
        if (policy != null) upsertPolicy(policy) else deletePolicy(entry.id)
    }

    @Transaction
    open suspend fun saveNote(entry: EntryEntity, detail: NoteDetailEntity) {
        upsertEntry(entry)
        upsertNote(detail)
    }

    @Transaction
    open suspend fun saveCard(entry: EntryEntity, detail: CardDetailEntity) {
        upsertEntry(entry)
        upsertCard(detail)
    }

    @Transaction
    open suspend fun saveIdentity(entry: EntryEntity, detail: IdentityDetailEntity) {
        upsertEntry(entry)
        upsertIdentity(detail)
    }

    /** BK-03 내보내기 순서. */
    @Query("SELECT id FROM entry ORDER BY createdAtEpochMs ASC, id ASC")
    abstract suspend fun allIds(): List<String>

    /**
     * BK-04 전량 교체. 한 트랜잭션이라 중간에 실패하면 기존 데이터가 그대로 남는다.
     * 상세·정책은 entry 삭제의 CASCADE 로 함께 지워진다.
     */
    @Transaction
    open suspend fun replaceAll(
        entries: List<EntryEntity>,
        logins: List<LoginDetailEntity>,
        notes: List<NoteDetailEntity>,
        cards: List<CardDetailEntity>,
        identities: List<IdentityDetailEntity>,
        policies: List<PasswordPolicyEntity>,
    ) {
        deleteAllEntries()
        insertEntries(entries)
        insertLogins(logins)
        insertNotes(notes)
        insertCards(cards)
        insertIdentities(identities)
        insertPolicies(policies)
    }

    @Query("DELETE FROM entry")
    protected abstract suspend fun deleteAllEntries()

    @Insert
    protected abstract suspend fun insertEntries(rows: List<EntryEntity>)

    @Insert
    protected abstract suspend fun insertLogins(rows: List<LoginDetailEntity>)

    @Insert
    protected abstract suspend fun insertNotes(rows: List<NoteDetailEntity>)

    @Insert
    protected abstract suspend fun insertCards(rows: List<CardDetailEntity>)

    @Insert
    protected abstract suspend fun insertIdentities(rows: List<IdentityDetailEntity>)

    @Insert
    protected abstract suspend fun insertPolicies(rows: List<PasswordPolicyEntity>)

    /** 상세·정책은 ON DELETE CASCADE 로 함께 지워진다 (DM-11). */
    @Query("DELETE FROM entry WHERE id = :id")
    abstract suspend fun deleteEntry(id: String): Int

    @Query("SELECT * FROM entry WHERE id = :id")
    abstract suspend fun entry(id: String): EntryEntity?

    @Query("SELECT * FROM login_detail WHERE entryId = :id")
    protected abstract suspend fun login(id: String): LoginDetailEntity?

    @Query("SELECT * FROM note_detail WHERE entryId = :id")
    protected abstract suspend fun note(id: String): NoteDetailEntity?

    @Query("SELECT * FROM card_detail WHERE entryId = :id")
    protected abstract suspend fun card(id: String): CardDetailEntity?

    @Query("SELECT * FROM identity_detail WHERE entryId = :id")
    protected abstract suspend fun identity(id: String): IdentityDetailEntity?

    @Query("SELECT * FROM password_policy WHERE entryId = :id")
    protected abstract suspend fun policy(id: String): PasswordPolicyEntity?

    @Upsert
    protected abstract suspend fun upsertEntry(entry: EntryEntity)

    @Upsert
    protected abstract suspend fun upsertLogin(detail: LoginDetailEntity)

    @Upsert
    protected abstract suspend fun upsertNote(detail: NoteDetailEntity)

    @Upsert
    protected abstract suspend fun upsertCard(detail: CardDetailEntity)

    @Upsert
    protected abstract suspend fun upsertIdentity(detail: IdentityDetailEntity)

    @Upsert
    protected abstract suspend fun upsertPolicy(policy: PasswordPolicyEntity)

    @Query("DELETE FROM password_policy WHERE entryId = :id")
    protected abstract suspend fun deletePolicy(id: String)
}
