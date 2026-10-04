package io.github.ilwoong.passvault.data.repo

import io.github.ilwoong.passvault.data.db.CardDetailEntity
import io.github.ilwoong.passvault.data.db.EntryEntity
import io.github.ilwoong.passvault.data.db.IdentityDetailEntity
import io.github.ilwoong.passvault.data.db.LoadedEntry
import io.github.ilwoong.passvault.data.db.LoginDetailEntity
import io.github.ilwoong.passvault.data.db.NoteDetailEntity
import io.github.ilwoong.passvault.data.db.PasswordPolicyEntity
import io.github.ilwoong.passvault.data.db.VaultDao
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.model.EntrySummary
import io.github.ilwoong.passvault.data.model.EntryType
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.policy.PasswordPolicyEvaluator
import io.github.ilwoong.passvault.data.policy.PolicyReport
import io.github.ilwoong.passvault.security.useThenZeroize
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * DM-11 쓰기 불변식을 보장하는 유일한 쓰기 경로.
 *
 * - entry 와 상세는 한 트랜잭션에서 함께 쓴다.
 * - subtitle, last4, hasPolicyViolation, hasRotationDue, updatedAt, passwordUpdatedAt 은 여기서 계산한다.
 */
class EntryRepository(
    private val dao: VaultDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeSummaries(): Flow<List<EntrySummary>> = dao.observeSummaries()

    suspend fun get(id: String): Entry? = dao.load(id)?.toModel()

    /** 저장된 항목의 id 를 돌려준다. */
    suspend fun save(draft: EntryDraft): String {
        require(draft.title.isNotBlank()) { "title 은 필수다 (DM-03)" }
        val now = clock()
        val existing = draft.id?.let { dao.load(it) }
        require(draft.id == null || existing != null) { "없는 항목은 수정할 수 없다: ${draft.id}" }
        require(existing == null || existing.entry.type == draft.content.type) { "항목 타입은 바꿀 수 없다 (UX-06)" }

        val id = existing?.entry?.id ?: UUID.randomUUID().toString()
        fun entry(subtitle: String?, report: PolicyReport = PolicyReport.NotConfigured) = EntryEntity(
            id = id,
            type = draft.content.type,
            title = draft.title,
            subtitle = subtitle?.takeIf { it.isNotBlank() },
            isFavorite = draft.isFavorite,
            createdAtEpochMs = existing?.entry?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            hasPolicyViolation = (report as? PolicyReport.Evaluated)?.hasError == true,
            hasRotationDue = (report as? PolicyReport.Evaluated)?.isRotationDue == true,
        )

        when (val c = draft.content) {
            is EntryContent.Login -> {
                val previous = existing?.login
                val passwordUpdatedAt = when {
                    previous == null || previous.password != c.password -> c.password?.let { now }
                    else -> previous.passwordUpdatedAtEpochMs
                }
                // SEC-12 예외: 비밀번호는 이미 String 이다. 검사용 사본만 지운다.
                val report = (c.password ?: "").toCharArray().useThenZeroize { pw ->
                    PasswordPolicyEvaluator.evaluate(pw, c.policy, passwordUpdatedAt, now)
                }
                dao.saveLogin(
                    entry(c.username, report),
                    LoginDetailEntity(id, c.username, c.password, c.url, c.memo, passwordUpdatedAt),
                    c.policy?.toEntity(id),
                )
            }
            is EntryContent.Note -> dao.saveNote(entry(null), NoteDetailEntity(id, c.body))
            is EntryContent.Card -> {
                val number = normalizeCardNumber(c.number)
                val last4 = cardLast4(number)
                dao.saveCard(
                    entry(last4?.let { "•••• $it" }),
                    CardDetailEntity(
                        id, c.cardholderName, number, last4, c.brand,
                        c.expiryMonth, c.expiryYear, c.cvc, c.pin, c.memo,
                    ),
                )
            }
            is EntryContent.Identity -> dao.saveIdentity(
                entry(c.issuer),
                IdentityDetailEntity(
                    id, c.docType, c.fullName, c.docNumber, c.issuer,
                    c.issuedDate, c.expiryDate, c.memo,
                ),
            )
        }
        return id
    }

    /** 상세·정책은 CASCADE 로 함께 지워진다. 휴지통은 없다 (UX-05). */
    suspend fun delete(id: String) {
        dao.deleteEntry(id)
    }
}

/** DM-06: 숫자만 남긴다. 남는 게 없으면 null. */
internal fun normalizeCardNumber(number: String?): String? =
    number?.filter { it in '0'..'9' }?.takeIf { it.isNotEmpty() }

/** DM-06: 4자리보다 길 때만. 4자리 이하면 last4 가 번호 전체가 되어 목록에 비밀이 노출된다. */
internal fun cardLast4(normalizedNumber: String?): String? =
    normalizedNumber?.takeIf { it.length > 4 }?.takeLast(4)

private fun LoadedEntry.toModel(): Entry {
    val e = entry
    fun <T : Any> detail(value: T?): T = checkNotNull(value) { "DM-11 위반: 상세 없는 entry ${e.id}" }
    val content = when (e.type) {
        EntryType.LOGIN -> detail(login).let {
            EntryContent.Login(it.username, it.password, it.url, it.memo, policy?.toModel())
        }
        EntryType.NOTE -> EntryContent.Note(detail(note).body)
        EntryType.CARD -> detail(card).let {
            EntryContent.Card(
                it.cardholderName, it.number, it.brand, it.expiryMonth, it.expiryYear, it.cvc, it.pin, it.memo,
            )
        }
        EntryType.IDENTITY -> detail(identity).let {
            EntryContent.Identity(it.docType, it.fullName, it.docNumber, it.issuer, it.issuedDate, it.expiryDate, it.memo)
        }
    }
    return Entry(
        id = e.id,
        title = e.title,
        isFavorite = e.isFavorite,
        createdAtEpochMs = e.createdAtEpochMs,
        updatedAtEpochMs = e.updatedAtEpochMs,
        passwordUpdatedAtEpochMs = login?.passwordUpdatedAtEpochMs,
        content = content,
    )
}

private fun PasswordPolicy.toEntity(entryId: String) = PasswordPolicyEntity(
    entryId, minLength, maxLength, upperRule, lowerRule, digitRule, symbolRule,
    allowedSymbols, forbiddenSymbols, maxRepeatRun, disallowSpace, rotationDays, rawNote,
)

private fun PasswordPolicyEntity.toModel() = PasswordPolicy(
    minLength, maxLength, upperRule, lowerRule, digitRule, symbolRule,
    allowedSymbols, forbiddenSymbols, maxRepeatRun, disallowSpace, rotationDays, rawNote,
)
