# 02. 암호화 설계

이 문서의 수치와 절차는 임의로 바꾸지 않는다. 바꿀 때는 [01](01-threat-model.md)의 어느 요구사항이 영향받는지 함께 적는다.

## 키 계층

```
  마스터 비밀번호 (사용자 기억, 저장 안 함)
        │
        │  Argon2id(salt, m, t, p)          ← CRY-01, CRY-02
        ▼
      MK (32B)  ─── 저장 안 함. 해제 직후 VK를 풀고 바로 폐기
        │
        │  AES-256-GCM unwrap               ← CRY-03
        ▼
      VK (32B, 기기에서 생성한 랜덤)         ← CRY-04
        │
        └──▶ SQLCipher raw key = DB 전체 암호화  ← CRY-05

  저장되는 것 (평문 파일에 둬도 안전한 것들):
    salt, m, t, p                      ← KDF 재현용
    wrapped_vk_by_mk   = AES-GCM(MK, VK)       ← CRY-03
    wrapped_vk_by_bio  = AES-GCM(BioKey, VK)   ← CRY-07 (생체 사용 시에만)

  BioKey: Android Keystore 안에서 생성되고 절대 나오지 않는 AES 키.
          생체 인증 성공 시에만 Cipher 사용이 허가된다.  ← CRY-07, CRY-13
```

**왜 2단 구조인가**: VK를 따로 두면 마스터 비밀번호를 바꿀 때 DB를 재암호화하지 않고
`wrapped_vk_by_mk` 블롭 하나만 다시 쓰면 된다. 생체 해제도 같은 VK를 다른 키로 한 번 더
래핑하는 것으로 끝난다. VK 없이 MK를 직접 DB 키로 쓰면 두 기능 모두 불가능하다.

## 알고리즘과 파라미터

| ID | 용도 | 알고리즘 | 파라미터 |
|----|------|----------|----------|
| CRY-01 | 난수 | `SecureRandom` (no-arg 생성자) | 시드 직접 지정 금지 |
| CRY-02 | 마스터 비밀번호 → MK | **Argon2id** | 기본값: `m = 64 MiB`, `t = 3`, `p = 2`, 출력 32B, salt 16B. 기기별 캘리브레이션은 CRY-09 참조 |
| CRY-03 | VK 래핑/언래핑 | **AES-256-GCM** | nonce 12B(매번 새로), 태그 128bit, AAD = ASCII `"pv:vk:v1"` ‖ `m`(u32 BE) ‖ `t`(u32 BE) ‖ `p`(u32 BE). salt 는 AAD 에 넣지 않는다 — MK 값 자체가 salt 에 묶여 있어 변조 시 언래핑이 이미 실패한다 |
| CRY-04 | VK 생성 | `SecureRandom` 32B | 금고 생성 시 1회. 이후 불변 |
| CRY-05 | DB 암호화 | **SQLCipher 4** raw key 모드 (`PRAGMA key = "x'<VK hex>'"`) | 파생 비활성(raw key이므로 SQLCipher 내부 KDF 생략), 기본 페이지 설정 유지 |
| CRY-06 | 백업 파일 암호화 | Argon2id + AES-256-GCM | 파라미터와 레이아웃은 [07](07-backup.md) |
| CRY-07 | 생체 해제용 키 | Keystore `AES/GCM/NoPadding` 256bit | 아래 "생체" 절 |

### CRY-09 KDF 캘리브레이션

NFR-01(해제 1.5초 이하)과 SEC-02(가능한 한 강하게)가 충돌한다. 금고 **생성 시점에** 아래 순서로
결정한다. 순서가 고정돼 있어야 같은 기기에서 같은 결과가 나온다.

```
m = 64 MiB, t = 3, p = 2
① 측정. 메모리 할당 실패면:
     m > 32 MiB  → m = m / 2, ① 로
     m = 32 MiB  → 실패를 그대로 던진다 (금고를 만들 수 없는 기기)
② 1200ms 초과이고 m > 32 MiB → m = m / 2, ① 로
③ 500ms 미만이고 t < 8      → t = t + 1, 재측정 후 ③ 반복
④ 확정. m/t/p 를 vault_meta 에 저장
```

- **m 을 먼저 맞추고 t 를 나중에 올린다.** 메모리-하드성이 Argon2id 의 핵심이므로 m 을 우선 보존한다.
- ③ 에서 t 를 하나 올려도 시간은 최대 (t+1)/t ≤ 4/3 배라 500ms 미만에서 출발하면 1200ms 를 넘지 않는다. 되돌리기 단계가 필요 없다.
- 32 MiB 아래로는 내리지 않는다. 느린 것을 받아들인다.
- 이후 해제 때는 **저장된 값만** 쓴다. 재측정하지 않는다.

**메모리 할당 실패의 형태** (argon2kt 1.6.0 바이트코드로 확인):
네이티브 `malloc` 실패는 `OutOfMemoryError` 가 아니라 `Argon2Exception`(`ARGON2_MEMORY_ALLOCATION_ERROR`)
으로 온다. 출력 버퍼의 `allocateDirect` 는 `OutOfMemoryError` 를 던질 수 있다. **둘 다 잡는다.**
캘리브레이션은 고정된 유효 입력으로 호출하므로 그 외 `Argon2Exception` 은 사실상 발생하지 않으며,
발생하더라도 32 MiB 에서 다시 던져지므로 버그가 삼켜지지 않는다.

측정에 첫 호출의 페이지 폴트 비용이 포함되는 것은 의도된 것이다 — 실제 해제도 매번 새로 할당한다.

## 저장 위치

VK를 풀기 위한 정보는 **암호화된 DB 안에 둘 수 없다** (DB를 열려면 VK가 필요하므로 순환).
따라서 DB 밖의 별도 저장소를 쓴다. 자세한 스키마는 [03](03-data-model.md#저장소-분리).

| 데이터 | 위치 | 암호화 | 이유 |
|--------|------|--------|------|
| salt, m, t, p, wrapped_vk_by_mk, wrapped_vk_by_bio, 실패 횟수 | `vault_meta` (DB 밖, 앱 내부 저장소) | 불필요 | 모두 래핑된 값 또는 공개 파라미터. 해제 **전에** 읽어야 한다 |
| 항목 전체 | SQLCipher DB | CRY-05 | |
| 설정값 | DataStore Preferences | 불필요 | 비밀 아님 |
| BioKey | Android Keystore | 하드웨어 | 밖으로 나오지 않음 |

## 금고 생성 (CRY-10)

```
1. salt = random(16)
2. m, t, p = 캘리브레이션 (CRY-09)
3. MK = Argon2id(password, salt, m, t, p, 32)
4. VK = random(32)
5. wrapped_vk_by_mk = AES-GCM(MK, VK, nonce=random(12), aad)
6. vault_meta 에 salt/m/t/p/wrapped_vk_by_mk 저장
7. SQLCipher DB를 VK로 생성 + Room 스키마 적용
8. zeroize(MK), zeroize(password 바이트)
```

## 잠금 해제 (CRY-11)

```
1. vault_meta 에서 salt, m, t, p, wrapped_vk_by_mk 읽기
2. MK = Argon2id(입력 비밀번호, salt, m, t, p, 32)
3. VK = AES-GCM-decrypt(MK, wrapped_vk_by_mk)
   → GCM 태그 검증 실패 = 비밀번호 틀림. 이것이 유일한 검증 수단이며
     별도의 검증용 해시를 저장하지 않는다 (공격자에게 주는 정보를 늘리지 않기 위해)
4. zeroize(MK)
5. VK로 SQLCipher DB 열기. 세션을 UNLOCKED 로 전환 ([06](06-lock-policy.md))
6. 실패 시 실패 횟수 증가 + 백오프 (LOCK-05)
```

## 생체 해제

**생체 인증은 마스터 비밀번호를 대체하지 않는다.** VK로 가는 두 번째 문을 여는 것이다.
생체 정보 자체는 앱이 보지 않는다 — Keystore 키 사용 허가만 받는다.

### CRY-07 BioKey 생성 사양

`KeyGenParameterSpec` 설정 (모두 필수):

| 설정 | 값 | 이유 |
|------|-----|------|
| 알고리즘 / 블록 / 패딩 | AES / GCM / NoPadding, 256bit | |
| `setUserAuthenticationRequired` | `true` | 생체 없이 사용 불가 |
| `setUserAuthenticationParameters` | `timeout = 0`, `AUTH_BIOMETRIC_STRONG` | 매 사용마다 인증. Class 3 생체만 허용 |
| `setInvalidatedByBiometricEnrollment` | `true` | **SEC-11** |
| `setUnlockedDeviceRequired` | `true` | 화면 잠긴 상태에서 사용 차단 |
| `setIsStrongBoxBacked` | 가능하면 `true` | `StrongBoxUnavailableException` 시 false로 재시도 |

### CRY-12 생체 등록 (설정에서 켤 때)

마스터 비밀번호를 **다시 입력받아야** 한다. 이미 해제된 세션의 VK를 그대로 쓰면,
기기를 잠깐 빌린 사람이 생체를 등록해 영구 접근 권한을 얻을 수 있다 (T-02).

```
1. 마스터 비밀번호 재확인 → VK 확보
2. BioKey 생성 (CRY-07)
3. BiometricPrompt(CryptoObject(Cipher.ENCRYPT_MODE, BioKey)) 로 인증
4. wrapped_vk_by_bio = Cipher.doFinal(VK), nonce 함께 저장
5. vault_meta 에 저장
```

### CRY-13 생체 해제

```
1. wrapped_vk_by_bio 없으면 생체 해제 UI를 아예 보여주지 않는다
2. BiometricPrompt(CryptoObject(Cipher.DECRYPT_MODE, BioKey, GCMParameterSpec(nonce)))
3. 인증 성공 → VK = Cipher.doFinal(wrapped_vk_by_bio)
4. KeyPermanentlyInvalidatedException → wrapped_vk_by_bio 삭제 + 생체 설정 OFF
   + "생체 정보가 변경되어 비밀번호로 해제해야 합니다" 안내 (SEC-11)
```

### CRY-14 생체 해제 비활성 조건

다음 중 하나면 생체 해제를 제공하지 않고 마스터 비밀번호를 요구한다.

- 앱 설치 후 첫 해제 (아직 VK가 생체로 래핑되지 않았다)
- 마스터 비밀번호 변경 직후 (CRY-15에서 생체 래핑을 폐기한다)
- 생체 미등록 / 하드웨어 없음 / `BIOMETRIC_STATUS_UNKNOWN`
- 생체 해제 연속 실패로 `BiometricPrompt`가 잠긴 경우

## 마스터 비밀번호 변경

### CRY-15

```
1. 현재 비밀번호로 해제 (CRY-11) → VK 확보
2. salt' = random(16), 파라미터 재캘리브레이션 (CRY-09)
3. MK' = Argon2id(새 비밀번호, salt', ...)
4. wrapped_vk_by_mk' = AES-GCM(MK', VK)
5. vault_meta 를 원자적으로 교체 (임시 파일 쓰기 → rename)
6. wrapped_vk_by_bio 삭제 + 생체 설정 OFF
   → 사용자에게 생체 재등록을 안내 (CRY-12)
7. zeroize(MK')
```

**DB는 재암호화하지 않는다** (VK가 그대로이므로). 5번의 원자적 교체가 중요하다 —
중간에 프로세스가 죽으면 금고를 영구히 열 수 없게 된다.

## 키 수명과 제로화 (CRY-16)

| 키 | 메모리 체류 | 폐기 시점 |
|----|-------------|-----------|
| 비밀번호 입력 바이트 | 수초 | KDF 호출 직후 |
| MK | 수초 | VK 언래핑 직후 |
| VK | 세션 전체 | 잠금 시 (LOCK-04) |
| BioKey | 없음 | Keystore 내부에만 존재 |

규칙:
- 비밀번호·키는 `CharArray` / `ByteArray` 로 다룬다. `String` 금지 (SEC-12).
  Compose `TextField` 가 `String`을 요구하는 지점이 불가피하게 존재한다 —
  해당 지점은 코드에 `// SEC-12 예외:` 주석으로 명시하고 범위를 최소화한다.
- `finally` 블록에서 `fill(0)`. `use {}` 패턴의 헬퍼를 하나 만들어 통일한다.
- **KDF 라이브러리의 결과 객체도 지운다.** argon2kt 의 `Argon2KtResult` 는 MK 를 direct ByteBuffer
  **두 곳**에 담는다 — `rawHash`(원시 해시)와 `encodedOutput`(PHC 문자열, 해시를 base64 로 다시 포함).
  `rawHashAsByteArray()` 로 꺼낸 사본만 지우면 원본 두 개가 GC 전까지 남는다.
  두 버퍼 모두 전체 용량을 0으로 덮어쓴다. 라이브러리의 `wipeDirectBuffer` 는 바이트코드상 public 이지만
  Kotlin `internal` 이라 호출할 수 없으므로 자체 구현을 쓴다. 라이브러리를 교체할 때 이 규칙을 다시 확인한다.
- `CharArray` → `ByteArray`(UTF-8) 변환은 `String` 을 거치지 않는다. 인코더 중간 버퍼도 지운다.
  `CharsetEncoder.encode(CharBuffer)` 는 출력이 넘치면 버퍼를 **재할당하며 이전 버퍼를 지우지 않고 버린다.**
  최대 크기(`maxBytesPerChar × 길이`)로 한 번만 할당해 재할당을 원천 차단한다.
- **수용하는 한계**: `SecretKeySpec` 은 키를 내부에 복제하고, `Cipher` 는 확장된 키 스케줄을 보관한다.
  둘 다 지울 공개 수단이 없다 (리플렉션은 Android hidden API 제한에 걸리고 깨지기 쉽다).
  JVM 한계로 받아들이며 SEC-12 가 "완화 조치"인 이유 중 하나다. 객체 수명을 함수 지역으로 짧게 유지하는 것으로 대응한다.
- 로그·`toString()`·크래시 리포트에 키가 들어가지 않는다 (SEC-10).

## 검토 후 제외한 대안

| 대안 | 제외 이유 |
|------|-----------|
| **필드 단위 추가 암호화** (SQLCipher 위에 비밀 필드를 또 AES-GCM) | DB가 열린 상태에서의 추가 방어는 T-03/T-04 어느 쪽도 실질적으로 개선하지 않는다 (같은 프로세스가 양쪽 키를 다 쥐고 있다). 코드 복잡도와 검색·정렬 제약만 늘어난다. 금고 전체 암호화는 KeePass·1Password 계열의 표준 접근이기도 하다. **단, 목록 화면에서 비밀 필드를 아예 읽지 않는다는 규칙(NFR-02)은 유지한다.** |
| PBKDF2 / scrypt | Argon2id가 GPU·ASIC 대입에 더 강하고 현재 권장안이다. PBKDF2는 반복 횟수를 극단적으로 올려도 메모리-하드 특성이 없다 |
| `MasterKey` + `EncryptedSharedPreferences` (androidx.security-crypto) | 키가 기기 Keystore에 묶여 **기기 교체·초기화 시 복구 불가**. 백업 파일에서 복구해야 하는 제품 요구(SEC-04)와 맞지 않다. 라이브러리도 유지보수 상태가 좋지 않다 |
| 마스터 비밀번호를 직접 SQLCipher 키로 사용 | 비밀번호 변경 시 DB 전체 재암호화 필요, 생체 해제 구현 불가 |
| 검증용 해시(마스터 비밀번호 해시)를 별도 저장 | GCM 태그 검증으로 충분하고, 공격자에게 추가 검증 오라클을 주지 않는다 |
| 실패 N회 시 금고 자동 삭제 | 오조작·아이 장난으로 데이터를 영구히 잃는 피해가 방어 이익보다 크다. 백오프(SEC-09)로 충분 |
| 루팅 탐지 | 우회가 쉽고 오탐이 많다. [01](01-threat-model.md) Out of scope |
