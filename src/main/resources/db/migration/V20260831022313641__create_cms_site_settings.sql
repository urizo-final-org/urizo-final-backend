-- Site-owned CMS settings. Template presentation fields remain in app.cms_template.

CREATE TABLE app.cms_site (
    site_key VARCHAR(40) PRIMARY KEY,
    site_name VARCHAR(100) NOT NULL,
    public_path VARCHAR(180) NOT NULL,
    template_key VARCHAR(40) NOT NULL REFERENCES app.cms_template(template_key),
    enabled_yn CHAR(1) NOT NULL DEFAULT 'Y',
    default_yn CHAR(1) NOT NULL DEFAULT 'N',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_cms_site_public_path UNIQUE (public_path),
    CONSTRAINT ck_cms_site_name CHECK (btrim(site_name) <> ''),
    CONSTRAINT ck_cms_site_public_path CHECK (public_path LIKE '/%'),
    CONSTRAINT ck_cms_site_enabled CHECK (enabled_yn IN ('Y', 'N')),
    CONSTRAINT ck_cms_site_default CHECK (default_yn IN ('Y', 'N')),
    CONSTRAINT ck_cms_site_default_enabled CHECK (default_yn = 'N' OR enabled_yn = 'Y')
);

CREATE UNIQUE INDEX uq_cms_site_single_default
    ON app.cms_site (default_yn) WHERE default_yn = 'Y';

INSERT INTO app.cms_site
    (site_key, site_name, public_path, template_key, enabled_yn, default_yn)
SELECT 'main', site_name, '/', template_key, 'Y', 'Y'
FROM app.cms_template
WHERE active_yn = 'Y'
ORDER BY template_key
LIMIT 1;

GRANT SELECT, INSERT, UPDATE, DELETE ON app.cms_site TO cms_app;
GRANT SELECT ON app.cms_site TO dbeaver_reader;
