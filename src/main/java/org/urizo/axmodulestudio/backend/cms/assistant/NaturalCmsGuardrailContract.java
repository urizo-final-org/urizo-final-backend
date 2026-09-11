package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 자연어 CMS 울타리 설정의 입출력.
 *
 * <p>대상과 필드 목록은 저장하지 않는다. 조회는 Handler가 지금 여는 것을 그대로 싣고 그 위에
 * 저장된 선택을 표시할 뿐이다. 그래서 코드가 필드를 늘리면 화면에 저절로 나타나고, 없앤 필드가
 * 옛 목록에서 계속 제공되지 않는다.
 */
public final class NaturalCmsGuardrailContract {

    private NaturalCmsGuardrailContract() { }

    /** 한 필드의 현재 상태. {@code enabled}는 관리자가 정한 값이다. */
    public record Field(String name, boolean enabled) { }

    /** 한 대상이 지금 여는 동작과 필드. */
    public record Resource(String resourceKey, List<String> operations, List<Field> fields) { }

    /**
     * 설정 화면이 읽는 전부.
     *
     * @param configured 한 번이라도 저장했는가. {@code false}면 아직 코드 기본값을 따른다.
     * @param allowDelete 삭제 명령을 여는가. 대상과 무관한 전역 값이다.
     */
    public record GuardrailView(
            boolean configured,
            boolean allowDelete,
            List<Resource> resources) { }

    /** 저장할 필드 하나. */
    public record FieldSelection(
            @NotBlank @Pattern(regexp = "^(MENU|BOARD|BOARD_POST|CONTENT)$") String resourceKey,
            @NotBlank @Size(max = 64) String fieldName,
            boolean enabled) { }

    /**
     * 저장된 선택을 통째로 바꾼다.
     *
     * <p>켠 것만 보내면 나머지가 "선택된 적 없음"인지 "꺼짐"인지 서버가 알 수 없다. 목록에 있는
     * 필드를 전부 담아 보낸다. 저장이 끝나면 {@code configured}가 켜지고, 그때부터 목록에 없는
     * 필드는 꺼짐으로 읽힌다.
     */
    public record SaveRequest(
            boolean allowDelete,
            @NotNull @Size(max = 200) List<@Valid FieldSelection> fields) { }
}
