package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 울타리 설정을 읽고 바꾼다.
 *
 * <p>{@code ai_workspace} 연결을 쓴다. 명령을 판정하는 {@code cms_app} 연결에는 이 표의 쓰기
 * 권한이 없어, 판정하는 쪽이 판정 기준을 고칠 수 없다. 읽기는
 * {@link NaturalCmsGuardrailStore}와 같은 질의를 공유한다.
 */
@Service
@Profile("dev & local-full")
public class NaturalCmsGuardrailAdminService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NaturalCmsResourceService resources;

    NaturalCmsGuardrailAdminService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            NaturalCmsResourceService resources) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.resources = resources;
    }

    /** 화면이 그릴 현재 상태. 목록은 Handler가 여는 것, 체크는 저장된 선택이다. */
    public NaturalCmsGuardrailContract.GuardrailView view() {
        return view(NaturalCmsGuardrailStore.read(jdbc));
    }

    /**
     * 선택을 통째로 바꾼다.
     *
     * <p>보낸 필드 중 지금 코드가 열지 않는 것은 버린다. 저장에 성공하면 {@code configured}가
     * 켜지고, 그때부터 목록에 없는 필드는 꺼짐으로 읽힌다.
     */
    public NaturalCmsGuardrailContract.GuardrailView save(
            NaturalCmsGuardrailContract.SaveRequest request) {
        NaturalCmsGuardrail stored = transactions.execute(status -> {
            jdbc.update("DELETE FROM app.natural_cms_field_selection");
            for (NaturalCmsGuardrailContract.FieldSelection selection : request.fields()) {
                if (!opened(selection.resourceKey(), selection.fieldName())) {
                    continue;
                }
                jdbc.update("""
                        INSERT INTO app.natural_cms_field_selection (
                            natural_cms_field_selection_id, resource_type, field_name, enabled)
                        VALUES (?, ?, ?, ?)
                        """,
                        UUID.randomUUID(), selection.resourceKey(),
                        selection.fieldName(), selection.enabled());
            }
            int updated = jdbc.update("""
                    UPDATE app.natural_cms_rule
                    SET allow_delete = ?, configured = TRUE, updated_at = CURRENT_TIMESTAMP
                    WHERE natural_cms_rule_id
                    """, request.allowDelete());
            if (updated != 1) {
                throw new NaturalCmsException(
                        "NATURAL_CMS_GUARDRAIL_UNAVAILABLE",
                        "Natural CMS guardrail rule row is missing.",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }
            return NaturalCmsGuardrailStore.read(jdbc);
        });
        if (stored == null) {
            throw new NaturalCmsException(
                    "NATURAL_CMS_GUARDRAIL_UNAVAILABLE",
                    "Natural CMS guardrail could not be saved.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        return view(stored);
    }

    private boolean opened(String resourceKey, String fieldName) {
        for (NaturalCmsResourceService.OpenResource open : resources.openResources()) {
            if (open.resourceKey().equals(resourceKey)) {
                return open.fields().contains(fieldName);
            }
        }
        return false;
    }

    private NaturalCmsGuardrailContract.GuardrailView view(NaturalCmsGuardrail guardrail) {
        List<NaturalCmsGuardrailContract.Resource> resourceViews = new ArrayList<>();
        for (NaturalCmsResourceService.OpenResource open : resources.openResources()) {
            Set<String> allowed = guardrail.fields(open.resourceKey(), open.fields());
            List<NaturalCmsGuardrailContract.Field> fields = new ArrayList<>();
            for (String name : new LinkedHashSet<>(sorted(open.fields()))) {
                fields.add(new NaturalCmsGuardrailContract.Field(
                        name, allowed.contains(name)));
            }
            resourceViews.add(new NaturalCmsGuardrailContract.Resource(
                    open.resourceKey(),
                    sorted(guardrail.operations(open.operations())),
                    List.copyOf(fields)));
        }
        return new NaturalCmsGuardrailContract.GuardrailView(
                guardrail.configured(), guardrail.allowDelete(), List.copyOf(resourceViews));
    }

    private static List<String> sorted(Set<String> values) {
        List<String> ordered = new ArrayList<>(values);
        ordered.sort(String::compareTo);
        return List.copyOf(ordered);
    }
}
