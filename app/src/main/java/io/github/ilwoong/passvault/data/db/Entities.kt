package io.github.ilwoong.passvault.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.EntryType

// DM-03 ~ DM-08. 상세 엔티티는 비밀 필드를 담으므로 toString 이 내용을 드러내지 않는다 (SEC-10).
// SEC-12 예외: Room TEXT 컬럼은 String 이다 (CRY-16).

/** DM-03. 목록은 이 테이블만 읽는다. 비밀 필드가 없다. */
@Entity(
    tableName = "entry",
    indices = [Index("type"), Index("title"), Index("isFavorite")],
)
data class EntryEntity(
    @PrimaryKey val id: String,
    val type: EntryType,
    val title: String,
    val subtitle: String?,
    val isFavorite: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val hasPolicyViolation: Boolean,
    val hasRotationDue: Boolean,
)

/** DM-04 */
@Entity(
    tableName = "login_detail",
    foreignKeys = [ForeignKey(EntryEntity::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE)],
)
data class LoginDetailEntity(
    @PrimaryKey val entryId: String,
    val username: String?,
    val password: String?,
    val url: String?,
    val memo: String?,
    val passwordUpdatedAtEpochMs: Long?,
) {
    override fun toString() = "LoginDetailEntity(…)"
}

/** DM-05 */
@Entity(
    tableName = "note_detail",
    foreignKeys = [ForeignKey(EntryEntity::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE)],
)
data class NoteDetailEntity(
    @PrimaryKey val entryId: String,
    val body: String,
) {
    override fun toString() = "NoteDetailEntity(…)"
}

/** DM-06 */
@Entity(
    tableName = "card_detail",
    foreignKeys = [ForeignKey(EntryEntity::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE)],
)
data class CardDetailEntity(
    @PrimaryKey val entryId: String,
    val cardholderName: String?,
    val number: String?,
    val last4: String?,
    val brand: String?,
    val expiryMonth: Int?,
    val expiryYear: Int?,
    val cvc: String?,
    val pin: String?,
    val memo: String?,
) {
    override fun toString() = "CardDetailEntity(…)"
}

/** DM-07 */
@Entity(
    tableName = "identity_detail",
    foreignKeys = [ForeignKey(EntryEntity::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE)],
)
data class IdentityDetailEntity(
    @PrimaryKey val entryId: String,
    val docType: String?,
    val fullName: String?,
    val docNumber: String?,
    val issuer: String?,
    val issuedDate: String?,
    val expiryDate: String?,
    val memo: String?,
) {
    override fun toString() = "IdentityDetailEntity(…)"
}

/** DM-08. 로그인 항목과 1:1, 선택적. 비밀이 아니다. */
@Entity(
    tableName = "password_policy",
    foreignKeys = [ForeignKey(EntryEntity::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE)],
)
data class PasswordPolicyEntity(
    @PrimaryKey val entryId: String,
    val minLength: Int?,
    val maxLength: Int?,
    val upperRule: CharClassRule,
    val lowerRule: CharClassRule,
    val digitRule: CharClassRule,
    val symbolRule: CharClassRule,
    val allowedSymbols: String?,
    val forbiddenSymbols: String?,
    val maxRepeatRun: Int?,
    val disallowSpace: Boolean,
    val rotationDays: Int?,
    val rawNote: String?,
)
