# 03. 데이터 모델

## 저장소 분리

세 저장소를 쓴다. 경계를 넘기지 않는 것이 중요하다.

| 저장소 | 내용 | 접근 시점 | 구현 |
|--------|------|-----------|------|
| `vault_meta` | KDF 파라미터, 래핑된 VK, 실패 횟수 | **잠금 상태에서도** 읽어야 함 | DataStore(Proto) 또는 단일 파일. 원자적 교체 필수 (CRY-15) |
| `vault.db` | 항목 전체 | 해제 후에만 | Room + SQLCipher ([CRY-05](02-crypto.md)) |
| `settings` | 사용자 설정 | 항상 | DataStore Preferences |

### DM-01 `vault_meta` 스키마

```
metaVersion: Int           // 이 메타 포맷의 버전. 현재 1
kdfSalt: ByteArray(16)
kdfMemoryKiB: Int          // 예: 65536
kdfIterations: Int
kdfParallelism: Int
wrappedVkByMk: ByteArray   // nonce(12) || ciphertext || tag(16)
wrappedVkByBio: ByteArray? // null = 생체 해제 미설정
failedAttempts: Int        // LOCK-05
lockoutUntilEpochMs: Long  // 0 = 잠김 없음. 벽시계 기한
vaultCreatedAtEpochMs: Long
lockoutBootCount: Int      // LOCK-05. 잠김이 시작된 부팅 번호
lockoutUntilElapsedMs: Long // LOCK-05. 같은 부팅 안에서만 유효한 단조 시계 기한
```

#### 디스크 레이아웃 (metaVersion = 1)

**188 바이트 고정 길이.** 정수는 전부 big-endian. 가변 길이 필드를 두지 않아 파서에 모호함이 없다.

```
오프셋  크기  필드
   0     6   magic "PVMETA" (ASCII)
   6     1   metaVersion = 1
   7     1   hasBioWrap (0 | 1)
   8    16   kdfSalt
  24     4   kdfMemoryKiB   (u32)
  28     4   kdfIterations  (u32)
  32     4   kdfParallelism (u32)
  36    60   wrappedVkByMk  = nonce(12) || ciphertext(32) || tag(16)
  96    60   wrappedVkByBio = iv(12) || ciphertext(32) || tag(16). hasBioWrap = 0 이면 전부 0
 156     4   failedAttempts        (i32)
 160     8   lockoutUntilEpochMs   (i64)
 168     8   vaultCreatedAtEpochMs (i64)
 176     4   lockoutBootCount      (i32)
 180     8   lockoutUntilElapsedMs (i64)
```

> M3 에서 마지막 두 필드를 덧붙였다 (176 → 188). 배포 전이라 v1 을 그대로 확장했다.
> 배포 후에는 레이아웃을 바꿀 때 반드시 `metaVersion` 을 올린다.

VK 길이(32B)가 바뀌면 `metaVersion` 을 올린다.

#### 읽기 결과는 세 가지로 구분한다

| 상태 | 조건 | 처리 |
|------|------|------|
| `Absent` | 파일 없음 | 금고 없음 → 온보딩 ([UX-01](05-ui-flows.md)) |
| `Present` | 크기·magic·version 모두 정상 | 해제 화면 |
| `Corrupt` | 파일은 있는데 크기 ≠ 188, magic 불일치, 알 수 없는 version, `hasBioWrap` ∉ {0,1}, KDF 파라미터가 [CRY-09](02-crypto.md) 산출 범위 밖(m ∉ [32 MiB, 64 MiB], t ∉ [1, 8], p ≠ 2) | "금고를 열 수 없습니다" + 백업 복구 안내 ([ARC-06](04-architecture.md)) |

KDF 파라미터 범위 검사를 읽기 단계에서 하는 이유: v1 파일은 CRY-09 캘리브레이션만이 쓰므로
그 범위 밖의 값은 손상이다. 읽기에서 걸러야 사용자가 열 수 없는 금고에 비밀번호를 입력하게 되지 않는다
(그렇지 않으면 비트 하나가 뒤집힌 `m` 이 수 TB 할당을 시도하다 해제 시점에야 실패한다).

**`Corrupt` 를 `Absent` 로 취급하면 안 된다.** 그렇게 하면 온보딩으로 빠져 새 금고를 만들면서
기존 메타를 덮어써 데이터가 영구히 사라진다. 이 구분이 이 파일 설계에서 가장 중요한 규칙이다.

#### 쓰기는 원자적 교체만 허용한다 (CRY-15)

```
1. vault_meta.tmp 에 188 바이트 전체를 쓴다
2. fsync (FileDescriptor.sync)
3. rename(vault_meta.tmp → vault_meta)   ← 같은 파일시스템에서 원자적
```

- `vault_meta` 를 **쓰기 모드로 직접 열지 않는다.** 중간에 프로세스가 죽어도 대상 파일은 항상 구 버전 또는 신 버전 중 하나로 온전하다.
- 읽기는 `vault_meta.tmp` 를 무시한다. 남아 있는 `.tmp` 는 중단된 쓰기의 흔적일 뿐 진실의 원천이 아니다.

#### 보호 범위

`wrappedVkByMk` 는 GCM 태그로 무결성이 보장되고, salt·KDF 파라미터는 MK 값 자체에 묶여 있어
변조 시 언래핑이 실패한다 (CRY-03). `failedAttempts` / `lockoutUntilEpochMs` 는 **인증되지 않는다** —
이 파일을 고쳐 쓸 수 있는 공격자는 앱 전용 저장소에 쓰기 권한을 가진 것이고, 이는 루팅 기기로
[01](01-threat-model.md) Out of scope 이다.

금고가 존재하는지 여부는 위 `Absent` / 그 외로 판단한다. 이것이 온보딩 분기 조건이다.

### DM-02 설정 스키마

```
autoLockSeconds: Int        // 기본 60. 허용: 15 / 30 / 60 / 300 / 0(즉시)
lockOnBackground: Boolean   // 기본 true
// biometricEnabled 는 저장하지 않는다 — vault_meta 의 wrappedVkByBio 유무에서 파생한다 (M6).
// 따로 저장하면 둘이 어긋날 수 있고, DM-11 5 번 규칙이 그 경우를 다뤄야 한다. 파생하면 어긋날 수 없다.
clipboardClearSeconds: Int  // 기본 30. 0 = 자동 삭제 안 함
```

## 항목 모델

공통 필드 + 타입별 상세 테이블(1:1)로 나눈다. 목록 화면은 `entry` 테이블만 읽으므로
비밀 필드를 건드리지 않고 NFR-02를 만족한다.

### DM-03 `entry` (공통)

| 컬럼 | 타입 | 비고 |
|------|------|------|
| `id` | TEXT PK | UUID v4 문자열. 백업 복구 시 안정적인 식별자가 필요하므로 자동증가 정수를 쓰지 않는다 |
| `type` | TEXT | `LOGIN` / `NOTE` / `CARD` / `IDENTITY` |
| `title` | TEXT | 필수. 목록·검색 대상 |
| `subtitle` | TEXT? | 목록 2행에 표시할 비-비밀 요약 (로그인=사용자명, 카드=`•••• 1234`, 신분증=발급기관, 메모=null) |
| `isFavorite` | INTEGER | 0/1 |
| `createdAtEpochMs` | INTEGER | |
| `updatedAtEpochMs` | INTEGER | |
| `hasPolicyViolation` | INTEGER | 0/1. 정책 검사 결과 **캐시** — 아래 설명 |
| `hasRotationDue` | INTEGER | 0/1. 변경 주기 경과 캐시 |

인덱스: `type`, `title`, `isFavorite`.

> **비정규화 두 건의 이유.**
> `subtitle` 은 목록 렌더링에서 상세 테이블 JOIN을 없애기 위한 것이다.
> `hasPolicyViolation` / `hasRotationDue` 는 더 중요한 이유가 있다 — 목록에 정책 경고 배지를
> 띄우려면 비밀번호를 검사해야 하는데, 목록 화면에서 비밀 필드를 읽는 것은
> NFR-02와 [01](01-threat-model.md) T-02에 어긋난다. 그래서 **저장 시점에 한 번 평가해
> 불리언만 캐시**하고 목록은 캐시만 읽는다.
> 세 값 모두 상세를 쓰는 같은 트랜잭션에서 갱신한다 (DM-11).
>
> `hasRotationDue` 는 시간 경과로 값이 바뀌므로 저장 시점 캐시만으로는 낡는다 →
> 앱이 포그라운드로 올라올 때 `rotationDays` 가 설정된 항목에 대해 일괄 재계산한다.
> 이 계산은 `passwordUpdatedAtEpochMs` 와 `rotationDays` 만 쓰므로 비밀번호를 읽지 않는다.

### DM-04 `login_detail`

| 컬럼 | 타입 | 비고 |
|------|------|------|
| `entryId` | TEXT PK FK → entry(id) ON DELETE CASCADE | |
| `username` | TEXT? | |
| `password` | TEXT? | **비밀 필드** |
| `url` | TEXT? | |
| `totpSecret` | — | 1차 제외 ([00](00-overview.md) 비범위). 컬럼도 만들지 않는다 |
| `memo` | TEXT? | **비밀 필드** |
| `passwordUpdatedAtEpochMs` | INTEGER? | 정책의 변경 주기 검사에 사용 (DM-10) |

### DM-05 `note_detail`

| 컬럼 | 타입 |
|------|------|
| `entryId` | TEXT PK FK |
| `body` | TEXT — **비밀 필드** |

### DM-06 `card_detail`

| 컬럼 | 타입 | 비고 |
|------|------|------|
| `entryId` | TEXT PK FK | |
| `cardholderName` | TEXT? | |
| `number` | TEXT? | **비밀 필드**. 숫자만 저장, 하이픈 제거 |
| `last4` | TEXT? | `subtitle` 생성용. 비밀로 보지 않는다. **번호가 4자리보다 길 때만** 만든다 — 4자리 이하면 `last4` 가 번호 전체가 되어 목록에 비밀이 노출된다 |
| `brand` | TEXT? | 사용자 입력. 번호에서 자동 판별하지 않는다 (불필요한 로직) |
| `expiryMonth` / `expiryYear` | INTEGER? | |
| `cvc` | TEXT? | **비밀 필드** |
| `pin` | TEXT? | **비밀 필드** |
| `memo` | TEXT? | **비밀 필드** |

### DM-07 `identity_detail`

| 컬럼 | 타입 | 비고 |
|------|------|------|
| `entryId` | TEXT PK FK | |
| `docType` | TEXT? | 자유 입력 (주민등록증, 운전면허, 여권 등). enum으로 고정하지 않는다 |
| `fullName` | TEXT? | |
| `docNumber` | TEXT? | **비밀 필드** |
| `issuer` | TEXT? | |
| `issuedDate` / `expiryDate` | TEXT? | ISO-8601 `yyyy-MM-dd` 문자열 |
| `memo` | TEXT? | **비밀 필드** |

## 비밀번호 정책

F-04. 각 서비스가 요구하는 비밀번호 규칙을 기록해 두고, 저장된 비밀번호가 그 규칙을
만족하는지 보여준다. **비밀번호를 만들어 주는 기능이 아니다** (생성기는 1차 비범위).

### DM-08 `password_policy`

`login_detail` 과 1:1, 선택적. 정책을 입력하지 않은 항목은 행이 없다.

| 컬럼 | 타입 | 의미 |
|------|------|------|
| `entryId` | TEXT PK FK → entry(id) ON DELETE CASCADE | |
| `minLength` | INTEGER? | |
| `maxLength` | INTEGER? | 길이 상한이 있는 서비스가 많아 기록 가치가 있다 |
| `upperRule` | TEXT | 영문 대문자 — DM-09 |
| `lowerRule` | TEXT | 영문 소문자 |
| `digitRule` | TEXT | 숫자 |
| `symbolRule` | TEXT | 특수문자 |
| `allowedSymbols` | TEXT? | 허용 특수문자 집합. 예: `!@#$%^&*` . null = 제한 없음/모름. **빈 문자열도 null 로 취급한다** — 비워 둔 입력란이 "특수문자 전부 금지"로 해석되는 오탐을 막는다 |
| `forbiddenSymbols` | TEXT? | 금지 특수문자 집합 |
| `maxRepeatRun` | INTEGER? | 같은 문자 연속 허용 최대 길이. 예: 2 = `aaa` 불가 |
| `disallowSpace` | INTEGER | 0/1 — 공백 금지 여부 |
| `rotationDays` | INTEGER? | 변경 주기. 예: 90 |
| `rawNote` | TEXT? | 위 필드로 표현 못 하는 규칙 원문. **정책 입력 UI의 탈출구이며 반드시 둔다** |

### DM-09 `CharClassRule` enum

```kotlin
enum class CharClassRule { REQUIRED, ALLOWED, FORBIDDEN, UNKNOWN }
```

- `REQUIRED`: 1자 이상 반드시 포함
- `ALLOWED`: 써도 되지만 필수 아님
- `FORBIDDEN`: 쓰면 안 됨 (특수문자 불가인 서비스가 실제로 있다)
- `UNKNOWN`: 기본값. 모름 — **검사에서 제외한다.** 이 값이 기본인 것이 중요하다.
  모르는 규칙을 "허용"으로 가정해 잘못된 경고를 띄우지 않기 위해서다.

### DM-10 준수 검사 규칙

순수 함수로 구현한다. Android 의존성 없이 JVM 단위 테스트가 가능해야 한다 ([TST-02](08-testing.md)).

```kotlin
fun evaluate(password: CharArray, policy: PasswordPolicy, passwordUpdatedAt: Long?): PolicyReport
```

`PolicyReport` 는 위반 목록이다. 위반 종류:

| 위반 | 조건 |
|------|------|
| `TOO_SHORT` / `TOO_LONG` | 길이가 min/max 밖 |
| `MISSING_UPPER` / `MISSING_LOWER` / `MISSING_DIGIT` / `MISSING_SYMBOL` | 해당 규칙이 `REQUIRED` 인데 없음 |
| `FORBIDDEN_UPPER` / `FORBIDDEN_LOWER` / `FORBIDDEN_DIGIT` / `FORBIDDEN_SYMBOL` | 해당 규칙이 `FORBIDDEN` 인데 있음 |
| `DISALLOWED_SYMBOL` | `allowedSymbols` 밖의 특수문자 사용, 또는 `forbiddenSymbols` 포함 |
| `CONTAINS_SPACE` | `disallowSpace` 인데 공백 포함 |
| `REPEAT_RUN` | 같은 문자 연속이 `maxRepeatRun` 초과 |
| `ROTATION_DUE` | `rotationDays` 경과. **경고 등급** (다른 위반은 오류 등급) |

판정 규칙:
- 정책 행이 없으면 `PolicyReport.NotConfigured` — 아무 표시도 하지 않는다.
- 모든 필드가 `UNKNOWN`/null 이면 위반이 없는 것으로 본다 (검사하지 않음).
- `ROTATION_DUE` 는 `passwordUpdatedAtEpochMs` 가 null 이면 판정하지 않는다.
- `rawNote` 는 자유 텍스트이므로 **검사하지 않고 상세 화면에 그대로 보여준다.**
- "특수문자" 정의: ASCII 출력 가능 문자 중 영문·숫자·공백이 아닌 것. 이 정의를 코드에 상수로 고정한다.
- 대문자·소문자·숫자도 ASCII 범위(`A-Z`, `a-z`, `0-9`)로 판정한다. 한글 등 비 ASCII 문자는 어느 종류에도 속하지 않는다.
- 길이는 UTF-16 코드 유닛 수다. 웹 서비스 대부분이 쓰는 JavaScript `length` 와 같은 기준이다.
- 공백 판정은 `Char.isWhitespace()` — 탭·줄바꿈·전각 공백을 포함한다.
- `ROTATION_DUE` 는 경과 일수 ≥ `rotationDays` 일 때다.
- 정책이 있는데 비밀번호가 비어 있으면 빈 문자열로 평가한다 (`minLength` 가 있으면 `TOO_SHORT`).
- `hasPolicyViolation` = 오류 등급 위반이 하나라도 있음, `hasRotationDue` = `ROTATION_DUE` 있음 (DM-03).

## DM-11 쓰기 불변식

Repository에서 보장한다. 트랜잭션 하나로 처리한다.

1. `entry` 와 상세 행은 항상 함께 생성·삭제된다. 상세 없는 `entry`는 존재하지 않는다.
2. `entry.subtitle`, `card_detail.last4`, `entry.hasPolicyViolation`, `entry.hasRotationDue` 는 상세 저장 시 재계산된다 (DM-03, DM-10).
3. `updatedAtEpochMs` 는 Repository가 갱신한다. UI나 DAO가 직접 넣지 않는다.
4. 삭제는 `ON DELETE CASCADE` + 외래키 활성(`PRAGMA foreign_keys = ON`)에 의존한다.
   SQLCipher/Room 설정에서 외래키가 켜져 있는지 반드시 확인한다 ([TST-06](08-testing.md)).
5. `biometricEnabled` 설정과 `wrappedVkByBio` 존재 여부가 어긋나면 **`wrappedVkByBio` 쪽을 진실로 본다.**

## DM-12 마이그레이션

- Room 스키마 버전 1로 시작. `exportSchema = true`, 스키마 JSON을 저장소에 커밋한다.
- `fallbackToDestructiveMigration()` **금지.** 사용자 데이터가 소실된다.
- 모든 버전 증가에는 명시적 `Migration` + 마이그레이션 테스트를 쌍으로 추가한다 ([TST-06](08-testing.md)).
- `vault_meta` 의 `metaVersion` 은 Room과 별개로 관리한다. 변경 시 마이그레이션 코드를 직접 작성한다.
- 백업 파일 포맷 버전도 별개다 ([BK-01](07-backup.md)).
