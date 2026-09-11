-- 문서 대표 사진 URL. COLLECT 단계가 원천 메타데이터의 firstimage 값을 그대로 옮겨 담는다.
-- 표본 코퍼스 500건 중 405건에 값이 있고 나머지는 NULL — 사진이 없는 문서가 정상이라 NULL이 기본이다.
--
-- source_url과 달리 https를 강제하지 않는다. 원천 값에 http가 228건 섞여 있고(전부
-- tong.visitkorea.or.kr), 스킴을 https로 바꿔 적으면 원천에 없는 주소를 지어내는 것이 된다.
-- 대신 스킴이 http/https인지만 확인해 빈 문자열·상대 경로가 들어오는 것을 막는다.
--
-- 본문(content)과 content_digest는 그대로다. 사진은 표시용 메타데이터일 뿐이며 검색·인용
-- 판정에 쓰지 않는다.

ALTER TABLE app.source_document
    ADD COLUMN image_url VARCHAR(500);

ALTER TABLE app.source_document
    ADD CONSTRAINT ck_source_document_image_url
        CHECK (image_url IS NULL OR image_url ~ '^https?://');
