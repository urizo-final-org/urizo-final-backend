-- AXMS-AI02-019: 버전별 검색 평가 결과를 남긴다.
--
-- score 컬럼은 이미 있지만 EVALUATE가 무조건 100을 쓰고 있었다. 숫자 하나로는 무엇을
-- 몇 건으로 쟀는지 말할 수 없어 관리자가 활성화를 판단할 근거가 되지 못한다.
--
-- 지표별 값·표본 수·측정 방식을 함께 남긴다. JSONB인 이유는 지표 구성이 바뀔 때마다
-- 컬럼을 늘리지 않기 위해서다(chunking_strategy와 같은 이유).
ALTER TABLE app.knowledge_version
    ADD COLUMN IF NOT EXISTS evaluation JSONB;

ALTER TABLE app.knowledge_version
    DROP CONSTRAINT IF EXISTS ck_knowledge_version_evaluation;

ALTER TABLE app.knowledge_version
    ADD CONSTRAINT ck_knowledge_version_evaluation
        CHECK (evaluation IS NULL OR jsonb_typeof(evaluation) = 'object');

COMMENT ON COLUMN app.knowledge_version.evaluation IS
    'AI02-019. 빌드가 잰 검색 평가(method/sampleSize/hit5/hit10/mrr10). NULL은 평가 전 버전.';
