package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설정 화면이 읽고 쓰는 경로.
 *
 * <p>여기서 고정하는 것은 두 가지다. 화면이 그릴 목록은 저장된 선택이 아니라 Handler가 지금
 * 여는 것에서 나온다는 것, 그리고 저장이 코드가 열지 않은 동작을 받아들이지 않는다는 것이다.
 */
class NaturalCmsGuardrailAdminServiceTest {

    private static NaturalCmsResourceService openResources() {
        NaturalCmsResourceService resources = mock(NaturalCmsResourceService.class);
        when(resources.openResources()).thenReturn(List.of(
                new NaturalCmsResourceService.OpenResource(
                        NaturalCmsGuardrail.MENU,
                        Set.of("CREATE", "UPDATE", "DELETE"),
                        Set.of("name", "path"),
                        Set.of(NaturalCmsGuardrail.CONTENT, "TEMPLATE")),
                new NaturalCmsResourceService.OpenResource(
                        NaturalCmsGuardrail.CONTENT,
                        Set.of("CREATE", "UPDATE", "DELETE"),
                        Set.of("title", "body"),
                        Set.of(NaturalCmsGuardrail.MENU, "TEMPLATE"))));
        return resources;
    }

    /** 트랜잭션 껍데기만 벗긴다. 이 테스트가 보는 것은 안에서 무엇을 쓰느냐다. */
    @SuppressWarnings("unchecked")
    private static TransactionTemplate directTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
        return transactions;
    }

    /** 규칙 행이 없으면 저장 전 상태로 읽는다. 설정을 못 읽었다고 CMS 관리가 멎으면 안 된다. */
    private static JdbcTemplate emptyDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class)))
                .thenReturn(null);
        return jdbc;
    }

    @Test
    void drawsTheListFromWhatTheHandlersOpenNotFromStoredChoices() {
        NaturalCmsGuardrailAdminService service = new NaturalCmsGuardrailAdminService(
                emptyDatabase(), directTransactions(), openResources());

        NaturalCmsGuardrailContract.GuardrailView view = service.view();

        assertThat(view.configured()).isFalse();
        assertThat(view.resources())
                .extracting(NaturalCmsGuardrailContract.Resource::resourceKey)
                .containsExactly(NaturalCmsGuardrail.MENU, NaturalCmsGuardrail.CONTENT);
        // 저장 전에는 코드가 연 것이 전부 켜진 것으로 보인다. 그것이 지금 동작이다.
        assertThat(view.resources().get(0).operations())
                .extracting(NaturalCmsGuardrailContract.Operation::name)
                .containsExactly("CREATE", "DELETE", "UPDATE");
        assertThat(view.resources().get(0).operations())
                .allMatch(NaturalCmsGuardrailContract.Operation::enabled);
        // 필드는 이름만 싣는다. 정하는 단위가 아니라 그 대상이 무엇을 다루는지 알려 주는 표시다.
        assertThat(view.resources().get(0).fields()).containsExactly("name", "path");
    }

    @Test
    void refusesToStoreAnOperationTheHandlerDoesNotOpen() {
        JdbcTemplate jdbc = emptyDatabase();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object.class))).thenReturn(1);
        when(jdbc.update(anyString())).thenReturn(1);
        NaturalCmsGuardrailAdminService service = new NaturalCmsGuardrailAdminService(
                jdbc, directTransactions(), openResources());

        // PUBLISH 는 어떤 Handler 도 열지 않는다. 계약 검증을 지나와도 저장되면 안 된다.
        service.save(new NaturalCmsGuardrailContract.SaveRequest(List.of(
                new NaturalCmsGuardrailContract.OperationSelection(
                        NaturalCmsGuardrail.MENU, "UPDATE", true),
                new NaturalCmsGuardrailContract.OperationSelection(
                        NaturalCmsGuardrail.MENU, "PUBLISH", true))));

        verify(jdbc, never()).update(
                org.mockito.ArgumentMatchers.contains(
                        "INSERT INTO app.natural_cms_operation_selection"),
                any(), any(), eq("PUBLISH"), any());
    }

    @Test
    void clearsTheWholeChoiceBeforeWritingSoUncheckedOperationsDoNotSurvive() {
        JdbcTemplate jdbc = emptyDatabase();
        when(jdbc.update(anyString())).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object.class))).thenReturn(1);
        NaturalCmsGuardrailAdminService service = new NaturalCmsGuardrailAdminService(
                jdbc, directTransactions(), openResources());

        service.save(new NaturalCmsGuardrailContract.SaveRequest(List.of(
                new NaturalCmsGuardrailContract.OperationSelection(
                        NaturalCmsGuardrail.MENU, "UPDATE", true))));

        verify(jdbc).update("DELETE FROM app.natural_cms_operation_selection");
    }
}
