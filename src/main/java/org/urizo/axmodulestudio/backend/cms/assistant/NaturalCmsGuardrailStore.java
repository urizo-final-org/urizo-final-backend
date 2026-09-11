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
 * 명령을 판정할 때 읽는 울타리.
 *
 * <p>CMS 데이터를 다루는 {@code cms_app} 연결로 읽는다. 이 연결에는 설정 표의 쓰기 권한이
 * 없다. 판정하는 쪽이 판정 기준을 고칠 수 없어야 울타리가 울타리로 남는다. 값을 바꾸는 것은
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
        Map<String, Boolean> rule = jdbc.query(
                "SELECT allow_delete, configured FROM app.natural_cms_rule",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Boolean> values = new HashMap<>();
                    values.put("allowDelete", rs.getBoolean("allow_delete"));
                    values.put("configured", rs.getBoolean("configured"));
                    return values;
                });
        if (rule == null) {
            return NaturalCmsGuardrail.unconfigured();
        }

        Map<String, Set<String>> allowed = new HashMap<>();
        jdbc.query(
                """
                SELECT resource_type, field_name
                FROM app.natural_cms_field_selection
                WHERE enabled
                ORDER BY resource_type, field_name
                """,
                rs -> {
                    allowed.computeIfAbsent(rs.getString("resource_type"),
                            key -> new LinkedHashSet<>()).add(rs.getString("field_name"));
                });

        Map<String, Set<String>> frozen = new HashMap<>();
        allowed.forEach((resourceType, fields) -> frozen.put(resourceType, Set.copyOf(fields)));
        return new NaturalCmsGuardrail(
                rule.get("configured"), rule.get("allowDelete"), frozen);
    }
}
