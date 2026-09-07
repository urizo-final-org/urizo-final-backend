package org.urizo.axmodulestudio.backend.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.repository.ProductStore;

/**
 * 자료 갱신 요청의 서비스 경계.
 *
 * <p>지키는 것은 둘이다 — **멱등 Key로 감싸지 않는다**(중복 방지가 저장소의 유니크 인덱스에
 * 있다), 그리고 **요청자를 본문이 아니라 세션에서 받는다**.
 */
class ActivationRequestServiceTest {

    private static final UUID TRACE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID KB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final AuthenticatedActor GENERAL = new AuthenticatedActor(
            UUID.fromString("22222222-2222-4222-8222-222222222222"), "김일반", AdminRole.GENERAL_ADMIN);

    private ProductStore store;
    private ProductService service;

    @BeforeEach
    void setUp() {
        store = mock(ProductStore.class);
        service = new ProductService(store);
    }

    private static ProductApiContract.ActivationRequestResponse response() {
        return new ProductApiContract.ActivationRequestResponse(
                "1.0", TRACE, UUID.randomUUID(), KB, null, "답이 낡았습니다", "OPEN",
                GENERAL.actorId(), GENERAL.name(), Instant.EPOCH);
    }

    @Test
    void aRequestIsNotWrappedInTheIdempotencyLedger() {
        ProductApiContract.CreateActivationRequestRequest body =
                new ProductApiContract.CreateActivationRequestRequest("1.0", null, "답이 낡았습니다");
        when(store.createActivationRequest(eq(KB), eq(TRACE), eq(GENERAL), eq(body)))
                .thenReturn(response());

        assertThat(service.createActivationRequest(KB, TRACE, GENERAL, body).status())
                .isEqualTo("OPEN");
        // 중복 방지는 "같은 사람 · 같은 대상 · 열린 요청" 유니크 인덱스가 한다. 멱등 원장에
        // 조회 성격의 행을 쌓지 않는다 — 공개 질의를 감싸지 않은 것과 같은 이유다.
        verify(store, never()).idempotent(any(), any(), any(), any(int.class), any(), any());
    }

    @Test
    void theRequesterComesFromTheSessionNotTheBody() {
        ProductApiContract.CreateActivationRequestRequest body =
                new ProductApiContract.CreateActivationRequestRequest("1.0", null, null);
        when(store.createActivationRequest(any(), any(), any(), any())).thenReturn(response());

        service.createActivationRequest(KB, TRACE, GENERAL, body);

        // 요청자는 본문에 없다 — 계약에 자리 자체가 없어야 위조가 불가능하다.
        assertThat(ProductApiContract.CreateActivationRequestRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("schemaVersion", "knowledgeVersionId", "reason");
        verify(store).createActivationRequest(KB, TRACE, GENERAL, body);
    }

    @Test
    void theListCarriesOnlyOpenRequests() {
        when(store.listOpenActivationRequests(KB, TRACE)).thenReturn(List.of(response()));

        ProductApiContract.ActivationRequestListResponse list =
                service.listOpenActivationRequests(KB, TRACE);

        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).status()).isEqualTo("OPEN");
        assertThat(list.traceId()).isEqualTo(TRACE);
    }
}
