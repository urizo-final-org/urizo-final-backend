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
 *
 * <p>Every rule names an act, never just its subject. {@code 로그인} and {@code 배포} were once
 * listed bare and refused "로그인 안내 문구를 바꿔줘" and "배포 소식 카드를 추가해 줘" - two requests
 * that only edit text on a page.
 *
 * <p>Naming the act as one string is not enough either, because Korean will put words between the
 * two halves and conjugate the verb. "로그인 기능" missed "로그인할 수 있는 기능", measured on Job
 * 594c6d6a, which is the same request with three syllables inserted. So a rule comes in two shapes:
 * a {@code phrase} that is damning on its own ({@code api 키}), and a {@link Pairing} whose subject
 * and act are matched separately, anywhere in the sentence.
 *
 * <p>Loosening this costs less than it appears, because it is not the control that protects
 * authentication. {@code src/features/auth} and {@code backend/auth} are hard-coded closed in
 * {@link GuardrailPathPolicy}: no guardrail setting opens them, so a request that slips past these
 * phrases still cannot change a line of them.
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
                            // The act, not the word: "배포 소식 카드" is a page of text.
                            // Korean conjugates the verb, so each ending is its own string:
                            // "배포할" does not contain "배포하".
                            "배포해", "배포 해", "배포하", "배포 하", "배포할", "배포 할",
                            "배포를 ", "재배포",
                            "서버 재시작", "서버 재기동", "포트 변경",
                            "도커", "docker", "compose", "nginx")),
            new Refusal(
                    "CODING_REQUEST_TOUCHES_AUTHENTICATION",
                    "로그인과 비밀 정보는 코딩 요청으로 바꿀 수 없습니다.",
                    List.of("api 키", "api key", "시크릿", "secret", "토큰 발급"),
                    // Listing "로그인 기능" as one string missed "로그인할 수 있는 기능", which is
                    // the same request with three syllables in the middle. Korean puts words
                    // between the two halves freely, so the halves are matched separately and
                    // the sentence is refused when both are present. "로그인 안내 문구를 바꿔줘"
                    // still passes: it names no mechanism.
                    List.of(
                            new Pairing("로그인", List.of(
                                    "기능", "방식", "처리", "로직", "인증", "연동", "구현", "붙여")),
                            new Pairing("인증", List.of("기능", "방식", "처리", "로직", "구현")),
                            new Pairing("비밀번호", List.of(
                                    "정책", "규칙", "재설정", "변경", "암호", "검증")),
                            new Pairing("패스워드", List.of(
                                    "정책", "규칙", "재설정", "변경", "암호", "검증")))),
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
            for (Pairing pairing : refusal.pairings()) {
                if (!normalized.contains(pairing.subject())) {
                    continue;
                }
                for (String act : pairing.acts()) {
                    if (normalized.contains(act)) {
                        return refusal;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A subject and the acts that turn it into a forbidden request. Both halves must appear,
     * anywhere in the sentence and in any order, because Korean puts words between them freely:
     * "로그인할 수 있는 기능" and "로그인 기능" are the same ask.
     */
    record Pairing(String subject, List<String> acts) { }

    record Refusal(String code, String message, List<String> phrases, List<Pairing> pairings) {
        Refusal(String code, String message, List<String> phrases) {
            this(code, message, phrases, List.of());
        }
    }
}
