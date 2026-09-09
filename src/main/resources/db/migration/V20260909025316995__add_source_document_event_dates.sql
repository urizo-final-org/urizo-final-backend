-- 행사 시작·종료일 메타데이터. COLLECT 단계가 본문의 "[행사기간] YYYYMMDD ~ YYYYMMDD" 줄을
-- 파싱해 채운다. 줄이 없거나 형식이 다르면 둘 다 NULL — 축제가 아닌 문서가 대부분이라 NULL이 기본이다.
-- 본문(content)과 content_digest는 그대로다. 날짜는 검색·표시용 메타데이터일 뿐이다.

ALTER TABLE app.source_document
    ADD COLUMN event_start_date DATE,
    ADD COLUMN event_end_date DATE;
