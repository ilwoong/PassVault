package io.github.ilwoong.passvault.data.model

enum class EntryType { LOGIN, NOTE, CARD, IDENTITY }

/**
 * UX-06, BK-08 길이 상한. 편집 화면이 입력에서 막고 백업 가져오기가 같은 값으로 검증한다.
 * 둘이 어긋나면 앱이 만든 백업을 앱이 복구하지 못한다.
 */
const val MAX_TITLE_LENGTH = 200
const val MAX_TEXT_LENGTH = 20_000

/** DM-09. 기본값은 UNKNOWN — 모르는 규칙은 검사하지 않는다. */
enum class CharClassRule { REQUIRED, ALLOWED, FORBIDDEN, UNKNOWN }

/** DM-08. 서비스가 요구하는 비밀번호 규칙. 비밀이 아니다. */
data class PasswordPolicy(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val upperRule: CharClassRule = CharClassRule.UNKNOWN,
    val lowerRule: CharClassRule = CharClassRule.UNKNOWN,
    val digitRule: CharClassRule = CharClassRule.UNKNOWN,
    val symbolRule: CharClassRule = CharClassRule.UNKNOWN,
    val allowedSymbols: String? = null,
    val forbiddenSymbols: String? = null,
    val maxRepeatRun: Int? = null,
    val disallowSpace: Boolean = false,
    val rotationDays: Int? = null,
    val rawNote: String? = null,
)

/**
 * 타입별 내용 (DM-04 ~ DM-07). 비밀 필드를 담으므로 toString 이 내용을 드러내지 않는다 (SEC-10).
 *
 * SEC-12 예외: Room 엔티티와 Compose 입력이 String 을 요구한다 (CRY-16).
 */
sealed interface EntryContent {
    val type: EntryType

    data class Login(
        val username: String? = null,
        val password: String? = null,
        val url: String? = null,
        val memo: String? = null,
        val policy: PasswordPolicy? = null,
    ) : EntryContent {
        override val type: EntryType get() = EntryType.LOGIN
        override fun toString() = "Login(…)"
    }

    data class Note(val body: String = "") : EntryContent {
        override val type: EntryType get() = EntryType.NOTE
        override fun toString() = "Note(…)"
    }

    data class Card(
        val cardholderName: String? = null,
        val number: String? = null,
        val brand: String? = null,
        val expiryMonth: Int? = null,
        val expiryYear: Int? = null,
        val cvc: String? = null,
        val pin: String? = null,
        val memo: String? = null,
    ) : EntryContent {
        override val type: EntryType get() = EntryType.CARD
        override fun toString() = "Card(…)"
    }

    data class Identity(
        val docType: String? = null,
        val fullName: String? = null,
        val docNumber: String? = null,
        val issuer: String? = null,
        val issuedDate: String? = null,
        val expiryDate: String? = null,
        val memo: String? = null,
    ) : EntryContent {
        override val type: EntryType get() = EntryType.IDENTITY
        override fun toString() = "Identity(…)"
    }
}

/** 저장된 항목. 시각 필드는 Repository 가 관리한다 (DM-11). */
data class Entry(
    val id: String,
    val title: String,
    val isFavorite: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** LOGIN 에만 있다. 비밀번호가 바뀐 시각 (DM-04). */
    val passwordUpdatedAtEpochMs: Long?,
    val content: EntryContent,
)

/** 저장 요청. [id] 가 null 이면 새 항목이다. */
data class EntryDraft(
    val id: String?,
    val title: String,
    val isFavorite: Boolean,
    val content: EntryContent,
)

/** 목록 한 행. entry 테이블만으로 만든다 — 비밀 필드가 없다 (NFR-02). */
data class EntrySummary(
    val id: String,
    val type: EntryType,
    val title: String,
    val subtitle: String?,
    val isFavorite: Boolean,
    val hasPolicyViolation: Boolean,
    val hasRotationDue: Boolean,
)
