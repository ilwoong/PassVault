package io.github.ilwoong.passvault.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.security.Argon2KeyDeriver
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** BK-07 고정 픽스처의 내용. 픽스처 파일을 다시 만들면 이 값과 같아야 한다. */
object FixtureV1 {
    const val ASSET = "backup-v1.pvault"
    const val PASSWORD = "fixture-backup-password"

    val entries = listOf(
        Entry(
            "11111111-1111-4111-8111-111111111111", "GitHub", true, 1_700_000_000_000, 1_700_000_100_000, 1_700_000_050_000,
            EntryContent.Login(
                "octocat", "Fixture-S3cret!", "https://github.com", "로그인 메모",
                PasswordPolicy(minLength = 12, upperRule = CharClassRule.REQUIRED, symbolRule = CharClassRule.REQUIRED, rotationDays = 90, rawNote = "특수문자는 !@# 만"),
            ),
        ),
        Entry("22222222-2222-4222-8222-222222222222", "보안 메모", false, 1_700_000_200_000, 1_700_000_300_000, null, EntryContent.Note("본문 🔐 줄바꿈\n둘째 줄")),
        Entry(
            "33333333-3333-4333-8333-333333333333", "카드", false, 1_700_000_400_000, 1_700_000_500_000, null,
            EntryContent.Card("홍길동", "4111111111111111", "VISA", 12, 2030, "123", "0000", null),
        ),
        Entry(
            "44444444-4444-4444-8444-444444444444", "여권", false, 1_700_000_600_000, 1_700_000_700_000, null,
            EntryContent.Identity("여권", "홍길동", "M12345678", "외교부", "2020-01-01", "2030-01-01", "신분증 메모"),
        ),
    )
}

/**
 * BK-07 픽스처 생성기. 평소에는 돌리지 않는다.
 *
 * 포맷 버전을 올릴 때 새 버전 픽스처를 만들려면 @Ignore 를 잠시 빼고:
 * ```
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilwoong.passvault.backup.FixtureGenerator
 * adb exec-out run-as io.github.ilwoong.passvault.debug cat files/backup-v1.pvault > app/src/androidTest/assets/backup-v1.pvault
 * ```
 * **이미 커밋된 이전 버전 픽스처는 절대 다시 만들지 않는다** — 그 파일이 하위 호환의 증거다.
 */
@RunWith(AndroidJUnit4::class)
@Ignore("BK-07 픽스처를 새로 만들 때만 돌린다")
class FixtureGenerator {
    @Test
    fun generate() {
        val bytes = BackupCodec(Argon2KeyDeriver()::derive)
            .encode(FixtureV1.entries, FixtureV1.PASSWORD.toCharArray(), "1.0.0", 1_700_001_000_000)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, FixtureV1.ASSET).writeBytes(bytes)
    }
}
