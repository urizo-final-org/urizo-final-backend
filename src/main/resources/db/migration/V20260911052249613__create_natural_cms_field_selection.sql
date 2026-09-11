-- 관리자가 정한, 자연어 CMS가 명령에 실을 수 있는 필드.
--
-- 필드 이름은 저장하지 않고 선택만 저장한다. 쓸 수 있는 필드는 Handler가 여는 것이 기준이고
-- 이 표는 그 위에 교집합으로 얹힌다. 나중에 Handler가 필드를 늘리거나 줄여도 옛 목록이
-- 그대로 제공되는 일이 없다.
--
-- 행이 없다는 것만으로는 꺼짐이 아니다. app.natural_cms_rule.configured 가 FALSE 인 동안에는
-- 코드 기본값을 그대로 따른다. 한 번이라도 저장한 뒤부터 없는 행이 꺼짐이 되며, 그때부터
-- Handler가 새로 연 필드는 관리자가 켜기 전까지 닫혀 있다. 새 필드를 켜진 채로 두면
-- 코드가 열 때마다 모델의 손이 조용히 넓어진다.
--
-- 게시물은 계약상 BOARD 안에서 id 모양으로 갈리지만, 관리자에게는 게시판과 다른 대상이고
-- 필드도 따로 연다. 그래서 여기서는 BOARD_POST 로 구분해 저장한다.
CREATE TABLE app.natural_cms_field_selection (
    natural_cms_field_selection_id UUID PRIMARY KEY,
    resource_type VARCHAR(16) NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_natural_cms_field_selection UNIQUE (resource_type, field_name),
    CONSTRAINT ck_natural_cms_field_selection_resource_type CHECK (
        resource_type IN ('MENU', 'BOARD', 'BOARD_POST', 'CONTENT')),
    -- 명령의 fields 키와 같은 이름만 담는다. 공백이나 대문자 시작은 그 계약에 없다.
    CONSTRAINT ck_natural_cms_field_selection_field_name CHECK (
        field_name ~ '^[a-z][A-Za-z0-9]*$')
);

CREATE INDEX idx_natural_cms_field_selection_enabled
    ON app.natural_cms_field_selection (resource_type, field_name)
    WHERE enabled;

-- ai_workspace 가 관리자 API를 담당한다. cms_app 은 명령을 판정할 때 읽기만 하며,
-- 판정하는 연결이 판정 기준을 고칠 수 없게 쓰기를 주지 않는다.
GRANT SELECT, INSERT, UPDATE, DELETE ON app.natural_cms_field_selection TO ai_workspace;
GRANT SELECT ON app.natural_cms_field_selection TO cms_app;
