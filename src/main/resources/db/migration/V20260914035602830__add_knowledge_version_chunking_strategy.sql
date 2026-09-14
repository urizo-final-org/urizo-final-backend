-- AXMS-AI02-018: 버전이 어떤 청킹 규칙으로 만들어졌는지 남긴다.
--
-- 이 칸이 없으면 두 버전의 청크 수가 달라도 "왜 달라졌는가"를 아무도 답할 수 없고,
-- 같은 전략으로 다시 빌드하는 것도 불가능하다(모델이 매번 다른 값을 낼 수 있다).
--
-- JSONB인 이유: 전략이 열린 값이라 컬럼을 늘려 가며 스키마를 바꾸고 싶지 않다.
-- 키 순서를 보존하지 않지만 이 값은 사람이 읽는 기록이지 순서 계약이 아니다.
ALTER TABLE app.knowledge_version
    ADD COLUMN IF NOT EXISTS chunking_strategy JSONB;

ALTER TABLE app.knowledge_version
    DROP CONSTRAINT IF EXISTS ck_knowledge_version_chunking_strategy;

ALTER TABLE app.knowledge_version
    ADD CONSTRAINT ck_knowledge_version_chunking_strategy
        CHECK (chunking_strategy IS NULL OR jsonb_typeof(chunking_strategy) = 'object');

COMMENT ON COLUMN app.knowledge_version.chunking_strategy IS
    'AI02-018. LLM이 정한 청킹 규칙(maxCharacters/overlapCharacters/reason). NULL은 문서당 1청크.';
