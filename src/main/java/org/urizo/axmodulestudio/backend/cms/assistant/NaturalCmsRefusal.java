package org.urizo.axmodulestudio.backend.cms.assistant;

/**
 * 가드레일이 막은 이유의 분류.
 *
 * <p>서버는 분류만 싣고 문구는 화면이 만든다. 서버 문장을 그대로 보내면 내부 키가 그대로
 * 새어 나가고({@code accepts these operations only: CREATE, UPDATE}) 화면의 한글 라벨과도
 * 말이 어긋난다. Coding 사전검사가 거절 코드와 한글 문구를 쌍으로 두는 것과 같은 방식이다.
 *
 * <p>{@code CMS_COMMAND_INVALID}와 나누는 기준은 "설정을 바꾸면 통과할 수 있는가"다.
 * 모양이 틀린 명령은 설정과 무관하게 잘못됐지만, 아래는 관리자가 켜면 통과한다.
 * 그 차이를 화면이 알아야 "요청을 바꾸세요"와 "관리자에게 문의하세요"를 가려 말할 수 있다.
 */
public enum NaturalCmsRefusal {

    /** 코드는 열었지만 관리자가 그 대상에서 닫아 둔 명령 종류다. */
    OPERATION_NOT_ALLOWED("CMS_OPERATION_NOT_ALLOWED");

    private final String code;

    NaturalCmsRefusal(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
