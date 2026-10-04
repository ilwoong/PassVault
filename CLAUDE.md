# CLAUDE.md

## 프로젝트

**PassVault** — 비밀번호·보안 메모·카드·신분증을 기기 안에서만 암호화해 보관하는 Android 앱.
Kotlin + Compose + Room/SQLCipher. 로컬 전용(네트워크 권한 없음), 암호화 파일로 수동 백업.

## 스펙이 기준이다

구현의 단일 기준은 [`spec/`](spec/) 폴더다. 코드와 스펙이 어긋나면 **스펙을 먼저 고치고** 코드를 맞춘다.
스펙에 없는 기능을 추가하지 않는다. 범위 밖 항목은 [`spec/00-overview.md`](spec/00-overview.md)의
비범위 표에 제외 이유와 함께 적혀 있다 — "있으면 좋으니까" 넣지 않는다.

| 작업 | 먼저 읽을 문서 |
|------|----------------|
| **무엇이든** | [spec/README.md](spec/README.md) — 인덱스와 문서 규약 |
| 범위·용어가 헷갈릴 때 | [00-overview](spec/00-overview.md) |
| 보안 판단이 필요할 때 | [01-threat-model](spec/01-threat-model.md) — `SEC-*` |
| 키·암호화·생체 코드 | [02-crypto](spec/02-crypto.md) — `CRY-*` |
| DB·엔티티·정책 모델 | [03-data-model](spec/03-data-model.md) — `DM-*` |
| 새 파일을 어디 둘지 | [04-architecture](spec/04-architecture.md) — `ARC-*` |
| 화면 작업 | [05-ui-flows](spec/05-ui-flows.md) — `UX-*` |
| 잠금·세션 작업 | [06-lock-policy](spec/06-lock-policy.md) — `LOCK-*` |
| 백업·복구 작업 | [07-backup](spec/07-backup.md) — `BK-*` |
| 완료 판정 | [08-testing](spec/08-testing.md) — `TST-*` |
| 다음에 뭘 할지 | [09-roadmap](spec/09-roadmap.md) — `M0`~`M9` |
| 보안 요구사항의 구현·검증 근거 | [10-traceability](spec/10-traceability.md) |

## 작업 루프

1. [09-roadmap](spec/09-roadmap.md)에서 **가장 위의 미완료 마일스톤**을 확인한다. 순서를 건너뛰지 않는다.
2. 해당 마일스톤이 참조하는 스펙 문서를 읽는다.
3. 구현한다.
4. 마일스톤의 **검증 기준**을 통과시킨다. "동작하는 것 같다"는 완료가 아니다.
5. 커밋 메시지에 근거 요구사항 ID를 적는다. 예: `feat(crypto): Argon2id 키 유도 (CRY-01, CRY-02)`

스펙에 답이 없으면 추측하지 말고 묻는다. 결정이 나면 스펙에 `TODO(결정필요):` 를 해소하고 커밋한다.

## 이 프로젝트에서 특히 조심할 것

- **비밀을 `String`에 담지 않는다.** `ByteArray`/`CharArray` + 사용 후 제로화 (`SEC-12`, `CRY-16`).
  불가피한 지점은 코드에 `// SEC-12 예외:` 주석으로 범위를 명시한다.
- **데이터를 자동으로 삭제·재생성하는 코드를 쓰지 않는다.** DB 손상 시 `fallbackToDestructiveMigration`,
  해제 실패 시 금고 파기 모두 금지. 사용자 데이터 소실이 가장 큰 피해다 (`DM-12`, `ARC-06`, `SEC-09`).
- **비밀 필드를 목록 화면에서 읽지 않는다.** 정책 배지는 캐시 컬럼으로 해결돼 있다 (`DM-03`).
- **의존성을 늘리지 않는다.** 특히 비밀을 다루는 경로. 네트워크 라이브러리는 추이적으로도 들어오면 안 된다 (`ARC-04`, `NFR-03`).
- **`FLAG_SECURE`를 끄지 않는다.** 디버그 빌드에서도 (`LOCK-06`).

---

아래는 일반 코딩 지침이다. 위의 프로젝트 규칙과 충돌하면 위를 따른다.

# 코딩 지침

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
