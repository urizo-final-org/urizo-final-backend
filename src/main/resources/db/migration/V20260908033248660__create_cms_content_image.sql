-- 컨텐츠 본문에 넣는 이미지. 파일이 아니라 DB에 담는다.
-- 로컬 시연 규모에서는 볼륨·마운트·권한을 새로 만들지 않는 편이 작고, 백업과 삭제가 DB를 따라간다.
-- 본문에는 바이트가 아니라 GET /api/site/images/{id} 주소만 들어간다.
--
-- 소프트 삭제 컬럼을 두지 않는다. 컨텐츠를 지워도 이미지는 남긴다 -- 컨텐츠가 소프트 삭제라
-- 되살릴 여지가 있는데 이미지를 먼저 지우면 짝이 맞지 않는다.

CREATE TABLE app.cms_content_image (
    image_id BIGSERIAL PRIMARY KEY,
    author_id UUID NOT NULL REFERENCES app.admin_account(account_id),
    content_type VARCHAR(40) NOT NULL,
    byte_size INTEGER NOT NULL,
    bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 저장 전에 파일 앞부분 바이트로 실제 형식을 확인한다. 확장자와 요청 헤더는 믿지 않는다.
    -- SVG는 스크립트를 품을 수 있어 목록에서 뺀다.
    CONSTRAINT ck_cms_content_image_type CHECK (
        content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_cms_content_image_size CHECK (byte_size > 0)
);

GRANT SELECT, INSERT, DELETE ON app.cms_content_image TO cms_app;
GRANT USAGE, SELECT ON SEQUENCE app.cms_content_image_image_id_seq TO cms_app;
GRANT SELECT ON app.cms_content_image TO dbeaver_reader;
