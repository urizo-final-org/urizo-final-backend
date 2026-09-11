package org.urizo.axmodulestudio.backend.cms.service;

import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.*;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.*;
import org.urizo.axmodulestudio.backend.cms.entity.*;
import org.urizo.axmodulestudio.backend.cms.repository.*;
import static org.urizo.axmodulestudio.backend.cms.service.CmsServiceException.*;

@Service
@Profile("local-full")
@Transactional(transactionManager = "authJpaTransactionManager")
public class CmsCodeService {
    private final CmsCodeGroupJpaRepository groups;
    private final CmsCodeJpaRepository codes;
    public CmsCodeService(CmsCodeGroupJpaRepository groups, CmsCodeJpaRepository codes) {
        this.groups = groups;
        this.codes = codes;
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public List<CodeGroupView> groups() {
        return groups.findAllByOrderByDisplayOrderAscKeyAsc().stream().map(CmsCodeService::view).toList();
    }
    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public List<CodeView> codes() {
        return codes.findAllByOrderByDisplayOrderAscIdAsc().stream().map(CmsCodeService::view).toList();
    }

    public CodeGroupView createGroup(CodeGroupRequest request) {
        if (groups.existsById(request.key())) throw invalidRequest("이미 사용 중인 코드 그룹입니다.");
        return view(groups.save(new CmsCodeGroupEntity(request.key(), request.label().trim(), request.displayOrder(), request.enabled())));
    }
    public CodeGroupView updateGroup(String key, CodeGroupRequest request) {
        if (!key.equals(request.key())) throw invalidRequest("코드 그룹 키는 변경할 수 없습니다.");
        var group = group(key);
        group.change(request.label().trim(), request.displayOrder(), request.enabled());
        return view(group);
    }
    public CodeView createCode(String groupKey, CodeRequest request) {
        group(groupKey);
        if (codes.existsByGroupKeyAndValue(groupKey, request.value())) throw invalidRequest("그룹에서 이미 사용 중인 코드 값입니다.");
        return view(codes.save(new CmsCodeEntity(groupKey, request.value(), request.label().trim(), request.displayOrder(), request.enabled())));
    }
    public CodeView updateCode(long id, CodeRequest request) {
        var code = code(id);
        if (!code.getValue().equals(request.value())) throw invalidRequest("코드 값은 변경할 수 없습니다.");
        code.change(request.label().trim(), request.displayOrder(), request.enabled());
        return view(code);
    }

    /** 비활성화한 기존 선택은 보존하되 새 선택으로는 사용할 수 없다. */
    public void validateGroup(String key, String previous) {
        if (key == null) return;
        var group = group(key);
        if (!group.isEnabled() && !key.equals(previous)) throw invalidRequest("사용 중인 코드 그룹만 선택할 수 있습니다.");
    }
    public void validateCode(Long id, String groupKey, Long previous) {
        if (id == null) return;
        var code = code(id);
        if (groupKey == null || !groupKey.equals(code.getGroupKey())) throw invalidRequest("게시판에 연결된 그룹의 코드만 선택할 수 있습니다.");
        if ((!code.isEnabled() || !group(groupKey).isEnabled()) && !Objects.equals(id, previous)) {
            throw invalidRequest("사용 중인 코드만 선택할 수 있습니다.");
        }
    }
    private CmsCodeGroupEntity group(String key) {
        return groups.findById(key).orElseThrow(() -> notFound("코드 그룹을 찾을 수 없습니다."));
    }
    private CmsCodeEntity code(long id) {
        return codes.findById(id).orElseThrow(() -> notFound("코드를 찾을 수 없습니다."));
    }
    private static CodeGroupView view(CmsCodeGroupEntity group) {
        return new CodeGroupView(group.getKey(), group.getLabel(), group.getDisplayOrder(), group.isEnabled());
    }
    private static CodeView view(CmsCodeEntity code) {
        return new CodeView(code.getId(), code.getGroupKey(), code.getValue(), code.getLabel(), code.getDisplayOrder(), code.isEnabled());
    }
}
