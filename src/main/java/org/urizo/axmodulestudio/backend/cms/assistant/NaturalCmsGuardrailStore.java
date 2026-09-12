package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 명령을 판정할 때 읽는 가드레일.
 *
 * <p>CMS 데이터를 다루는 {@code cms_app} 연결로 읽는다. 이 연결에는 설정 표의 쓰기 권한이
 * 없다. 판정하는 쪽이 판정 기준을 고칠 수 없어야 가드레일이 가드레일로 남는다. 값을 바꾸는 것은
 * {@link NaturalCmsGuardrailAdminService}가 {@code ai_workspace} 연결로 한다.
 */
@Service
@Profile("local-full")
public class NaturalCmsGuardrailStore {

    private final JdbcTemplate cmsJdbc;

    NaturalCmsGuardrailStore(@Qualifier("productJdbcTemplate") JdbcTemplate cmsJdbc) {
        this.cmsJdbc = cmsJdbc;
    }

    /**
     * 지금 적용할 울타리.
     *
     * <p>규칙 행은 Migration이 만들어 두므로 없을 수 없다. 그래도 없으면 저장 전 상태로 읽어
     * 코드 기본값을 따른다. 설정을 못 읽었다는 이유로 CMS 관리가 멎는 편이 더 나쁘다.
     */
    @Transactional(transactionManager = "productTransactionManager", readOnly = true)
    public NaturalCmsGuardrail current() {
        return read(cmsJdbc);
    }

    static NaturalCmsGuardrail read(JdbcTemplate jdbc) {
        // 규칙 행은 configured 하나 때문에 남아 있다. allow_delete 는 동작 표로 옮겼고
        // 더 읽지 않지만 forward-only 원칙에 따라 컬럼은 지우지 않았다.
        Boolean configured = jdbc.query(
                "SELECT configured FROM app.natural_cms_rule",
                rs -> rs.next() ? rs.getBoolean("configured") : null);
        if (configured == null) {
            return NaturalCmsGuardrail.unconfigured();
        }

        Map<String, Set<String>> allowed = new HashMap<>();
        jdbc.query(
                """
                SELECT resource_type, operation
                FROM app.natural_cms_operation_selection
                WHERE enabled
                ORDER BY resource_type, operation
                """,
                rs -> {
                    allowed.computeIfAbsent(rs.getString("resource_type"),
                            key -> new LinkedHashSet<>()).add(rs.getString("operation"));
                });

        Map<String, Set<String>> frozen = new HashMap<>();
        allowed.forEach((resourceType, operations) ->
                frozen.put(resourceType, Set.copyOf(operations)));
        return new NaturalCmsGuardrail(configured, frozen);
    }
}
