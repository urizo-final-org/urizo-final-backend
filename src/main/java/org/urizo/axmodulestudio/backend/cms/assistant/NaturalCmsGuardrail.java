package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 관리자가 정한 자연어 CMS 가드레일 한 벌.
 *
 * <p>이 값은 Handler가 여는 것을 넓히지 못한다. 언제나 교집합으로만 동작하므로
 * 설정으로 열 수 있는 것은 코드가 이미 연 것뿐이다.
 *
 * <p>{@code configured}가 저장 전후를 가른다. 저장한 적이 없으면 코드 기본값을 그대로
 * 따르고, 저장한 뒤부터 목록에 없는 동작은 꺼짐이다. 이 구분이 없으면 설치 직후
 * 모든 동작이 닫힌 것으로 읽혀 자연어 CMS가 통째로 멎는다.
 */
record NaturalCmsGuardrail(
        boolean configured,
        Map<String, Set<String>> allowedOperations) {

    /** 동작 선택의 저장 단위. 게시물은 계약상 BOARD지만 동작을 따로 연다. */
    static final String MENU = "MENU";
    static final String BOARD = "BOARD";
    static final String BOARD_POST = "BOARD_POST";
    static final String CONTENT = "CONTENT";

    /**
     * 이 가드레일이 관리하는 대상.
     *
     * <p>여기 없는 대상은 설정을 저장한 뒤에도 코드가 연 그대로 둔다. 관리 대상이 아닌 것을
     * "선택된 적 없음"으로 읽으면 저장 한 번에 그 대상이 통째로 닫힌다. TEMPLATE이 그 경우이고,
     * 저장 표의 CHECK도 같은 넷으로 닫혀 있다.
     */
    static final Set<String> MANAGED = Set.of(MENU, BOARD, BOARD_POST, CONTENT);

    NaturalCmsGuardrail {
        Objects.requireNonNull(allowedOperations, "allowedOperations is required");
        allowedOperations = Map.copyOf(allowedOperations);
    }

    /** 아직 아무것도 저장하지 않은 상태. 코드 기본값을 그대로 쓴다. */
    static NaturalCmsGuardrail unconfigured() {
        return new NaturalCmsGuardrail(false, Map.of());
    }

    /**
     * 이 대상에 열린 명령 종류.
     *
     * <p>저장 전에는 코드가 연 그대로다. 저장 뒤에는 목록에 있으면서 코드도 연 것만 남는다.
     * 코드가 나중에 새 동작을 열어도 관리자가 켜기 전까지는 닫혀 있다.
     */
    Set<String> operations(String resourceKey, Set<String> opened) {
        if (!configured || !MANAGED.contains(resourceKey)) {
            return opened;
        }
        Set<String> chosen = allowedOperations.getOrDefault(resourceKey, Set.of());
        Set<String> allowed = new LinkedHashSet<>();
        for (String name : opened) {
            if (chosen.contains(name)) {
                allowed.add(name);
            }
        }
        return Set.copyOf(allowed);
    }
}
