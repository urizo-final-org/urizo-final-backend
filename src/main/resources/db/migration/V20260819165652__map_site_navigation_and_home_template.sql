-- Minimal menu-to-resource mapping and editable home-page fields for the local CMS demo.

ALTER TABLE app.cms_menu
    ADD COLUMN target_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN target_id BIGINT,
    ADD CONSTRAINT ck_cms_menu_target CHECK (
        (target_type = 'NONE' AND target_id IS NULL)
        OR (target_type IN ('CONTENT', 'BOARD') AND target_id IS NOT NULL));

ALTER TABLE app.cms_template
    ADD COLUMN hero_image_url VARCHAR(500) NOT NULL DEFAULT '/images/cms/hero-bio.svg',
    ADD COLUMN hero_title VARCHAR(160) NOT NULL DEFAULT 'Technology for a Better Tomorrow',
    ADD COLUMN hero_subtitle VARCHAR(300) NOT NULL DEFAULT '더 나은 내일을 만드는 AX Module Studio의 기술을 소개합니다.',
    ADD COLUMN hero_button_label VARCHAR(60) NOT NULL DEFAULT '사업 소개 보기',
    ADD COLUMN hero_button_url VARCHAR(180) NOT NULL DEFAULT '/products/ax-module-studio';

UPDATE app.cms_template
SET site_name = 'AX Bio Studio',
    header_text = 'Technology · Trust · Growth',
    footer_text = 'AX Bio Studio | 서울특별시 디지털로 123 | 02-1234-5678',
    hero_image_url = '/images/cms/hero-bio.svg',
    hero_title = 'Technology for a Better Tomorrow',
    hero_subtitle = '사람과 기술을 연결해 지속 가능한 비즈니스의 내일을 만듭니다.',
    hero_button_label = 'AX Bio Studio 소개',
    hero_button_url = '/about/company'
WHERE template_key = 'CLASSIC';

GRANT SELECT, INSERT, UPDATE, DELETE ON app.cms_menu, app.cms_template TO cms_app;
