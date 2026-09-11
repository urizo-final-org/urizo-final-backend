-- NULL keeps the existing single image readable without rewriting CMS data.
-- An explicit empty array means that all image links have been removed.
ALTER TABLE app.cms_template
    ADD COLUMN hero_image_urls JSONB,
    ADD CONSTRAINT ck_cms_template_hero_images CHECK (
        hero_image_urls IS NULL OR (
            jsonb_typeof(hero_image_urls) = 'array'
            AND jsonb_array_length(hero_image_urls) <= 5
            AND NOT jsonb_path_exists(hero_image_urls, '$[*] ? (@.type() != "string" || @ == "")')
        )
    );
