-- 관리자가 정한, 자연어 CMS가 대상마다 실행할 수 있는 동작.
--
-- 설정 단위를 필드에서 대상별 동작으로 올린다. 필드 하나하나는 관리자가 판단할 근거가 없었다.
-- `메뉴 주소는 AI가 못 바꾸게`를 실제로 원하는 관리자는 드물고, `게시판은 만들기만, 지우지는
-- 못하게`는 자주 원한다.
--
-- 20260911052249613 이 만든 app.natural_cms_field_selection 은 그래서 쓰이지 않게 됐다.
-- 그 리비전은 이미 적용돼 파일을 고칠 수 없으므로(Flyway 체크섬) 여기서 지운다.
-- 적용된 행은 없었다.
--
-- 행이 없다는 것만으로는 꺼짐이 아니다. app.natural_cms_rule.configured 가 FALSE 인 동안에는
-- 코드 기본값을 그대로 따른다. 한 번이라도 저장한 뒤부터 없는 행이 꺼짐이 되며, 그때부터
-- Handler가 새로 연 동작은 관리자가 켜기 전까지 닫혀 있다.
--
-- 게시물은 계약상 BOARD 안에서 id 모양으로 갈리지만 관리자에게는 게시판과 다른 대상이라
-- BOARD_POST 로 구분해 저장한다. TEMPLATE 은 자연어 어시스턴트가 아직 열려 있지 않아 넣지
-- 않는다. 관리 대상이 아닌 것을 `선택된 적 없음`으로 읽으면 저장 한 번에 그 대상이 통째로 닫힌다.
CREATE TABLE app.natural_cms_operation_selection (
    natural_cms_operation_selection_id UUID PRIMARY KEY,
    resource_type VARCHAR(16) NOT NULL,
    operation VARCHAR(8) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_natural_cms_operation_selection UNIQUE (resource_type, operation),
    CONSTRAINT ck_natural_cms_operation_selection_resource_type CHECK (
        resource_type IN ('MENU', 'BOARD', 'BOARD_POST', 'CONTENT')),
    -- 명령서의 operation 값과 같은 셋만 담는다.
    CONSTRAINT ck_natural_cms_operation_selection_operation CHECK (
        operation IN ('CREATE', 'UPDATE', 'DELETE'))
);

CREATE INDEX idx_natural_cms_operation_selection_enabled
    ON app.natural_cms_operation_selection (resource_type, operation)
    WHERE enabled;

-- ai_workspace 가 관리자 API를 담당한다. cms_app 은 명령을 판정할 때 읽기만 하며,
-- 판정하는 연결이 판정 기준을 고칠 수 없게 쓰기를 주지 않는다.
GRANT SELECT, INSERT, UPDATE, DELETE ON app.natural_cms_operation_selection TO ai_workspace;
GRANT SELECT ON app.natural_cms_operation_selection TO cms_app;

-- 저장한 적이 있는 설치라면 기존 전역 allow_delete 를 대상별 행으로 옮긴다.
-- 등록·수정은 끌 수단이 없었으므로 켜진 상태로 옮긴다.
--
-- configured 가 FALSE 면 한 행도 넣지 않는다. 넣으면 `아직 정하지 않음`이 `이렇게 정함`으로
-- 바뀌어, 설치 직후 상태가 관리자가 저장한 것처럼 읽힌다.
INSERT INTO app.natural_cms_operation_selection (
    natural_cms_operation_selection_id, resource_type, operation, enabled)
SELECT gen_random_uuid(), resource.type, op.name,
       CASE WHEN op.name = 'DELETE' THEN rule.allow_delete ELSE TRUE END
FROM app.natural_cms_rule rule
CROSS JOIN (VALUES ('MENU'), ('BOARD'), ('BOARD_POST'), ('CONTENT')) AS resource(type)
CROSS JOIN (VALUES ('CREATE'), ('UPDATE'), ('DELETE')) AS op(name)
WHERE rule.configured;

-- 필드 선택은 더 이상 읽지 않는다. app.natural_cms_rule 은 configured 를 위해 남는다.
-- allow_delete 컬럼은 위에서 옮긴 뒤 읽지 않지만 forward-only 원칙에 따라 지우지 않는다.
DROP TABLE app.natural_cms_field_selection;
