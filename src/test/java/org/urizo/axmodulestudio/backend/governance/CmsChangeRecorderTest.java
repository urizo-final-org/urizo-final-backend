package org.urizo.axmodulestudio.backend.governance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.cms.controller.CmsAdminController;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.ArticleRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

class CmsChangeRecorderTest {
    final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final AuthenticatedActor actor = new AuthenticatedActor(id, "관리자", AdminRole.GENERAL_ADMIN);

    @Test
    void recordsOnlyServerActorAndBoundedMetadata() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var recorder = new CmsChangeRecorder(jdbc, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        recorder.record(actor, "CONTENT", "1", "UPDATE", "a".repeat(300));
        verify(jdbc).update(contains("INSERT INTO app.cms_change_history"), any(UUID.class), eq("CONTENT"), eq("1"), eq("UPDATE"), eq("a".repeat(240)), eq(id), eq("관리자"), eq("GENERAL_ADMIN"), any());
        assertThatThrownBy(() -> recorder.record(actor, "SITE", "1", "UPDATE", "title")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder.record(new AuthenticatedActor(id, "user", AdminRole.GENERAL_USER), "CONTENT", "1", "UPDATE", "title")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("deprecation")
    void directChangeAndHistoryShareTransactionAndRecorderFailureRollsBack() {
        CmsService cms = mock(CmsService.class);
        AuthService auth = mock(AuthService.class);
        CmsChangeRecorder recorder = mock(CmsChangeRecorder.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(auth.loadActor(id)).thenReturn(actor);
        var content = new ContentView(1, id, "관리자", "title", "body", Instant.EPOCH, Instant.EPOCH);
        when(cms.createContent(id, "title", "body")).thenReturn(content);
        ProxyFactory factory = new ProxyFactory(new CmsAdminController(cms, auth, recorder));
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        var controller = (CmsAdminController) factory.getProxy();
        var authentication = new UsernamePasswordAuthenticationToken(id.toString(), "", java.util.List.of());
        assertThat(controller.createContent(authentication, new ArticleRequest("title", "body"))).isEqualTo(content);
        var order = inOrder(cms, recorder, tx);
        order.verify(cms).createContent(id, "title", "body");
        order.verify(recorder).record(actor, "CONTENT", "1", "CREATE", "title");
        order.verify(tx).commit(status);
        doThrow(new DataAccessResourceFailureException("history failed")).when(recorder).record(actor, "CONTENT", "1", "CREATE", "title");
        assertThatThrownBy(() -> controller.createContent(authentication, new ArticleRequest("title", "body"))).isInstanceOf(DataAccessResourceFailureException.class);
        verify(tx).rollback(status);
    }

    @Test
    void allThirteenDirectResourceMutationsHaveTransactionBoundary() {
        var methods = java.util.Arrays.stream(CmsAdminController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Transactional.class)).toList();
        assertThat(methods).hasSize(13).allSatisfy(method -> {
            assertThat(method.getAnnotation(Transactional.class).transactionManager()).isEqualTo("authJpaTransactionManager");
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        });
    }
}
