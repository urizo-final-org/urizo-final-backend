-- 일반 관리자가 최고 관리자에게 "자료를 갱신해 달라"를 전달하는 경로.
--
-- 빌드 후 APPROVAL_PENDING 정지가 최고 관리자 쪽 알림을 대신하지만, 그 반대 방향
-- (권한 없는 관리자가 문제를 발견했을 때)은 화면에 경로가 없어 사람이 말로 옮겨야 했다.
--
-- 요청은 지시가 아니라 표시다. 최고 관리자를 강제하지 않으며, 해당 지식 베이스가
-- 활성화·롤백되면 애플리케이션이 열린 요청을 닫는다(별도 처리 엔드포인트를 두지 않는다).

CREATE TABLE app.knowledge_activation_request (
    request_id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL REFERENCES app.knowledge_base(knowledge_base_id),
    -- 특정 버전을 지목한 요청과 "새로 만들어 달라"는 요청을 한 테이블로 받는다.
    -- 후자는 아직 대상 버전이 없으므로 NULL이다.
    knowledge_version_id UUID REFERENCES app.knowledge_version(knowledge_version_id),
    reason VARCHAR(500),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    requested_by UUID NOT NULL,
    requested_by_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_knowledge_activation_request_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_knowledge_activation_request_resolved
        CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

-- 같은 사람이 같은 대상으로 열린 요청을 쌓지 못하게 한다. 재촉은 새 행이 아니라
-- 이미 열려 있는 행으로 충분하고, 목록이 중복으로 길어지면 읽히지 않는다.
CREATE UNIQUE INDEX uq_knowledge_activation_request_open
    ON app.knowledge_activation_request (knowledge_base_id, requested_by, COALESCE(knowledge_version_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'OPEN';

-- 목록은 언제나 "이 지식 베이스의 열린 요청"으로 읽는다.
CREATE INDEX idx_knowledge_activation_request_open
    ON app.knowledge_activation_request (knowledge_base_id, created_at DESC)
    WHERE status = 'OPEN';

GRANT SELECT, INSERT, UPDATE ON app.knowledge_activation_request TO cms_app;
