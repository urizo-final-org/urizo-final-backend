-- Keep legacy scalar/list data intact. NULL reads through the existing image URLs.
ALTER TABLE app.cms_template ADD COLUMN hero_images jsonb;

ALTER TABLE app.cms_template ADD CONSTRAINT ck_cms_template_hero_image_captions
    CHECK (hero_images IS NULL OR (
        jsonb_typeof(hero_images) = 'array'
        AND jsonb_array_length(hero_images) <= 5
        AND NOT jsonb_path_exists(hero_images, '$[*] ? (@.type() != "object")')
        AND NOT jsonb_path_exists(hero_images, '$[*] ? (!(exists(@.url)) || @.url.type() != "string" || @.url == "")')
        AND NOT jsonb_path_exists(hero_images, '$[*] ? (exists(@.title) && @.title.type() != "string")')
        AND NOT jsonb_path_exists(hero_images, '$[*] ? (exists(@.description) && @.description.type() != "string")')
    ));

COMMENT ON COLUMN app.cms_template.hero_images IS
    'Ordered main images with optional title and description; NULL uses legacy URLs; [] means no images.';
