-- 대상과 무관하게 모든 자연어 CMS 명령에 걸리는 규칙.
--
-- 한 행만 존재한다. 기본키가 상수라 두 번째 행을 넣을 수 없고 INSERT·DELETE 를 아무에게도
-- 주지 않아 행이 사라지거나 늘어날 수 없다. 비어 있을 수 있는 설정 표는 "규칙 없음" 과
-- "표가 깨짐" 을 같은 상태로 만든다.
--
-- allow_delete 는 삭제 명령 전체를 연다. 삭제는 필드를 하나도 싣지 않아 필드 선택으로는
-- 막을 수 없으므로 별도 값이 필요하다.
--
-- configured 는 "관리자가 한 번이라도 저장했는가" 다. 이 값 하나가 두 상태를 가른다.
--   FALSE: 저장한 적 없음  -> 코드 기본값을 그대로 따른다. 설치 직후 기능이 멈추지 않는다.
--   TRUE : 저장한 적 있음  -> app.natural_cms_field_selection 에 없는 필드는 꺼짐이다.
-- 이 구분이 없으면 첫 실행에서 모든 필드가 닫힌 것으로 읽혀 자연어 CMS 가 통째로 멎는다.
CREATE TABLE app.natural_cms_rule (
    natural_cms_rule_id BOOLEAN PRIMARY KEY DEFAULT TRUE,
    allow_delete BOOLEAN NOT NULL DEFAULT TRUE,
    configured BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_natural_cms_rule_single_row CHECK (natural_cms_rule_id)
);

-- 행은 처음부터 존재한다. 규칙을 읽을 때 없는 행을 다루지 않아도 된다.
INSERT INTO app.natural_cms_rule (natural_cms_rule_id) VALUES (TRUE);

-- ai_workspace 만 값을 바꾼다. 명령을 판정하는 cms_app 은 읽기만 하며,
-- 판정하는 연결이 판정 기준을 고칠 수 없게 한다.
GRANT SELECT, UPDATE ON app.natural_cms_rule TO ai_workspace;
GRANT SELECT ON app.natural_cms_rule TO cms_app;
