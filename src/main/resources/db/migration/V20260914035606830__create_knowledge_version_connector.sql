-- AXMS-AI02-023: 한 Knowledge Version이 여러 원천 커넥터를 고정한다.
-- knowledge_version.connector_version_id는 그대로 두고 항상 BASE를 가리킨다 — 기존 조회·
-- 원천 변경 감시·이미 만들어진 버전이 그 컬럼만 보고 동작하므로 옮기지 않는다.
-- 이 표는 그 위에 OVERLAY(문서를 만들지 않고 필드만 공급하는 원천)를 더한다.
CREATE TABLE app.knowledge_version_connector (
    knowledge_version_id UUID NOT NULL
        REFERENCES app.knowledge_version(knowledge_version_id) ON DELETE CASCADE,
    connector_version_id UUID NOT NULL
        REFERENCES app.connector_version(connector_version_id),
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY (knowledge_version_id, connector_version_id),
    CONSTRAINT knowledge_version_connector_role_check CHECK (role IN ('BASE', 'OVERLAY'))
);

-- BASE는 버전당 하나다. 둘이면 어느 원천이 문서 집합을 만드는지 정할 수 없다.
CREATE UNIQUE INDEX idx_knowledge_version_connector_base
    ON app.knowledge_version_connector (knowledge_version_id)
    WHERE role = 'BASE';

GRANT SELECT, INSERT ON app.knowledge_version_connector TO cms_app;
