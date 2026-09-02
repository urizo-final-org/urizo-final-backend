package org.urizo.axmodulestudio.backend.coding.service;

import java.util.List;
import java.util.Locale;

/**
 * Refuses a request that is obviously outside what the Coding pipeline may do, before any model is
 * called.
 *
 * <p>This exists to save tokens, not to be accurate. A request that reaches the pipeline costs
 * three model conversations, so a request that was never going to be allowed should not start one.
 * The real verdict is the post-check against the files actually changed.
 *
 * <p>Only the obvious is refused and anything arguable passes. A false refusal is worse than a
 * false pass here: a pass still meets the post-check, while a refusal stops work that was fine.
 * {@code 컬럼} on its own is therefore not listed, because showing a column that already exists is
 * exactly what the demo asks for; only wording that clearly means creating one is.
 */
final class GuardrailRequestPrecheck {

    /** Matched against the lower-cased request, so every phrase here is lower case. */
    private static final List<Refusal> REFUSALS = List.of(
            new Refusal(
                    "CODING_REQUEST_NEEDS_SCHEMA_CHANGE",
                    "데이터베이스 구조 변경은 개발자 작업입니다.",
                    List.of(
                            "테이블 추가", "테이블 생성", "테이블 만들", "테이블 삭제", "테이블 제거",
                            "컬럼 추가", "컬럼 생성", "컬럼 만들", "컬럼 삭제", "컬럼 제거",
                            "스키마 변경", "스키마 수정", "마이그레이션",
                            "인덱스 추가", "인덱스 생성",
                            "create table", "alter table", "drop table",
                            "add column", "drop column")),
            new Refusal(
                    "CODING_REQUEST_NEEDS_INFRASTRUCTURE",
                    "배포와 서버 설정은 개발자 작업입니다.",
                    List.of(
                            "배포", "재배포", "서버 재시작", "포트 변경",
                            "도커", "docker", "compose", "nginx")),
            new Refusal(
                    "CODING_REQUEST_TOUCHES_AUTHENTICATION",
                    "로그인과 비밀 정보는 코딩 요청으로 바꿀 수 없습니다.",
                    List.of(
                            "로그인", "인증 방식", "비밀번호", "패스워드",
                            "api 키", "api key", "시크릿", "secret", "토큰 발급")),
            new Refusal(
                    // A request to loosen the guardrail is not a coding task. The control cannot be
                    // widened from inside the thing it controls.
                    "CODING_REQUEST_WOULD_WIDEN_GUARDRAIL",
                    "울타리 설정은 코딩 요청으로 바꿀 수 없습니다.",
                    List.of(
                            "울타리", "가드레일", "guardrail", "금지 목록",
                            "제한 해제", "제한 풀", "권한 해제")));

    private GuardrailRequestPrecheck() { }

    /** Returns the refusal a request obviously earns, or null when it should proceed. */
    static Refusal refusalFor(String requestText) {
        if (requestText == null || requestText.isBlank()) {
            return null;
        }
        String normalized = requestText.toLowerCase(Locale.ROOT);
        for (Refusal refusal : REFUSALS) {
            for (String phrase : refusal.phrases()) {
                if (normalized.contains(phrase)) {
                    return refusal;
                }
            }
        }
        return null;
    }

    record Refusal(String code, String message, List<String> phrases) { }
}
