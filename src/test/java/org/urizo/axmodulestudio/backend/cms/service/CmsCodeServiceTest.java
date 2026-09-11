package org.urizo.axmodulestudio.backend.cms.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.*;
import org.urizo.axmodulestudio.backend.cms.entity.*;
import org.urizo.axmodulestudio.backend.cms.repository.*;

class CmsCodeServiceTest {
    private final CmsCodeGroupJpaRepository groups = mock(CmsCodeGroupJpaRepository.class);
    private final CmsCodeJpaRepository codes = mock(CmsCodeJpaRepository.class);
    private final CmsCodeService service = new CmsCodeService(groups, codes);

    @Test void inactiveExistingCodeCanRemainWhileTextIsEdited() {
        when(codes.findById(1L)).thenReturn(Optional.of(new CmsCodeEntity("REGION", "SEOUL", "서울", 0, false)));
        assertThatCode(() -> service.validateCode(1L, "REGION", 1L)).doesNotThrowAnyException();
    }
    @Test void inactiveCodeCannotBeSelectedAsANewValue() {
        when(codes.findById(1L)).thenReturn(Optional.of(new CmsCodeEntity("REGION", "SEOUL", "서울", 0, false)));
        assertThatThrownBy(() -> service.validateCode(1L, "REGION", null)).isInstanceOf(CmsServiceException.class);
    }
    @Test void categoryCodeCannotBeUsedAsARegion() {
        when(codes.findById(1L)).thenReturn(Optional.of(new CmsCodeEntity("CATEGORY", "NEWS", "소식", 0, true)));
        assertThatThrownBy(() -> service.validateCode(1L, "REGION", null)).isInstanceOf(CmsServiceException.class);
    }
    @Test void activeCodeInDisabledGroupCannotBeNewlySelected() {
        when(codes.findById(1L)).thenReturn(Optional.of(new CmsCodeEntity("REGION", "SEOUL", "서울", 0, true)));
        when(groups.findById("REGION")).thenReturn(Optional.of(new CmsCodeGroupEntity("REGION", "지역", 0, false)));
        assertThatThrownBy(() -> service.validateCode(1L, "REGION", null)).isInstanceOf(CmsServiceException.class);
        assertThatCode(() -> service.validateCode(1L, "REGION", 1L)).doesNotThrowAnyException();
    }
    @Test void codesAreDisabledInsteadOfRenamingTheirStableValue() {
        when(codes.findById(1L)).thenReturn(Optional.of(new CmsCodeEntity("REGION", "SEOUL", "서울", 0, true)));
        assertThatThrownBy(() -> service.updateCode(1, new CodeRequest("BUSAN", "부산", 0, true)))
                .isInstanceOf(CmsServiceException.class).hasMessageContaining("변경할 수 없습니다");
    }
    @Test void groupKeyIsImmutable() {
        assertThatThrownBy(() -> service.updateGroup("REGION", new CodeGroupRequest("OTHER", "지역", 0, true)))
                .isInstanceOf(CmsServiceException.class);
    }
    @Test void duplicateCodeValueIsRejectedWithinGroup() {
        when(groups.findById("REGION")).thenReturn(Optional.of(new CmsCodeGroupEntity("REGION", "지역", 0, true)));
        when(codes.existsByGroupKeyAndValue("REGION", "SEOUL")).thenReturn(true);
        assertThatThrownBy(() -> service.createCode("REGION", new CodeRequest("SEOUL", "서울", 0, true)))
                .isInstanceOf(CmsServiceException.class);
    }
}
