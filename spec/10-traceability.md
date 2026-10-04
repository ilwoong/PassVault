# 10. 추적성 — SEC-* 구현과 검증 근거

[01](01-threat-model.md)의 보안 요구사항마다 구현 위치와 검증 근거를 한 줄로 적는다 (M9).
요구사항이나 구현이 바뀌면 이 표도 같은 커밋에서 고친다.

경로는 `app/src/main/java/io/github/ilwoong/passvault/` 기준. 테스트는 클래스 이름만 적는다
(`test/` = JVM, `androidTest/` = 계측).

| ID | 구현 | 검증 근거 |
|----|------|-----------|
| SEC-01 | `data/db/VaultDatabase` — SQLCipher 로 DB 파일 전체 암호화, 원시 키 `x'…'` (CRY-05) | `VaultDatabaseTest` (TST-05: 파일 바이트에 평문 없음, 키 없이 열기 실패) |
| SEC-02 | `security/Argon2KeyDeriver` — Argon2id, `calibrateKdf` 로 NFR-01 안에서 최대 파라미터 (CRY-02, CRY-09) | `Argon2KeyDeriverTest`, `KdfCalibrationTest`. 실기기(S23) 266~528ms. 저사양 기기는 미확인 — 아래 수기 표 |
| SEC-03 | `AndroidManifest.xml` `allowBackup=false`, `res/xml/data_extraction_rules.xml` | TST-13 릴리스 머지 매니페스트 확인 (M9) |
| SEC-04 | `backup/BackupCodec` — 백업 비밀번호 → Argon2id → AES-256-GCM, 헤더 AAD. 기기 키 미사용 (BK-02) | `BackupCodecTest`, `BackupIntegrationTest` (v1 고정 픽스처 포함), 에뮬레이터에서 만든 백업을 실기기에서 복구 |
| SEC-05 | `security/AutoLock` (유휴·백그라운드·화면 꺼짐), `MainActivity`·`PassVaultApp` 연결 (LOCK-03). 전환 중의 백그라운드·화면 꺼짐은 `SessionManager` 가 전환 직후 적용한다. 잠기는 즉시 `ui/BranchStores` 가 금고 분기 ViewModel 을 비운다 (LOCK-04 4 단계) | `AutoLockTest`, `AutoLockPickerTest`, `SessionManagerTest`, `BranchStoresTest` (TST-08). 릴리스 빌드 E2E: 백그라운드 8초·20초 뒤 복귀(목록·상세·편집), 화면 꺼짐, 키보드 타이핑 중 유휴 잠금 미발동 |
| SEC-06 | `MainActivity` — `FLAG_SECURE` (디버그 포함, 단일 Activity) | 코드 확인. 실기기에서 화면 캡처와 최근 앱 미리보기가 가려지는 것을 확인 |
| SEC-07 | `ui/common/SecureClipboard` — `EXTRA_IS_SENSITIVE`, 자기 클립만 지움 (LOCK-07) | `SecureClipboardTest`, `ClipboardAndSettingsTest`. 실기기에서 자동 삭제 확인 |
| SEC-08 | `AndroidManifest.xml` — `MainActivity` 만 exported, `ProfileInstallReceiver` 제거 | TST-13 (허용 예외 4건 외 없음, M9) |
| SEC-09 | `security/Backoff`, `VaultKeyManager` 실패 횟수 영속화 — 자동 파기 없음 (LOCK-05) | `BackoffTest`, `VaultKeyManagerTest`, `SessionManagerTest` |
| SEC-10 | `proguard-rules.pro` `Log` 제거 규칙, `EntryContent` 등 `toString()` 마스킹 | `DataDerivationTest` (TST-12 마스킹), 릴리스 dex 의 `android.util.Log` 호출 0건 (M9) |
| SEC-11 | `security/BiometricKeyStore` — `setInvalidatedByBiometricEnrollment(true)` (CRY-07, CRY-13) | `BiometricSessionTest`, 지문 추가 등록 후 거부·안내 E2E (M6) |
| SEC-12 | `security/Secrets` (`zeroize`, `useThenZeroize`), 비밀 입력은 `TextFieldState` → `CharArray` | `SecretsTest` (TST-04). 예외 지점은 코드의 `// SEC-12 예외:` 주석 |

## M9 이후 점검에서 고친 것

스펙·구현 대조 점검에서 기존 E2E 가 놓친 결함 3건을 찾았다. 놓친 이유를 남긴다 — 같은 종류를 다시 놓치지 않기 위해서다.

| 결함 | 놓친 이유 | 지금의 검증 |
|------|-----------|-------------|
| 백그라운드 잠금 뒤 5초 넘게 지나 목록으로 돌아오면 크래시 (`Database is closed`) | E2E 가 잠금 직후 바로 복귀했다. 5초 안에는 목록 쿼리가 다시 시작되지 않는다 | `BranchStoresTest`, 릴리스 E2E (8초·20초) |
| 소프트 키보드로 타이핑하는 동안 유휴 잠금 | E2E 가 `adb input text` 로 입력했다. 이 경로는 `onUserInteraction` 을 지나고, 실제 키보드는 지나지 않는다 | `VaultScreensTest.keyboardEditsAreReportedButReadsAreNot`, 키보드 키를 직접 눌러 확인한 릴리스 E2E |
| 제목 200자·본문 20,000자를 넘는 항목이 있으면 앱이 만든 백업을 앱이 거부 | 라운드트립 테스트가 짧은 값만 썼다 | `BackupCodecTest.entriesAtTheEditLimitsRoundTrip`, `VaultScreensTest.inputBeyondTheLengthLimitIsNotAccepted` |

## 수기 체크리스트 결과

[08 수기 체크리스트](08-testing.md#수기-체크리스트-릴리스-전) 항목별. 난독화 릴리스 빌드 기준.
실기기는 Galaxy S23 (SM-S911N, Android 16), 에뮬레이터는 API 37.

| 항목 | 결과 |
|------|------|
| 최근 앱 화면에서 금고 내용이 보이지 않는다 | 통과 (실기기 — 카드가 빈 화면으로 나온다) |
| 스크린샷 차단 | 통과 (실기기 — 비밀번호를 표시한 상태의 화면 캡처에서 앱 영역이 검게 나온다) |
| 클립보드 지정 시간 후 비움 | 통과 (실기기 — 15초 설정에서 복사 직후 붙여넣기는 값이 들어오고, 30초 뒤에는 비어 있다). 키보드 앱이 따로 보관하는 클립보드 기록은 앱이 지울 수 없다 — 아래 |
| 지문 추가 등록 후 생체 해제 거부·안내 | 통과 (에뮬레이터, M6). 실기기에서는 하지 않았다 — 사용자의 지문 등록을 바꿔야 한다 |
| 기기 A 백업을 기기 B 에서 복구 | 통과 (에뮬레이터 x86_64 → 실기기 arm64) |
| 비행기 모드에서 전 기능 | 통과 (에뮬레이터, M9) |
| 저사양 실기기 해제 1.5초 | **미확인** — S23 은 266~528ms (m=64 MiB, t=5~8) 로 충족하지만 저사양 기기가 아니다 |
| 글꼴 200% / 다크 모드 | 통과 (주요 9개 화면 캡처 점검, M9) |
| 비밀번호 변경 후 구 비밀번호 거부, 항목 유지 | 통과 (계측 TST-03, M6 E2E) |

실기기에서 함께 확인한 것: 계측 테스트 전체(128개 중 실패 0, 건너뜀 2), 항목 1,000건에서 목록 21ms·검색 2ms·필터 8ms (NFR-02),
백그라운드 잠금 뒤 복귀, 삼성 키보드로 타이핑하는 동안 유휴 잠금 미발동.

**확인하지 못한 것 — 키보드의 클립보드 기록**: 삼성 키보드처럼 자체 클립보드 기록을 두는 키보드는 시스템 클립보드를 비워도
기록을 남길 수 있다. 앱은 민감 표시(`EXTRA_IS_SENSITIVE`)를 달 뿐 그 기록을 지울 수단이 없다 (LOCK-07 의 한계).
키보드가 민감 표시를 존중하는지는 사용자의 개인 기록을 읽어야 해서 자동으로 확인하지 않았다.
