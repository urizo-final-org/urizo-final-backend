package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 자연어 CMS 가드레일 설정의 입출력.
 *
 * <p>대상과 동작 목록은 저장하지 않는다. 조회는 Handler가 지금 여는 것을 그대로 싣고 그 위에
 * 저장된 선택을 표시할 뿐이다. 그래서 코드가 동작을 늘리면 화면에 저절로 나타나고, 없앤 동작이
 * 옛 목록에서 계속 제공되지 않는다.
 *
 * <p>필드는 이름만 싣는다. 관리자가 정하는 단위가 아니라 그 대상이 무엇을 다루는지 알려 주는
 * 표시다. 필드 하나하나를 켜고 끄는 것은 판단할 근거가 없어 동작 단위로 올렸다.
 */
public final class NaturalCmsGuardrailContract {

    private NaturalCmsGuardrailContract() { }

    /** 한 동작의 현재 상태. {@code enabled}는 관리자가 정한 값이다. */
    public record Operation(String name, boolean enabled) { }

    /** 한 대상이 지금 여는 동작과, 그 대상이 다루는 필드 이름. */
    public record Resource(String resourceKey, List<Operation> operations, List<String> fields) { }

    /**
     * 설정 화면이 읽는 전부.
     *
     * @param configured 한 번이라도 저장했는가. {@code false}면 아직 코드 기본값을 따른다.
     */
    public record GuardrailView(boolean configured, List<Resource> resources) { }

    /** 저장할 동작 하나. */
    public record OperationSelection(
            @NotBlank @Pattern(regexp = "^(MENU|BOARD|BOARD_POST|CONTENT)$") String resourceKey,
            @NotBlank @Pattern(regexp = "^(CREATE|UPDATE|DELETE)$") String operation,
            boolean enabled) { }

    /**
     * 저장된 선택을 통째로 바꾼다.
     *
     * <p>켠 것만 보내면 나머지가 "선택된 적 없음"인지 "꺼짐"인지 서버가 알 수 없다. 목록에 있는
     * 동작을 전부 담아 보낸다. 저장이 끝나면 {@code configured}가 켜지고, 그때부터 목록에 없는
     * 동작은 꺼짐으로 읽힌다.
     *
     * <p>상한은 대상 넷 × 동작 셋이다. 화면이 여는 것보다 많이 보낼 이유가 없다.
     */
    public record SaveRequest(
            @NotNull @Size(max = 12) List<@Valid OperationSelection> operations) { }
}
