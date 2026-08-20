-- Local demo CMS: one compact forward-only schema for the approved MVP.

ALTER TABLE app.admin_account
    ADD COLUMN display_name VARCHAR(80);

UPDATE app.admin_account
SET display_name = CASE role
    WHEN 'SUPER_ADMIN' THEN '최고 관리자'
    WHEN 'GENERAL_ADMIN' THEN '일반 관리자'
    ELSE login_id
END
WHERE display_name IS NULL;

ALTER TABLE app.admin_account
    ALTER COLUMN display_name SET NOT NULL,
    DROP CONSTRAINT ck_admin_account_role,
    ADD CONSTRAINT ck_admin_account_role
        CHECK (role IN ('SUPER_ADMIN', 'GENERAL_ADMIN', 'GENERAL_USER'));

CREATE TABLE app.cms_menu (
    menu_id BIGSERIAL PRIMARY KEY,
    menu_name VARCHAR(80) NOT NULL,
    path VARCHAR(180) NOT NULL,
    parent_menu_id BIGINT REFERENCES app.cms_menu(menu_id) ON DELETE SET NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_cms_menu_name CHECK (btrim(menu_name) <> ''),
    CONSTRAINT ck_cms_menu_path CHECK (path LIKE '/%')
);

CREATE TABLE app.cms_content (
    content_id BIGSERIAL PRIMARY KEY,
    author_id UUID NOT NULL REFERENCES app.admin_account(account_id),
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_yn CHAR(1) NOT NULL DEFAULT 'N',
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_cms_content_deleted CHECK (
        (deleted_yn = 'N' AND deleted_at IS NULL)
        OR (deleted_yn = 'Y' AND deleted_at IS NOT NULL))
);

CREATE TABLE app.cms_board (
    board_id BIGSERIAL PRIMARY KEY,
    board_name VARCHAR(100) NOT NULL,
    description VARCHAR(300) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_yn CHAR(1) NOT NULL DEFAULT 'N',
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_cms_board_deleted CHECK (
        (deleted_yn = 'N' AND deleted_at IS NULL)
        OR (deleted_yn = 'Y' AND deleted_at IS NOT NULL))
);

CREATE TABLE app.cms_post (
    post_id BIGSERIAL PRIMARY KEY,
    board_id BIGINT NOT NULL REFERENCES app.cms_board(board_id),
    author_id UUID NOT NULL REFERENCES app.admin_account(account_id),
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_yn CHAR(1) NOT NULL DEFAULT 'N',
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_cms_post_deleted CHECK (
        (deleted_yn = 'N' AND deleted_at IS NULL)
        OR (deleted_yn = 'Y' AND deleted_at IS NOT NULL))
);

CREATE TABLE app.cms_template (
    template_key VARCHAR(40) PRIMARY KEY,
    layout VARCHAR(40) NOT NULL,
    primary_color VARCHAR(16) NOT NULL,
    site_name VARCHAR(100) NOT NULL,
    header_text VARCHAR(200) NOT NULL,
    footer_text VARCHAR(200) NOT NULL,
    active_yn CHAR(1) NOT NULL DEFAULT 'N',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_cms_template_active CHECK (active_yn IN ('Y', 'N'))
);

CREATE UNIQUE INDEX uq_cms_template_single_active
    ON app.cms_template (active_yn) WHERE active_yn = 'Y';

INSERT INTO app.cms_menu (menu_name, path, display_order)
VALUES
    ('소개', '/about', 10),
    ('Products', '/products', 20),
    ('Services', '/services', 30),
    ('고객지원', '/support', 40);

INSERT INTO app.cms_menu (menu_name, path, parent_menu_id, display_order)
SELECT child.menu_name, child.path, parent.menu_id, child.display_order
FROM (VALUES
    ('소개', '회사 소개', '/about/company', 11),
    ('소개', '비전', '/about/vision', 12),
    ('Products', 'AX Module Studio', '/products/ax-module-studio', 21),
    ('Products', 'Solutions', '/products/solutions', 22),
    ('Services', 'Consulting', '/services/consulting', 31),
    ('Services', 'Technical Support', '/services/technical-support', 32),
    ('고객지원', '공지사항', '/support/notices', 41),
    ('고객지원', '문의하기', '/support/contact', 42)
) AS child(parent_name, menu_name, path, display_order)
JOIN app.cms_menu parent ON parent.menu_name = child.parent_name
WHERE parent.parent_menu_id IS NULL;

INSERT INTO app.cms_template
    (template_key, layout, primary_color, site_name, header_text, footer_text, active_yn)
VALUES
    ('CLASSIC', 'CLASSIC', '#6957E8', 'AX Module Studio', '새로운 소식을 확인하세요', 'AX Module Studio · Local Demo', 'Y'),
    ('MINIMAL', 'MINIMAL', '#0E9F76', 'AX Studio', '간결하게 만나는 콘텐츠', 'Made for the local demo', 'N'),
    ('BOLD', 'BOLD', '#D4495B', 'AX Creative', '아이디어를 크게 보여주세요', 'AX Creative CMS', 'N');

CREATE OR REPLACE VIEW app.admin_account_status AS
SELECT account_id, login_id, role, status, created_at, updated_at, display_name
FROM app.admin_account;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    app.cms_menu, app.cms_content, app.cms_board, app.cms_post, app.cms_template TO cms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA app TO cms_app;
GRANT SELECT ON app.admin_account_status,
    app.cms_menu, app.cms_content, app.cms_board, app.cms_post, app.cms_template TO dbeaver_reader;
