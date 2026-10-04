package io.github.ilwoong.passvault.ui.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.model.EntryType
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class FieldKey {
    TITLE, USERNAME, PASSWORD, URL, MEMO, BODY,
    CARDHOLDER, NUMBER, BRAND, EXP_MONTH, EXP_YEAR, CVC, PIN,
    DOC_TYPE, FULL_NAME, DOC_NUMBER, ISSUER, ISSUED, EXPIRY,
}

/** 입력 방식. 학습 차단은 모든 종류에 루트 인터셉터가 건다 (UX-06). */
enum class FieldKind { PLAIN, SECRET, SECRET_NUMBER, MULTILINE, MONTH, YEAR, DATE }

class FormField(val key: FieldKey, @StringRes val label: Int, val kind: FieldKind, val initial: String) {
    /** UX-00b: 저장되지 않는 상태다. */
    val state = TextFieldState(initial)

    val isValid: Boolean get() = validate(kind, state.text.toString())
}

internal fun validate(kind: FieldKind, text: String): Boolean = when {
    text.isEmpty() -> true
    kind == FieldKind.MONTH -> text.toIntOrNull()?.let { it in 1..12 } == true
    kind == FieldKind.YEAR -> text.length == 4 && text.all { it in '0'..'9' }
    kind == FieldKind.DATE -> try {
        LocalDate.parse(text)
        true
    } catch (e: DateTimeParseException) {
        false
    }
    else -> true
}

/** UX-06 편집 상태. 타입은 생성 시 정해지고 바뀌지 않는다. 로그인이면 정책(UX-07)도 편집한다. */
class EntryForm(val type: EntryType, private val existing: Entry?) {

    val fields: List<FormField> = buildList {
        add(FormField(FieldKey.TITLE, R.string.field_title, FieldKind.PLAIN, existing?.title.orEmpty()))
        when (val c = existing?.content ?: blank(type)) {
            is EntryContent.Login -> {
                add(FormField(FieldKey.USERNAME, R.string.field_username, FieldKind.PLAIN, c.username.orEmpty()))
                add(FormField(FieldKey.PASSWORD, R.string.field_password, FieldKind.SECRET, c.password.orEmpty()))
                add(FormField(FieldKey.URL, R.string.field_url, FieldKind.PLAIN, c.url.orEmpty()))
                add(FormField(FieldKey.MEMO, R.string.field_memo, FieldKind.MULTILINE, c.memo.orEmpty()))
            }
            is EntryContent.Note ->
                add(FormField(FieldKey.BODY, R.string.field_body, FieldKind.MULTILINE, c.body))
            is EntryContent.Card -> {
                add(FormField(FieldKey.CARDHOLDER, R.string.field_cardholder, FieldKind.PLAIN, c.cardholderName.orEmpty()))
                add(FormField(FieldKey.NUMBER, R.string.field_card_number, FieldKind.SECRET_NUMBER, c.number.orEmpty()))
                add(FormField(FieldKey.BRAND, R.string.field_brand, FieldKind.PLAIN, c.brand.orEmpty()))
                add(FormField(FieldKey.EXP_MONTH, R.string.field_expiry_month, FieldKind.MONTH, c.expiryMonth?.let { "%02d".format(it) }.orEmpty()))
                add(FormField(FieldKey.EXP_YEAR, R.string.field_expiry_year, FieldKind.YEAR, c.expiryYear?.toString().orEmpty()))
                add(FormField(FieldKey.CVC, R.string.field_cvc, FieldKind.SECRET_NUMBER, c.cvc.orEmpty()))
                add(FormField(FieldKey.PIN, R.string.field_pin, FieldKind.SECRET_NUMBER, c.pin.orEmpty()))
                add(FormField(FieldKey.MEMO, R.string.field_memo, FieldKind.MULTILINE, c.memo.orEmpty()))
            }
            is EntryContent.Identity -> {
                add(FormField(FieldKey.DOC_TYPE, R.string.field_doc_type, FieldKind.PLAIN, c.docType.orEmpty()))
                add(FormField(FieldKey.FULL_NAME, R.string.field_full_name, FieldKind.PLAIN, c.fullName.orEmpty()))
                add(FormField(FieldKey.DOC_NUMBER, R.string.field_doc_number, FieldKind.SECRET, c.docNumber.orEmpty()))
                add(FormField(FieldKey.ISSUER, R.string.field_issuer, FieldKind.PLAIN, c.issuer.orEmpty()))
                add(FormField(FieldKey.ISSUED, R.string.field_issued_date, FieldKind.DATE, c.issuedDate.orEmpty()))
                add(FormField(FieldKey.EXPIRY, R.string.field_expiry_date, FieldKind.DATE, c.expiryDate.orEmpty()))
                add(FormField(FieldKey.MEMO, R.string.field_memo, FieldKind.MULTILINE, c.memo.orEmpty()))
            }
        }
    }

    /** UX-07. 로그인 항목에만 있다. */
    val policy: PolicyForm? =
        if (type == EntryType.LOGIN) PolicyForm((existing?.content as? EntryContent.Login)?.policy) else null

    val isNew: Boolean get() = existing == null

    val titleOk: Boolean get() = field(FieldKey.TITLE).state.text.isNotBlank()

    val isValid: Boolean get() = titleOk && fields.all { it.isValid } && policy?.isValid != false

    val isDirty: Boolean get() = fields.any { !it.state.text.contentEquals(it.initial) } || policy?.isDirty == true

    /** UX-07 실시간 검사용. 로그인이 아니면 null. */
    val passwordText: CharSequence? get() = fields.firstOrNull { it.key == FieldKey.PASSWORD }?.state?.text

    /**
     * 실시간 검사가 쓸 비밀번호 변경 시각. 비밀번호를 바꿨다면 저장 시 지금으로 바뀐다 (Repository 규칙과 같다).
     */
    fun passwordUpdatedAtForCheck(now: Long): Long? {
        val pw = fields.firstOrNull { it.key == FieldKey.PASSWORD } ?: return null
        val changed = !pw.state.text.contentEquals(pw.initial)
        return if (existing == null || changed) pw.state.text.takeIf { it.isNotEmpty() }?.let { now }
        else existing.passwordUpdatedAtEpochMs
    }

    /** SEC-12 예외: 저장 경로는 String 이다 (CRY-16). 빈 칸은 null 로 저장한다. */
    fun toDraft(): EntryDraft {
        fun v(k: FieldKey) = field(k).state.text.toString().takeIf { it.isNotBlank() }
        val content = when (type) {
            EntryType.LOGIN -> EntryContent.Login(
                v(FieldKey.USERNAME), v(FieldKey.PASSWORD), v(FieldKey.URL), v(FieldKey.MEMO), policy?.toPolicy(),
            )
            EntryType.NOTE -> EntryContent.Note(field(FieldKey.BODY).state.text.toString())
            EntryType.CARD -> EntryContent.Card(
                v(FieldKey.CARDHOLDER), v(FieldKey.NUMBER), v(FieldKey.BRAND),
                v(FieldKey.EXP_MONTH)?.toInt(), v(FieldKey.EXP_YEAR)?.toInt(),
                v(FieldKey.CVC), v(FieldKey.PIN), v(FieldKey.MEMO),
            )
            EntryType.IDENTITY -> EntryContent.Identity(
                v(FieldKey.DOC_TYPE), v(FieldKey.FULL_NAME), v(FieldKey.DOC_NUMBER), v(FieldKey.ISSUER),
                v(FieldKey.ISSUED), v(FieldKey.EXPIRY), v(FieldKey.MEMO),
            )
        }
        return EntryDraft(existing?.id, field(FieldKey.TITLE).state.text.toString(), existing?.isFavorite ?: false, content)
    }

    private fun field(key: FieldKey) = fields.first { it.key == key }

    private fun blank(type: EntryType): EntryContent = when (type) {
        EntryType.LOGIN -> EntryContent.Login()
        EntryType.NOTE -> EntryContent.Note()
        EntryType.CARD -> EntryContent.Card()
        EntryType.IDENTITY -> EntryContent.Identity()
    }
}
