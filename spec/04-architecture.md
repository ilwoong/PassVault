# 04. 아키텍처

## ARC-01 모듈 구성: 단일 모듈

1차는 `:app` 단일 모듈로 간다. 패키지로만 레이어를 나눈다.

멀티모듈을 **지금** 하지 않는 이유: 기능 범위가 확정적이고([00](00-overview.md)) 팀이 작다.
모듈 경계는 빌드 설정·DI 배선 비용을 먼저 청구하고 이득은 나중에 준다.

분리 시점 (아래 중 하나가 발생하면 재검토):
- Autofill 서비스 추가 — 별도 프로세스에서 데이터 레이어 재사용이 필요해진다
- Wear/태블릿 등 두 번째 UI 타깃
- 빌드 시간이 개발을 방해하는 수준

분리 순서는 `:core:crypto` → `:core:data` → `:feature:*`. 그 전까지는 **패키지 간 의존 방향만** 지킨다.

## ARC-02 패키지 구조

```
com.example.passvault
├─ PassVaultApp.kt            // Application, Hilt 진입점
├─ MainActivity.kt            // 단일 Activity. FLAG_SECURE 설정 (LOCK-06)
├─ security/                  // ← Android UI 의존 없음
│   ├─ Argon2KeyDeriver.kt       // CRY-02, CRY-09
│   ├─ AesGcmKeyWrapper.kt       // CRY-03
│   ├─ BiometricKeyStore.kt      // CRY-07, CRY-12, CRY-13
│   ├─ VaultMetaStore.kt         // DM-01. 원자적 교체 책임
│   ├─ VaultKeyManager.kt        // 생성/해제/변경 조율 (CRY-10, 11, 15)
│   ├─ SessionManager.kt         // 상태머신 + VK 보유 (LOCK-01~04)
│   └─ Secrets.kt                // ByteArray/CharArray 제로화 헬퍼 (SEC-12)
├─ data/
│   ├─ db/                       // Room: Entity, DAO, Database, Migration
│   ├─ repo/                     // EntryRepository, SettingsRepository
│   └─ policy/                   // PasswordPolicyEvaluator (DM-10) — 순수 코틀린
├─ backup/                    // BackupWriter, BackupReader, 포맷 정의 (07)
├─ ui/
│   ├─ theme/
│   ├─ common/                   // 공용 Composable (SecretField, PolicyBadge 등)
│   ├─ unlock/                   // UX-02, UX-03
│   ├─ onboarding/               // UX-01
│   ├─ list/                     // UX-04
│   ├─ detail/                   // UX-05
│   ├─ edit/                     // UX-06, UX-07
│   └─ settings/                 // UX-08~UX-11
└─ di/                        // Hilt 모듈
```

### 의존 방향 (ARC-03)

```
ui  →  data  →  security
 └──────────────┘ (SessionManager 참조만 허용)
```

- `security` 는 `data` / `ui` 를 **참조하지 않는다.**
- `data` 는 `ui` 를 참조하지 않는다.
- `security/*` 와 `data/policy/*` 는 Android 프레임워크 타입을 쓰지 않는다
  (Keystore·BiometricPrompt를 쓰는 `BiometricKeyStore` 만 예외). JVM 단위 테스트 대상이다.

## ARC-04 라이브러리

**버전은 여기 적지 않는다.** `gradle/libs.versions.toml` 에서 version catalog로 관리한다.

| 역할 | 선택 | 메모 |
|------|------|------|
| UI | Jetpack Compose + Material 3 | |
| 내비게이션 | Navigation Compose | |
| DI | Hilt | |
| DB | Room | `exportSchema = true` (DM-12) |
| DB 암호화 | SQLCipher for Android | Room의 `openHelperFactory` 에 연결. 현행 아티팩트 좌표는 구현 시 확인 (`net.zetetic:sqlcipher-android` 계열) |
| Argon2 | Argon2 JNI 바인딩 (예: `argon2kt`) | 순수 JVM 구현은 성능이 부족하다. ABI 4종(arm64/armeabi-v7a/x86/x86_64) 포함 여부 확인 |
| 생체 | `androidx.biometric` | |
| 메타·설정 저장 | DataStore (Proto / Preferences) | |
| 비동기 | Coroutines + Flow | |
| 테스트 | JUnit, Turbine, Room testing, Compose UI test | [08](08-testing.md) |

의존성 추가 기준: **비밀을 다루는 경로에는 새 의존성을 넣지 않는다.** 꼭 필요하면 스펙에 먼저 적고 이유를 남긴다.

## ARC-05 스레딩

- Argon2id 호출(수백 ms~1.5초)은 `Dispatchers.Default`. 진행 표시를 띄우고 취소 가능하게 한다.
- DB 접근은 Room의 `suspend` DAO. 직접 스레드를 만들지 않는다.
- `SessionManager` 상태는 `StateFlow`. UI는 이것만 구독한다.
- VK 바이트는 `SessionManager` 밖으로 복사해 나가지 않는다. 필요한 쪽은 `SessionManager`에
  작업을 넘긴다(`withVaultKey { }` 형태). 복사본이 돌아다니면 제로화 시점을 보장할 수 없다 (SEC-12).

## ARC-06 에러 처리

사용자에게 보여줄 오류를 sealed 타입으로 좁게 정의한다. 예외를 UI까지 던지지 않는다.

| 오류 | 사용자 메시지 방향 | 금지 |
|------|-------------------|------|
| 비밀번호 불일치 (GCM 태그 실패) | "비밀번호가 올바르지 않습니다" | 실패 원인 세분화 금지 |
| 잠금 백오프 중 | 남은 시간 표시 | |
| 생체 키 무효화 | 재등록 안내 (CRY-13) | |
| DB 열기 실패 / 손상 | "금고를 열 수 없습니다" + 백업 복구 안내 | 자동 재생성·자동 삭제 **절대 금지** |
| 백업 파일 손상·비밀번호 오류 | 구분해 표시 ([BK-06](07-backup.md)) | |

- 스택트레이스·예외 메시지를 사용자 화면에 노출하지 않는다 (SEC-10).
- 릴리스 빌드에서 `Log.*` 호출은 R8 규칙으로 제거한다 ([TST-12](08-testing.md)).

## ARC-07 빌드 설정

| 항목 | 값 |
|------|-----|
| `applicationId` | `TODO(결정필요):` 실제 패키지명 |
| `minSdk` / `targetSdk` / `compileSdk` | 28 / 36 / 36 |
| `allowBackup` | `false` (SEC-03) |
| `dataExtractionRules` | 기기 간 전송·클라우드 백업 모두 제외 (SEC-03) |
| `MainActivity.exported` | 런처이므로 `true`. 그 외 모든 컴포넌트 `false` (SEC-08) |
| 권한 | **선언 없음.** `INTERNET` 포함 (NFR-03) |
| 릴리스 | R8 축소·난독화 활성, `debuggable=false`, 로그 제거 |
| 디버그 | `applicationIdSuffix = ".debug"` 로 릴리스 금고와 공존 |
